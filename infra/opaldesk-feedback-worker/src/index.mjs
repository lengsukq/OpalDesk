import {
  FEEDBACK_SCHEMA_VERSION,
  FeedbackKind,
  extractFeedbackKind,
  extractReportedVersion,
  extractTagMap,
  formatShortCode,
  isManualReportOwnedByInstallation,
  mapSentryIssueToStatus,
  validateEnvelopeHeaderDsn,
  validateEventIds,
  validateInstallationId,
  validateShortCode,
} from "./sentry_mapper.mjs";

const MAX_ENVELOPE_BYTES = 1024 * 1024;
const SENTRY_TIMEOUT_MS = 10_000;
const ISSUE_CACHE_TTL_MS = 60_000;
const MAX_ISSUE_CACHE_ENTRIES = 500;
const LOOKUP_SCAN_EVENTS_LIMIT = 100;

const issueTtlCache = new Map();

function readCachedIssue(issueId, nowMs = Date.now()) {
  const entry = issueTtlCache.get(issueId);
  if (!entry) return null;
  if (entry.expiresAtMs <= nowMs) {
    issueTtlCache.delete(issueId);
    return null;
  }
  return entry.value;
}

function writeCachedIssue(issueId, value, nowMs = Date.now()) {
  if (issueTtlCache.size >= MAX_ISSUE_CACHE_ENTRIES) {
    const oldestKey = issueTtlCache.keys().next().value;
    if (oldestKey !== undefined) issueTtlCache.delete(oldestKey);
  }
  issueTtlCache.set(issueId, { value, expiresAtMs: nowMs + ISSUE_CACHE_TTL_MS });
}

function jsonResponse(payload, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "x-content-type-options": "nosniff",
      ...extraHeaders,
    },
  });
}

function getSentryConfig(env) {
  return {
    apiBase: String(env.SENTRY_API_BASE || "https://us.sentry.io/api/0").trim().replace(/\/+$/, ""),
    org: String(env.SENTRY_ORG || "").trim(),
    project: String(env.SENTRY_PROJECT || "").trim(),
    projectId: String(env.SENTRY_PROJECT_ID || "").trim(),
    ingestHost: String(env.SENTRY_INGEST_HOST || "").trim(),
    authToken: String(env.SENTRY_AUTH_TOKEN || "").trim(),
  };
}

function hasReadConfig(cfg) {
  return Boolean(cfg.authToken && cfg.org && cfg.project);
}

function hasEnvelopeConfig(cfg) {
  return Boolean(cfg.projectId && cfg.ingestHost);
}

async function sentryGetJson(cfg, path, fetchImpl) {
  const resp = await fetchImpl(`${cfg.apiBase}${path}`, {
    method: "GET",
    headers: {
      authorization: `Bearer ${cfg.authToken}`,
      accept: "application/json",
      "user-agent": "opaldesk-feedback-worker/1.0",
    },
    signal: AbortSignal.timeout(SENTRY_TIMEOUT_MS),
  });
  if (resp.status === 404) return { ok: false, status: 404, data: null };
  if (!resp.ok) {
    return { ok: false, status: resp.status, data: null };
  }
  const data = await resp.json();
  return { ok: true, status: resp.status, data };
}

async function fetchProjectReleases(cfg, fetchImpl, bypassTtlCache = false) {
  const cacheKey = `releases:${cfg.org}/${cfg.project}`;
  if (!bypassTtlCache) {
    const cached = readCachedIssue(cacheKey);
    if (cached) return cached;
  }
  const res = await sentryGetJson(
    cfg,
    `/projects/${encodeURIComponent(cfg.org)}/${encodeURIComponent(cfg.project)}/releases/?per_page=25`,
    fetchImpl,
  );
  const list = res.ok && Array.isArray(res.data) ? res.data : [];
  if (!bypassTtlCache && list.length > 0) {
    writeCachedIssue(cacheKey, list);
  }
  return list;
}

async function fetchIssueWithStatus(cfg, issueId, requestIssueCache, fetchImpl, bypassTtlCache = false) {
  if (!bypassTtlCache) {
    const cached = readCachedIssue(issueId);
    if (cached) return cached;
  }
  if (!requestIssueCache.has(issueId)) {
    const promise = (async () => {
      const [issueRes, commentsRes] = await Promise.all([
        sentryGetJson(cfg, `/issues/${encodeURIComponent(issueId)}/`, fetchImpl),
        sentryGetJson(cfg, `/issues/${encodeURIComponent(issueId)}/comments/`, fetchImpl),
      ]);
      if (!issueRes.ok || !issueRes.data) return null;
      const comments = commentsRes.ok && Array.isArray(commentsRes.data) ? commentsRes.data : [];
      const needsReleaseTimeline = issueRes.data.status === "resolved" &&
        !issueRes.data.statusDetails?.inRelease &&
        Boolean(issueRes.data.statusDetails?.inCommit);
      const releases = needsReleaseTimeline
        ? await fetchProjectReleases(cfg, fetchImpl, bypassTtlCache)
        : [];
      const mapped = mapSentryIssueToStatus(issueRes.data, comments, releases);
      if (!bypassTtlCache) {
        writeCachedIssue(issueId, mapped);
      }
      return mapped;
    })();
    requestIssueCache.set(issueId, promise);
  }
  return requestIssueCache.get(issueId);
}

async function handleEnvelopeProxy(request, env, fetchImpl) {
  const cfg = getSentryConfig(env);
  if (!hasEnvelopeConfig(cfg)) {
    return jsonResponse({ error: "envelope_proxy_not_configured" }, 503);
  }
  const contentLengthHeader = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(contentLengthHeader) && contentLengthHeader > MAX_ENVELOPE_BYTES) {
    return jsonResponse({ error: "envelope_too_large" }, 413);
  }

  const bodyBuf = new Uint8Array(await request.arrayBuffer());
  if (bodyBuf.byteLength === 0 || bodyBuf.byteLength > MAX_ENVELOPE_BYTES) {
    return jsonResponse({ error: "invalid_envelope_size" }, 413);
  }

  const headSlice = new TextDecoder("utf-8", { fatal: false }).decode(bodyBuf.subarray(0, 4096));
  const dsnCheck = validateEnvelopeHeaderDsn(headSlice, cfg.projectId, cfg.ingestHost);
  if (!dsnCheck.ok) {
    return jsonResponse({ error: dsnCheck.reason }, 403);
  }

  const targetUrl = `https://${cfg.ingestHost}/api/${cfg.projectId}/envelope/`;
  const headers = new Headers();
  headers.set("content-type", request.headers.get("content-type") || "application/x-sentry-envelope");
  const sentryAuth = request.headers.get("x-sentry-auth");
  if (sentryAuth) headers.set("x-sentry-auth", sentryAuth);

  const upstream = await fetchImpl(targetUrl, {
    method: "POST",
    headers,
    body: bodyBuf,
    signal: AbortSignal.timeout(15_000),
  });
  const respText = await upstream.text();
  return new Response(respText, {
    status: upstream.status,
    headers: {
      "content-type": upstream.headers.get("content-type") || "application/json; charset=utf-8",
    },
  });
}

async function handleBatchStatus(requestUrl, env, fetchImpl, bypassTtlCache = false) {
  const cfg = getSentryConfig(env);
  if (!hasReadConfig(cfg)) {
    return jsonResponse({ error: "sentry_read_not_configured" }, 503);
  }

  const installationId = validateInstallationId(requestUrl.searchParams.get("installation_id"));
  if (!installationId) {
    return jsonResponse({ error: "invalid_installation_id" }, 400);
  }

  const eventIds = validateEventIds(requestUrl.searchParams.get("event_ids"));
  if (eventIds.length === 0) {
    return jsonResponse({
      schema: FEEDBACK_SCHEMA_VERSION,
      generatedAt: new Date().toISOString(),
      items: {},
    });
  }

  const issueCache = new Map();
  const items = {};

  await Promise.all(eventIds.map(async (eventId) => {
    const evRes = await sentryGetJson(
      cfg,
      `/projects/${encodeURIComponent(cfg.org)}/${encodeURIComponent(cfg.project)}/events/${eventId}/`,
      fetchImpl,
    );
    if (!evRes.ok || !evRes.data) return;
    if (!isManualReportOwnedByInstallation(evRes.data, installationId)) return;

    const groupId = String(evRes.data.groupID || "").trim();
    if (!groupId) return;
    const mapped = await fetchIssueWithStatus(cfg, groupId, issueCache, fetchImpl, bypassTtlCache);
    if (!mapped) return;
    const reportedVersion = extractTagMap(evRes.data).app_version || "";
    const evKind = extractFeedbackKind(evRes.data);
    const kind = evKind !== FeedbackKind.BUG ? evKind : mapped.kind;

    items[eventId] = {
      shortCode: formatShortCode(eventId),
      kind,
      status: mapped.status,
      fixedVersion: mapped.fixedVersion,
      replyNote: mapped.replyNote,
      summary: mapped.summary,
      reportedVersion,
      updatedAt: mapped.updatedAt,
    };
  }));

  return jsonResponse(
    {
      schema: FEEDBACK_SCHEMA_VERSION,
      generatedAt: new Date().toISOString(),
      items,
    },
    200,
    { "cache-control": "private, max-age=60" },
  );
}

async function resolveEventOrIssueByShortCode(cfg, code, fetchImpl, bypassTtlCache = false) {
  const issueQuery = encodeURIComponent(`feedback_code:${code}`);
  const issueListRes = await sentryGetJson(
    cfg,
    `/projects/${encodeURIComponent(cfg.org)}/${encodeURIComponent(cfg.project)}/issues/?query=${issueQuery}&limit=1`,
    fetchImpl,
  );
  if (issueListRes.ok && Array.isArray(issueListRes.data) && issueListRes.data.length > 0) {
    const issue = issueListRes.data[0];
    const mapped = await fetchIssueWithStatus(cfg, String(issue.id), new Map(), fetchImpl, bypassTtlCache);
    if (!mapped) return null;
    return {
      eventId: "",
      reportedVersion: "",
      kind: mapped.kind,
      mapped,
    };
  }

  // Fallback for reports submitted before feedback_code tag existed: match eventID prefix among manual reports.
  const eventsRes = await sentryGetJson(
    cfg,
    `/projects/${encodeURIComponent(cfg.org)}/${encodeURIComponent(cfg.project)}/events/?full=false&per_page=${LOOKUP_SCAN_EVENTS_LIMIT}`,
    fetchImpl,
  );
  if (!eventsRes.ok || !Array.isArray(eventsRes.data)) return null;

  const lowerPrefix = code.toLowerCase();
  const matched = eventsRes.data.find((ev) => {
    const id = String(ev?.eventID || "").toLowerCase();
    if (!id.startsWith(lowerPrefix)) return false;
    return extractTagMap(ev).report_source === "manual_user_report";
  });
  if (!matched || !matched.groupID) return null;

  const mapped = await fetchIssueWithStatus(cfg, String(matched.groupID), new Map(), fetchImpl, bypassTtlCache);
  if (!mapped) return null;
  const evKind = extractFeedbackKind(matched);
  return {
    eventId: String(matched.eventID || "").toLowerCase(),
    reportedVersion: extractTagMap(matched).app_version || "",
    kind: evKind !== FeedbackKind.BUG ? evKind : mapped.kind,
    mapped,
  };
}

async function handleLookupByCode(requestUrl, env, fetchImpl, bypassTtlCache = false) {
  const cfg = getSentryConfig(env);
  if (!hasReadConfig(cfg)) {
    return jsonResponse({ error: "sentry_read_not_configured" }, 503);
  }

  const code = validateShortCode(requestUrl.searchParams.get("code"));
  if (!code) {
    return jsonResponse({ error: "invalid_feedback_code" }, 400);
  }

  const resolved = await resolveEventOrIssueByShortCode(cfg, code, fetchImpl, bypassTtlCache);
  if (!resolved) {
    return jsonResponse({
      schema: FEEDBACK_SCHEMA_VERSION,
      found: false,
      item: null,
    }, 404);
  }

  return jsonResponse(
    {
      schema: FEEDBACK_SCHEMA_VERSION,
      found: true,
      item: {
        eventId: resolved.eventId,
        shortCode: code,
        kind: resolved.kind || resolved.mapped.kind || FeedbackKind.BUG,
        status: resolved.mapped.status,
        fixedVersion: resolved.mapped.fixedVersion,
        replyNote: resolved.mapped.replyNote,
        summary: resolved.mapped.summary,
        reportedVersion: resolved.reportedVersion || "",
        updatedAt: resolved.mapped.updatedAt,
      },
    },
    200,
    { "cache-control": "public, max-age=60" },
  );
}

export function createWorkerHandler({ fetchImpl = fetch, bypassTtlCache = false } = {}) {
  return {
    async fetch(request, env = {}) {
      const url = new URL(request.url);

      if (request.method === "GET" && url.pathname === "/healthz") {
        const cfg = getSentryConfig(env);
        return jsonResponse({
          ok: true,
          service: "opaldesk-feedback-worker",
          sentryConfigured: hasReadConfig(cfg),
          envelopeConfigured: hasEnvelopeConfig(cfg),
        });
      }

      if (request.method === "POST" && (url.pathname === "/envelope" || url.pathname === "/")) {
        return handleEnvelopeProxy(request, env, fetchImpl);
      }

      if (request.method === "GET" && url.pathname === "/v1/feedback/status") {
        return handleBatchStatus(url, env, fetchImpl, bypassTtlCache);
      }

      if (request.method === "GET" && url.pathname === "/v1/feedback/lookup") {
        return handleLookupByCode(url, env, fetchImpl, bypassTtlCache);
      }

      return jsonResponse({ error: "not_found" }, 404);
    },
  };
}

export default createWorkerHandler();
