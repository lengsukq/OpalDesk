import test from "node:test";
import assert from "node:assert/strict";
import {
  FeedbackKind,
  FeedbackStatus,
  extractFeedbackKind,
  extractSemverFromSentryRelease,
  isManualReportOwnedByInstallation,
  mapSentryIssueToStatus,
  parseMaintainerComments,
  validateEnvelopeHeaderDsn,
  validateEventIds,
  validateInstallationId,
  validateShortCode,
} from "../src/sentry_mapper.mjs";
import { createWorkerHandler } from "../src/index.mjs";

test("extractSemverFromSentryRelease normalizes appId@ver+build and v prefixes", () => {
  assert.equal(extractSemverFromSentryRelease("io.github.lengsukq.opaldesk@1.0.16+10016"), "1.0.16");
  assert.equal(extractSemverFromSentryRelease("v1.0.18-beta"), "1.0.18-beta");
  assert.equal(extractSemverFromSentryRelease("1.2.3"), "1.2.3");
  assert.equal(extractSemverFromSentryRelease("invalid-tag"), "");
});

test("parseMaintainerComments prefers newest fixed directive and public reply", () => {
  const comments = [
    {
      dateCreated: "2026-11-25T10:00:00Z",
      text: "[fixed:1.0.15]\n[reply] 临时测试备注",
    },
    {
      dateCreated: "2026-11-26T10:00:00Z",
      text: "内部排查记录\n[fixed:v1.0.16]\n[reply] 已修复外接屏拖动黑屏，请升级到 v1.0.16 验证",
    },
  ];
  const parsed = parseMaintainerComments(comments);
  assert.equal(parsed.fixedVersion, "1.0.16");
  assert.equal(parsed.replyNote, "已修复外接屏拖动黑屏，请升级到 v1.0.16 验证");
});

test("mapSentryIssueToStatus maps open, ignored, inNextRelease, inRelease, and comment override", () => {
  assert.equal(mapSentryIssueToStatus({ status: "unresolved", statusDetails: {} }).status, FeedbackStatus.OPEN);
  assert.equal(mapSentryIssueToStatus({ status: "ignored", statusDetails: {} }).status, FeedbackStatus.IGNORED);
  assert.equal(
    mapSentryIssueToStatus({ status: "resolved", statusDetails: { inNextRelease: true } }).status,
    FeedbackStatus.FIXED_PENDING_RELEASE,
  );

  const byRelease = mapSentryIssueToStatus({
    status: "resolved",
    statusDetails: { inRelease: "io.github.lengsukq.opaldesk@1.0.17+10017" },
    lastSeen: "2026-11-26T12:00:00Z",
  });
  assert.equal(byRelease.status, FeedbackStatus.FIXED_IN_VERSION);
  assert.equal(byRelease.fixedVersion, "1.0.17");

  const byComment = mapSentryIssueToStatus(
    {
      status: "resolved",
      title: "[User Report] 外接屏拖动黑屏",
      statusDetails: {},
    },
    [{ dateCreated: "2026-11-26T12:05:00Z", text: "[fixed:1.0.18]\n[public] 修复了 UHID 坐标映射" }],
  );
  assert.equal(byComment.status, FeedbackStatus.FIXED_IN_VERSION);
  assert.equal(byComment.fixedVersion, "1.0.18");
  assert.equal(byComment.replyNote, "修复了 UHID 坐标映射");
  assert.equal(byComment.summary, "外接屏拖动黑屏");

  const anonSummary = mapSentryIssueToStatus({
    status: "unresolved",
    title: "[User Report] Manual diagnostic log from alice",
    statusDetails: {},
  });
  assert.equal(anonSummary.summary, "手动诊断日志上报");

  const releases = [
    { version: "io.github.lengsukq.opaldesk@1.0.15+10015", dateCreated: "2026-11-25T15:41:15Z" },
    { version: "io.github.lengsukq.opaldesk@1.0.16+10016", dateCreated: "2026-11-26T03:48:01Z" },
  ];
  const byCommitTimeline = mapSentryIssueToStatus(
    {
      status: "resolved",
      statusDetails: {
        inCommit: {
          id: "9b826a2",
          message: "fix(input,cast,tray): resolve UHID auto-fallback",
          dateCreated: "2026-11-26T01:11:43Z",
          releases: [],
        },
      },
    },
    [],
    releases,
  );
  assert.equal(byCommitTimeline.status, FeedbackStatus.FIXED_IN_VERSION);
  assert.equal(byCommitTimeline.fixedVersion, "1.0.16");

  const pendingCommit = mapSentryIssueToStatus(
    {
      status: "resolved",
      statusDetails: {
        inCommit: {
          id: "deadbeef",
          message: "fix(logs): upcoming fix",
          dateCreated: "2026-11-26T09:00:00Z",
          releases: [],
        },
      },
    },
    [],
    releases,
  );
  assert.equal(pendingCommit.status, FeedbackStatus.FIXED_PENDING_RELEASE);
});

test("validation helpers enforce installation_id, event_ids, shortCode and envelope DSN allowlist", () => {
  assert.equal(validateInstallationId("inst-uuid-1234"), "inst-uuid-1234");
  assert.equal(validateInstallationId("bad"), null);
  assert.deepEqual(
    validateEventIds("A1B2-C3D4-E5F6-4789-A0B1-C2D3E4F56789,not-hex,a1b2c3d4e5f64789a0b1c2d3e4f56789"),
    ["a1b2c3d4e5f64789a0b1c2d3e4f56789"],
  );
  assert.equal(validateShortCode("#a1b2c3d4"), "A1B2C3D4");
  assert.equal(validateShortCode("xyz"), null);

  assert.equal(
    isManualReportOwnedByInstallation({
      tags: [
        { key: "report_source", value: "manual_user_report" },
        { key: "installation_id", value: "inst-1" },
      ],
    }, "inst-1"),
    true,
  );
  assert.equal(
    isManualReportOwnedByInstallation({
      tags: [
        { key: "report_source", value: "manual_user_report" },
        { key: "installation_id", value: "inst-other" },
      ],
    }, "inst-1"),
    false,
  );

  const validEnvelope = JSON.stringify({
    event_id: "a1b2c3d4e5f64789a0b1c2d3e4f56789",
    dsn: "https://examplepublickey@o123456.ingest.example.invalid/42",
  }) + "\n{}";
  assert.equal(
    validateEnvelopeHeaderDsn(validEnvelope, "42", "o123456.ingest.example.invalid").ok,
    true,
  );
  assert.equal(
    validateEnvelopeHeaderDsn(validEnvelope, "999999", "o123456.ingest.example.invalid").ok,
    false,
  );
});

test("worker routes verify ownership, lookup fallback, and envelope proxy", async () => {
  const env = {
    SENTRY_AUTH_TOKEN: "test-token",
    SENTRY_API_BASE: "https://us.sentry.io/api/0",
    SENTRY_ORG: "example-org",
    SENTRY_PROJECT: "example-app",
    SENTRY_PROJECT_ID: "42",
    SENTRY_INGEST_HOST: "o123456.ingest.example.invalid",
  };

  const ownedEvent = "11111111222222223333333344444444";
  const foreignEvent = "99999999888888887777777766666666";

  const mockFetch = async (url, init) => {
    const u = String(url);
    if (u.startsWith("https://o123456.ingest.example.invalid/api/42/envelope/")) {
      assert.equal(init.method, "POST");
      return new Response(JSON.stringify({ id: ownedEvent }), { status: 200 });
    }
    if (u.endsWith(`/events/${ownedEvent}/`)) {
      return new Response(JSON.stringify({
        eventID: ownedEvent,
        groupID: "7868",
        tags: [
          { key: "report_source", value: "manual_user_report" },
          { key: "installation_id", value: "inst-abc-123" },
        ],
      }), { status: 200 });
    }
    if (u.endsWith(`/events/${foreignEvent}/`)) {
      return new Response(JSON.stringify({
        eventID: foreignEvent,
        groupID: "7868",
        tags: [
          { key: "report_source", value: "manual_user_report" },
          { key: "installation_id", value: "inst-someone-else" },
        ],
      }), { status: 200 });
    }
    if (u.endsWith("/issues/7868/")) {
      return new Response(JSON.stringify({
        id: "7868",
        status: "resolved",
        statusDetails: { inRelease: "io.github.lengsukq.opaldesk@1.0.16+10016" },
        lastSeen: "2026-11-26T05:00:00Z",
      }), { status: 200 });
    }
    if (u.endsWith("/issues/7868/comments/")) {
      return new Response(JSON.stringify([
        { dateCreated: "2026-11-26T05:10:00Z", text: "[reply] 已在 v1.0.16 修复" },
      ]), { status: 200 });
    }
    if (u.includes("/issues/?query=feedback_code%3A11111111")) {
      return new Response(JSON.stringify([]), { status: 200 });
    }
    if (u.includes("/events/?full=false&per_page=100")) {
      return new Response(JSON.stringify([
        {
          eventID: ownedEvent,
          groupID: "7868",
          tags: [{ key: "report_source", value: "manual_user_report" }],
        },
      ]), { status: 200 });
    }
    return new Response("not found", { status: 404 });
  };

  const worker = createWorkerHandler({ fetchImpl: mockFetch, bypassTtlCache: true });

  const healthResp = await worker.fetch(new Request("https://feedback.example.invalid/healthz"), env);
  assert.equal(healthResp.status, 200);
  const healthJson = await healthResp.json();
  assert.equal(healthJson.ok, true);
  assert.equal(healthJson.sentryConfigured, true);

  const statusResp = await worker.fetch(
    new Request(`https://feedback.example.invalid/v1/feedback/status?installation_id=inst-abc-123&event_ids=${ownedEvent},${foreignEvent}`),
    env,
  );
  assert.equal(statusResp.status, 200);
  const statusJson = await statusResp.json();
  assert.equal(statusJson.schema, 1);
  assert.ok(statusJson.items[ownedEvent]);
  assert.equal(statusJson.items[foreignEvent], undefined);
  assert.equal(statusJson.items[ownedEvent].status, FeedbackStatus.FIXED_IN_VERSION);
  assert.equal(statusJson.items[ownedEvent].fixedVersion, "1.0.16");
  assert.equal(statusJson.items[ownedEvent].replyNote, "已在 v1.0.16 修复");

  const lookupResp = await worker.fetch(
    new Request("https://feedback.example.invalid/v1/feedback/lookup?code=11111111"),
    env,
  );
  assert.equal(lookupResp.status, 200);
  const lookupJson = await lookupResp.json();
  assert.equal(lookupJson.found, true);
  assert.equal(lookupJson.item.shortCode, "11111111");
  assert.equal(lookupJson.item.fixedVersion, "1.0.16");

  const envelopeBody = JSON.stringify({
    event_id: ownedEvent,
    dsn: "https://examplepublickey@o123456.ingest.example.invalid/42",
  }) + "\n{\"type\":\"event\"}\n{}";
  const envResp = await worker.fetch(
    new Request("https://feedback.example.invalid/envelope", { method: "POST", body: envelopeBody }),
    env,
  );
  assert.equal(envResp.status, 200);
  assert.equal((await envResp.json()).id, ownedEvent);
});

test("feature requests map kind=feature, strip [Feature Request] prefix, and propagate in worker routes", async () => {
  const featureIssue = {
    id: "9001",
    status: "resolved",
    title: "[Feature Request] 支持 Dock 自动隐藏",
    statusDetails: { inNextRelease: true },
    tags: [{ key: "feedback_kind", value: "feature" }],
  };
  assert.equal(extractFeedbackKind(featureIssue), FeedbackKind.FEATURE);
  const mapped = mapSentryIssueToStatus(featureIssue, [{ dateCreated: "2026-11-26T17:00:00Z", text: "[reply] 已排期下版上线" }]);
  assert.equal(mapped.kind, FeedbackKind.FEATURE);
  assert.equal(mapped.status, FeedbackStatus.FIXED_PENDING_RELEASE);
  assert.equal(mapped.summary, "支持 Dock 自动隐藏");
  assert.equal(mapped.replyNote, "已排期下版上线");

  const anonFeature = mapSentryIssueToStatus({
    status: "unresolved",
    title: "[Feature Request] Feature suggestion from alice",
    statusDetails: {},
  });
  assert.equal(anonFeature.kind, FeedbackKind.FEATURE);
  assert.equal(anonFeature.summary, "功能建议");

  const env = {
    SENTRY_AUTH_TOKEN: "test-token",
    SENTRY_API_BASE: "https://us.sentry.io/api/0",
    SENTRY_ORG: "example-org",
    SENTRY_PROJECT: "example-app",
    SENTRY_PROJECT_ID: "42",
    SENTRY_INGEST_HOST: "o123456.ingest.example.invalid",
  };
  const featEvent = "22222222333333334444444455555555";
  const mockFetch = async (url) => {
    const u = String(url);
    if (u.endsWith(`/events/${featEvent}/`)) {
      return new Response(JSON.stringify({
        eventID: featEvent,
        groupID: "9001",
        tags: [
          { key: "report_source", value: "manual_user_report" },
          { key: "installation_id", value: "inst-feat-1" },
          { key: "feedback_kind", value: "feature" },
        ],
      }), { status: 200 });
    }
    if (u.endsWith("/issues/9001/")) {
      return new Response(JSON.stringify(featureIssue), { status: 200 });
    }
    if (u.endsWith("/issues/9001/comments/")) {
      return new Response(JSON.stringify([{ dateCreated: "2026-11-26T17:00:00Z", text: "[reply] 已排期下版上线" }]), { status: 200 });
    }
    if (u.includes("/issues/?query=feedback_code%3A22222222")) {
      return new Response(JSON.stringify([featureIssue]), { status: 200 });
    }
    return new Response("not found", { status: 404 });
  };
  const worker = createWorkerHandler({ fetchImpl: mockFetch, bypassTtlCache: true });

  const batchResp = await worker.fetch(
    new Request(`https://feedback.example.invalid/v1/feedback/status?installation_id=inst-feat-1&event_ids=${featEvent}`),
    env,
  );
  assert.equal(batchResp.status, 200);
  const batchJson = await batchResp.json();
  assert.equal(batchJson.items[featEvent].kind, FeedbackKind.FEATURE);
  assert.equal(batchJson.items[featEvent].summary, "支持 Dock 自动隐藏");

  const lookupResp = await worker.fetch(
    new Request("https://feedback.example.invalid/v1/feedback/lookup?code=22222222"),
    env,
  );
  assert.equal(lookupResp.status, 200);
  const lookupJson = await lookupResp.json();
  assert.equal(lookupJson.item.kind, FeedbackKind.FEATURE);
  assert.equal(lookupJson.item.status, FeedbackStatus.FIXED_PENDING_RELEASE);
});
