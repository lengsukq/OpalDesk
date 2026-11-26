export const FEEDBACK_SCHEMA_VERSION = 1;
export const MAX_BATCH_EVENT_IDS = 20;
export const MAX_REPLY_NOTE_CHARS = 240;
export const MAX_SUMMARY_CHARS = 120;

export const FeedbackKind = Object.freeze({
  BUG: "bug",
  FEATURE: "feature",
});

export const FeedbackStatus = Object.freeze({
  OPEN: "open",
  FIXED_PENDING_RELEASE: "fixed_pending_release",
  FIXED_IN_VERSION: "fixed_in_version",
  RESOLVED_OTHER: "resolved",
  IGNORED: "ignored",
});

const EVENT_ID_REGEX = /^[0-9a-f]{32}$/;
const SHORT_CODE_REGEX = /^[0-9a-fA-F]{8}$/;
const INSTALLATION_ID_REGEX = /^[A-Za-z0-9._-]{8,80}$/;
const SEMVER_REGEX = /^\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z.-]+)?$/;
const FIXED_DIRECTIVE_REGEX = /\[fixed\s*:\s*v?([0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[0-9A-Za-z.-]+)?)\]/i;
const REPLY_DIRECTIVE_REGEX = /^\[(?:reply|public)\]\s*(.+)$/i;
const FEATURE_TITLE_PREFIX_REGEX = /^\[feature\s*request\]\s*/i;
const USER_REPORT_PREFIX_REGEX = /^\[(?:user\s*report|feature\s*request)\]\s*/i;
const ANONYMOUS_MANUAL_LOG_REGEX = /^(?:manual diagnostic log|feature suggestion) from\b/i;

export function validateInstallationId(raw) {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  return INSTALLATION_ID_REGEX.test(trimmed) ? trimmed : null;
}

export function validateEventIds(rawCsv) {
  if (typeof rawCsv !== "string" || !rawCsv.trim()) return [];
  const seen = new Set();
  const out = [];
  for (const part of rawCsv.split(",")) {
    const normalized = part.trim().replace(/-/g, "").toLowerCase();
    if (!EVENT_ID_REGEX.test(normalized) || seen.has(normalized)) continue;
    seen.add(normalized);
    out.push(normalized);
    if (out.length >= MAX_BATCH_EVENT_IDS) break;
  }
  return out;
}

export function validateShortCode(raw) {
  if (typeof raw !== "string") return null;
  const cleaned = raw.trim().replace(/^#/, "");
  return SHORT_CODE_REGEX.test(cleaned) ? cleaned.toUpperCase() : null;
}

export function formatShortCode(eventId) {
  return String(eventId || "").replace(/-/g, "").slice(0, 8).toUpperCase();
}

export function extractSemverFromSentryRelease(raw) {
  if (typeof raw !== "string") return "";
  let candidate = raw.trim();
  if (!candidate) return "";
  const atIdx = candidate.lastIndexOf("@");
  if (atIdx >= 0 && atIdx + 1 < candidate.length) {
    candidate = candidate.slice(atIdx + 1);
  }
  candidate = candidate.split("+")[0].trim();
  if (candidate.startsWith("v") || candidate.startsWith("V")) {
    candidate = candidate.slice(1).trim();
  }
  return SEMVER_REGEX.test(candidate) ? candidate : "";
}

function sanitizeReplyText(raw) {
  const cleaned = String(raw || "")
    .replace(/[\u0000-\u001F\u007F]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return cleaned.length > MAX_REPLY_NOTE_CHARS
    ? `${cleaned.slice(0, MAX_REPLY_NOTE_CHARS - 1).trimEnd()}…`
    : cleaned;
}

function commentTimestampMs(comment) {
  const ts = Date.parse(comment?.dateCreated || "");
  return Number.isFinite(ts) ? ts : 0;
}

export function parseMaintainerComments(comments) {
  if (!Array.isArray(comments) || comments.length === 0) {
    return { fixedVersion: "", replyNote: "" };
  }
  const ordered = [...comments].sort((a, b) => commentTimestampMs(b) - commentTimestampMs(a));
  let fixedVersion = "";
  let replyNote = "";

  for (const comment of ordered) {
    const body = typeof comment?.text === "string"
      ? comment.text
      : (typeof comment?.data?.text === "string" ? comment.data.text : "");
    if (!body.trim()) continue;

    if (!fixedVersion) {
      const fixedMatch = FIXED_DIRECTIVE_REGEX.exec(body);
      if (fixedMatch) {
        fixedVersion = extractSemverFromSentryRelease(fixedMatch[1]);
      }
    }

    if (!replyNote) {
      for (const line of body.split(/\r?\n/)) {
        const replyMatch = REPLY_DIRECTIVE_REGEX.exec(line.trim());
        if (replyMatch) {
          const sanitized = sanitizeReplyText(replyMatch[1]);
          if (sanitized) {
            replyNote = sanitized;
            break;
          }
        }
      }
    }

    if (fixedVersion && replyNote) break;
  }

  return { fixedVersion, replyNote };
}

export function extractTagMap(eventObj) {
  const map = Object.create(null);
  const tags = Array.isArray(eventObj?.tags) ? eventObj.tags : [];
  for (const tag of tags) {
    if (tag && typeof tag.key === "string" && typeof tag.value === "string") {
      map[tag.key] = tag.value;
    }
  }
  return map;
}

export function isManualReportOwnedByInstallation(eventObj, expectedInstallationId) {
  if (!eventObj || !expectedInstallationId) return false;
  const tags = extractTagMap(eventObj);
  return tags.report_source === "manual_user_report" &&
    tags.installation_id === expectedInstallationId;
}

const RELEASE_COMMIT_REGEX = /\bRelease\s+v?([0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[0-9A-Za-z.-]+)?)\b/i;

export function resolveVersionFromInCommit(inCommit, projectReleases = []) {
  if (!inCommit || typeof inCommit !== "object") return null;

  if (Array.isArray(inCommit.releases)) {
    for (const rel of inCommit.releases) {
      const ver = extractSemverFromSentryRelease(typeof rel === "string" ? rel : rel?.version);
      if (ver) return { status: FeedbackStatus.FIXED_IN_VERSION, fixedVersion: ver };
    }
  }

  const msg = typeof inCommit.message === "string" ? inCommit.message : "";
  const msgMatch = RELEASE_COMMIT_REGEX.exec(msg);
  if (msgMatch) {
    const ver = extractSemverFromSentryRelease(msgMatch[1]);
    if (ver) return { status: FeedbackStatus.FIXED_IN_VERSION, fixedVersion: ver };
  }

  const commitTs = Date.parse(inCommit.dateCreated || "");
  if (Number.isFinite(commitTs) && Array.isArray(projectReleases) && projectReleases.length > 0) {
    const parsedReleases = projectReleases
      .map((r) => ({
        version: extractSemverFromSentryRelease(typeof r === "string" ? r : r?.version),
        createdTs: Date.parse((r && r.dateCreated) || ""),
      }))
      .filter((r) => r.version && Number.isFinite(r.createdTs))
      .sort((a, b) => a.createdTs - b.createdTs);

    const matched = parsedReleases.find((r) => r.createdTs >= commitTs);
    if (matched) {
      return { status: FeedbackStatus.FIXED_IN_VERSION, fixedVersion: matched.version };
    }
    if (parsedReleases.length > 0) {
      return { status: FeedbackStatus.FIXED_PENDING_RELEASE, fixedVersion: "" };
    }
  }

  return null;
}

export function extractReportedVersion(eventObj) {
  if (!eventObj || typeof eventObj !== "object") return "";
  const tags = extractTagMap(eventObj);
  const fromTag = extractSemverFromSentryRelease(tags.app_version || "");
  if (fromTag) return fromTag;
  return extractSemverFromSentryRelease(
    typeof eventObj.release === "string" ? eventObj.release : eventObj.release?.version || "",
  );
}

export function extractFeedbackKind(obj) {
  if (!obj || typeof obj !== "object") return FeedbackKind.BUG;
  const tags = extractTagMap(obj);
  const tagKind = String(tags.feedback_kind || "").trim().toLowerCase();
  if (tagKind === FeedbackKind.FEATURE) return FeedbackKind.FEATURE;
  if (tagKind === FeedbackKind.BUG) return FeedbackKind.BUG;
  const rawTitle = typeof obj.title === "string"
    ? obj.title
    : (typeof obj.metadata?.title === "string" ? obj.metadata.title : "");
  if (FEATURE_TITLE_PREFIX_REGEX.test(rawTitle.trim())) {
    return FeedbackKind.FEATURE;
  }
  return FeedbackKind.BUG;
}

export function extractPublicIssueSummary(issue, kind = extractFeedbackKind(issue)) {
  const rawTitle = typeof issue?.title === "string"
    ? issue.title
    : (typeof issue?.metadata?.title === "string" ? issue.metadata.title : "");
  const stripped = rawTitle
    .replace(USER_REPORT_PREFIX_REGEX, "")
    .replace(/[\u0000-\u001F\u007F]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!stripped || ANONYMOUS_MANUAL_LOG_REGEX.test(stripped)) {
    return kind === FeedbackKind.FEATURE ? "功能建议" : "手动诊断日志上报";
  }
  return stripped.length > MAX_SUMMARY_CHARS
    ? `${stripped.slice(0, MAX_SUMMARY_CHARS - 1).trimEnd()}…`
    : stripped;
}

export function mapSentryIssueToStatus(issue, comments = [], projectReleases = []) {
  const issueStatus = String(issue?.status || "").toLowerCase();
  const statusDetails = issue?.statusDetails && typeof issue.statusDetails === "object"
    ? issue.statusDetails
    : {};
  const commentMeta = parseMaintainerComments(comments);
  const updatedAt = String(issue?.lastSeen || statusDetails?.resolvedAt || "");
  const kind = extractFeedbackKind(issue);
  const summary = extractPublicIssueSummary(issue, kind);

  if (issueStatus === "ignored") {
    return {
      kind,
      status: FeedbackStatus.IGNORED,
      fixedVersion: "",
      replyNote: commentMeta.replyNote,
      summary,
      updatedAt,
    };
  }

  if (issueStatus === "resolved") {
    const releaseVersion = commentMeta.fixedVersion ||
      extractSemverFromSentryRelease(statusDetails?.inRelease || "");
    if (releaseVersion) {
      return {
        kind,
        status: FeedbackStatus.FIXED_IN_VERSION,
        fixedVersion: releaseVersion,
        replyNote: commentMeta.replyNote,
        summary,
        updatedAt,
      };
    }
    const commitResolution = resolveVersionFromInCommit(statusDetails?.inCommit, projectReleases);
    if (commitResolution) {
      return {
        kind,
        status: commitResolution.status,
        fixedVersion: commitResolution.fixedVersion,
        replyNote: commentMeta.replyNote,
        summary,
        updatedAt,
      };
    }
    if (statusDetails?.inNextRelease === true) {
      return {
        kind,
        status: FeedbackStatus.FIXED_PENDING_RELEASE,
        fixedVersion: "",
        replyNote: commentMeta.replyNote,
        summary,
        updatedAt,
      };
    }
    return {
      kind,
      status: FeedbackStatus.RESOLVED_OTHER,
      fixedVersion: "",
      replyNote: commentMeta.replyNote,
      summary,
      updatedAt,
    };
  }

  return {
    kind,
    status: FeedbackStatus.OPEN,
    fixedVersion: "",
    replyNote: commentMeta.replyNote,
    summary,
    updatedAt,
  };
}

export function validateEnvelopeHeaderDsn(envelopeTextOrFirstLine, allowedProjectId, allowedIngestHost) {
  const firstNewline = envelopeTextOrFirstLine.indexOf("\n");
  const headerLine = (firstNewline >= 0
    ? envelopeTextOrFirstLine.slice(0, firstNewline)
    : envelopeTextOrFirstLine).trim();
  if (!headerLine) return { ok: false, reason: "empty_envelope_header" };

  let header;
  try {
    header = JSON.parse(headerLine);
  } catch {
    return { ok: false, reason: "invalid_envelope_header_json" };
  }

  const rawDsn = typeof header?.dsn === "string" ? header.dsn.trim() : "";
  if (!rawDsn) return { ok: false, reason: "missing_dsn" };

  let parsed;
  try {
    parsed = new URL(rawDsn);
  } catch {
    return { ok: false, reason: "invalid_dsn_url" };
  }

  if (parsed.protocol !== "https:") return { ok: false, reason: "dsn_must_be_https" };
  if (allowedIngestHost && parsed.hostname.toLowerCase() !== allowedIngestHost.toLowerCase()) {
    return { ok: false, reason: "untrusted_sentry_host" };
  }
  const segments = parsed.pathname.split("/").filter(Boolean);
  const projectId = segments[segments.length - 1] || "";
  if (!allowedProjectId || projectId !== String(allowedProjectId)) {
    return { ok: false, reason: "disallowed_sentry_project" };
  }
  return { ok: true, projectId };
}
