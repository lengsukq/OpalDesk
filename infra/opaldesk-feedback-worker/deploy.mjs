#!/usr/bin/env node
/**
 * Zero-dependency Cloudflare Workers REST API deployment script using curl.
 * Reads ALL domains, project IDs, and credentials strictly from environment variables
 * (or optional gitignored `.env` in this directory) so zero sensitive or personal
 * config is ever committed to Git.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CF_API = "https://api.cloudflare.com/client/v4";

function loadLocalDotEnv(filePath) {
  if (!fs.existsSync(filePath)) return;
  const content = fs.readFileSync(filePath, "utf8");
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let val = line.slice(eq + 1).trim();
    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (key && process.env[key] === undefined) {
      process.env[key] = val;
    }
  }
}

loadLocalDotEnv(path.join(__dirname, ".env"));

function requireEnv(name) {
  const val = (process.env[name] || "").trim();
  if (!val) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return val;
}

const cfToken = requireEnv("CF_API_TOKEN");
const accountId = requireEnv("CF_ACCOUNT_ID");
const zoneId = (process.env.CF_ZONE_ID || "").trim();
const customDomain = (process.env.CUSTOM_DOMAIN || "").trim();
const workerName = (process.env.WORKER_NAME || "opaldesk-feedback-worker").trim();
const sentryApiBase = (process.env.SENTRY_API_BASE || "https://us.sentry.io/api/0").trim();
const sentryOrg = requireEnv("SENTRY_ORG");
const sentryProject = requireEnv("SENTRY_PROJECT");
const sentryProjectId = requireEnv("SENTRY_PROJECT_ID");
const sentryIngestHost = requireEnv("SENTRY_INGEST_HOST");
const sentryAuthToken = (process.env.SENTRY_AUTH_TOKEN || "").trim();

function curlJson(args) {
  const raw = execFileSync("curl", ["-sS", ...args], {
    encoding: "utf8",
    maxBuffer: 4 * 1024 * 1024,
  });
  const data = JSON.parse(raw);
  if (data.success === false) {
    throw new Error(`Cloudflare API error: ${JSON.stringify(data.errors || data)}`);
  }
  return data.result;
}

function uploadWorkerScript() {
  const metadata = JSON.stringify({
    main_module: "index.mjs",
    compatibility_date: "2026-11-01",
    keep_bindings: ["secret_text"],
    bindings: [
      { type: "plain_text", name: "SENTRY_API_BASE", text: sentryApiBase },
      { type: "plain_text", name: "SENTRY_ORG", text: sentryOrg },
      { type: "plain_text", name: "SENTRY_PROJECT", text: sentryProject },
      { type: "plain_text", name: "SENTRY_PROJECT_ID", text: sentryProjectId },
      { type: "plain_text", name: "SENTRY_INGEST_HOST", text: sentryIngestHost },
    ],
  });

  const indexPath = path.join(__dirname, "src/index.mjs");
  const mapperPath = path.join(__dirname, "src/sentry_mapper.mjs");

  const result = curlJson([
    "-X", "PUT",
    "-H", `Authorization: Bearer ${cfToken}`,
    "-F", `metadata=${metadata};type=application/json`,
    "-F", `index.mjs=@${indexPath};type=application/javascript+module`,
    "-F", `sentry_mapper.mjs=@${mapperPath};type=application/javascript+module`,
    `${CF_API}/accounts/${accountId}/workers/scripts/${workerName}`,
  ]);
  console.log(`Uploaded worker '${workerName}' (etag=${result?.etag || "ok"})`);
}

function uploadSecret(name, text) {
  curlJson([
    "-X", "PUT",
    "-H", `Authorization: Bearer ${cfToken}`,
    "-H", "Content-Type: application/json",
    "-d", JSON.stringify({ name, text, type: "secret_text" }),
    `${CF_API}/accounts/${accountId}/workers/scripts/${workerName}/secrets`,
  ]);
  console.log(`Updated Worker secret '${name}'`);
}

function bindCustomDomain() {
  if (!zoneId || !customDomain) {
    console.warn("Skipped custom domain binding because CF_ZONE_ID or CUSTOM_DOMAIN is empty.");
    return;
  }
  const result = curlJson([
    "-X", "PUT",
    "-H", `Authorization: Bearer ${cfToken}`,
    "-H", "Content-Type: application/json",
    "-d", JSON.stringify({
      environment: "production",
      hostname: customDomain,
      service: workerName,
      zone_id: zoneId,
    }),
    `${CF_API}/accounts/${accountId}/workers/domains`,
  ]);
  console.log(`Bound custom domain '${result?.hostname || customDomain}' -> '${workerName}'`);
}

uploadWorkerScript();
if (sentryAuthToken) {
  uploadSecret("SENTRY_AUTH_TOKEN", sentryAuthToken);
} else {
  console.warn("Skipped SENTRY_AUTH_TOKEN secret upload because env SENTRY_AUTH_TOKEN is empty.");
}
bindCustomDomain();
