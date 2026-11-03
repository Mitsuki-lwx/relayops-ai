#!/usr/bin/env node
// Runs the fixed requirements-review evaluation set against one local API.
// One task per case, no automatic retries, no callback destination.
import { readFileSync, mkdirSync, existsSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["SUCCEEDED", "FAILED", "UNKNOWN", "CANCELED"]);
const INFRA_CODES = new Set(["MODEL_RESULT_UNKNOWN", "MODEL_REJECTED", "MODEL_CONFIG_CHANGED", "MODEL_REFUSED"]);
const VERSION_SCHEMA = "version.diff.result.v1";

function usage() {
  return `Usage: node scripts/run-version-diff-eval.mjs --config <env> --out <dir> --acknowledge-cost [options]\n\nOptions:\n  --base <url>                 Local API base (default http://127.0.0.1:18081)\n  --user <name>                Provisioned owner (default eval.owner)\n  --config <file>              Local env file containing RELAYOPS_DEMO_PASSWORD\n  --cases <file>               Dataset (default eval/version-diff/cases.json)\n  --out <dir>                  New directory for results and manifest\n  --acknowledge-cost           Confirm real supplier calls and possible charges\n  --timeout-seconds <n>        Per-task polling limit (default 180)\n  --max-infra-failures <n>     Stop after n UNKNOWN/REJECTED failures (default 2)\n  --delay-ms <n>               Delay between submissions (default 250)\n  --dry-run                    Validate dataset and print the plan without calling\n  --help\n`;
}

export function parseArgs(argv) {
  const args = { base: "http://127.0.0.1:18081", user: "eval.owner", config: "", cases: "eval/version-diff/cases.json", out: "", acknowledgeCost: false, timeoutSeconds: 180, maxInfraFailures: 2, delayMs: 250, only: [], dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    const next = () => {
      const value = argv[++i];
      if (value === undefined || value.startsWith("--")) throw new Error(`Missing value for ${token}`);
      return value;
    };
    if (token === "--base") args.base = next();
    else if (token === "--user") args.user = next();
    else if (token === "--config") args.config = next();
    else if (token === "--cases") args.cases = next();
    else if (token === "--out") args.out = next();
    else if (token === "--acknowledge-cost") args.acknowledgeCost = true;
    else if (token === "--timeout-seconds") args.timeoutSeconds = Number(next());
    else if (token === "--max-infra-failures") args.maxInfraFailures = Number(next());
    else if (token === "--delay-ms") args.delayMs = Number(next());
    else if (token === "--only") args.only = next().split(",").map(value => value.trim()).filter(Boolean);
    else if (token === "--dry-run") args.dryRun = true;
    else if (token === "--help" || token === "-h") { console.log(usage()); process.exit(0); }
    else throw new Error(`Unknown argument: ${token}`);
  }
  const base = new URL(args.base);
  if (base.protocol !== "http:" || base.hostname !== "127.0.0.1" || base.port === "18080" || base.port === "8080") throw new Error("Eval runner requires a dedicated local HTTP port other than 18080/8080");
  if (!Number.isInteger(args.timeoutSeconds) || args.timeoutSeconds < 10 || args.timeoutSeconds > 1800) throw new Error("Invalid --timeout-seconds");
  if (!Number.isInteger(args.maxInfraFailures) || args.maxInfraFailures < 1) throw new Error("Invalid --max-infra-failures");
  if (!Number.isInteger(args.delayMs) || args.delayMs < 0 || args.delayMs > 60000) throw new Error("Invalid --delay-ms");
  return args;
}

export function readDataset(path) {
  const data = JSON.parse(readFileSync(path, "utf8"));
  if (data?.schemaVersion !== "relayops.version-diff.eval.v1" || !Array.isArray(data.cases) || !data.cases.length) throw new Error("Invalid evaluation dataset");
  const ids = new Set();
  for (const item of data.cases) {
    if (!/^vd-[0-9]{3}-[a-z0-9-]+$/.test(item.id ?? "") || ids.has(item.id)) throw new Error(`Invalid or duplicate case id: ${item.id}`);
    for (const field of ["base", "revised"]) if (typeof item[field] !== "string" || !item[field].trim() || item[field].length > 6000) throw new Error(`Invalid ${field} for ${item.id}`);
    if (typeof item.title !== "string" || !item.title.trim()) throw new Error(`Invalid title for ${item.id}`);
    ids.add(item.id);
  }
  return data;
}

function envFile(path) {
  const result = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const match = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
    if (match) result[match[1]] = match[2];
  }
  return result;
}

function client(base) {
  let cookie = "";
  let csrf = null;
  const request = async (path, init = {}) => {
    const headers = new Headers(init.headers);
    if (cookie) headers.set("Cookie", cookie);
    if (init.csrf && csrf) headers.set(csrf.headerName, csrf.token);
    const response = await fetch(base + path, { ...init, headers, signal: AbortSignal.timeout(15000) });
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(";")[0];
      if (pair.startsWith("JSESSIONID=")) cookie = pair;
    }
    return response;
  };
  const json = async (path, init = {}) => {
    const response = await request(path, init);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${path}`);
    return response.json();
  };
  const login = async (username, password) => {
    csrf = await json("/auth/csrf");
    const response = await fetch(base + "/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", [csrf.headerName]: csrf.token, ...(cookie ? { Cookie: cookie } : {}) },
      body: new URLSearchParams({ username, password }),
      signal: AbortSignal.timeout(15000),
    });
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(";")[0];
      if (pair.startsWith("JSESSIONID=")) cookie = pair;
    }
    if (response.status !== 204) throw new Error(`Login failed with HTTP ${response.status}`);
    csrf = await json("/auth/csrf");
  };
  return { request, json, login };
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function poll(client, prefix, jobId, timeoutSeconds) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  let metadata;
  while (Date.now() < deadline) {
    metadata = await client.json(`${prefix}/jobs/${jobId}`);
    if (TERMINAL.has(metadata.status)) return metadata;
    await sleep(500);
  }
  return { ...metadata, status: "TIMEOUT", errorCode: "EVAL_POLL_TIMEOUT" };
}

async function main(argv) {
  const args = parseArgs(argv);
  let dataset = readDataset(resolve(args.cases));
  if (args.only.length) {
    const known = new Set(dataset.cases.map(item => item.id));
    const missing = args.only.filter(id => !known.has(id));
    if (missing.length) throw new Error(`Unknown --only ids: ${missing.join(", ")}`);
    dataset = { ...dataset, cases: dataset.cases.filter(item => args.only.includes(item.id)) };
  }
  if (!args.dryRun && !args.acknowledgeCost) throw new Error("Refusing real supplier calls without --acknowledge-cost");
  if (args.dryRun) {
    console.log(JSON.stringify({ mode: "dry-run", cases: dataset.cases.length, ids: dataset.cases.map(item => item.id) }));
    return 0;
  }
  if (!args.out) throw new Error("--out is required");
  const out = resolve(args.out);
  if (existsSync(out)) throw new Error(`Output directory already exists: ${out}`);
  const password = process.env.RELAYOPS_EVAL_PASSWORD || (args.config ? envFile(resolve(args.config)).RELAYOPS_DEMO_PASSWORD : "");
  if (!password) throw new Error("Set RELAYOPS_EVAL_PASSWORD or pass --config containing RELAYOPS_DEMO_PASSWORD");
  mkdirSync(join(out, "results"), { recursive: true });
  const base = args.base.replace(/\/$/, "");
  const api = client(base);
  await api.login(args.user, password);
  const me = await api.json("/api/v1/me");
  if (me.workspaces?.length !== 1) throw new Error("Evaluation owner must have exactly one workspace");
  const space = me.workspaces[0];
  const prefix = `/api/v1/workspaces/${space.id}`;
  const profile = await api.json(`${prefix}/model-profile?taskType=VERSION_DIFF`);
  if (!profile.billable || profile.provider !== "openai-compatible" || profile.schemaVersion !== VERSION_SCHEMA) throw new Error("Version-diff profile is not the expected billable compatible profile");
  const applications = await api.json(`${prefix}/applications`);
  const applicationId = applications[0]?.id;
  if (!applicationId) throw new Error("No application available");
  const startedAt = new Date().toISOString();
  const manifest = {
    dataset: { schemaVersion: dataset.schemaVersion, cases: dataset.cases.length, only: args.only },
    model: profile.modelName,
    profile: profile.profileId,
    promptVersion: profile.promptVersion,
    schemaVersion: profile.schemaVersion,
    billable: profile.billable,
    base,
    startedAt,
    cases: [],
  };
  let infraFailures = 0;
  console.log(JSON.stringify({ event: "start", model: profile.modelName, profile: profile.profileId, promptVersion: profile.promptVersion, cases: dataset.cases.length }));
  for (const testCase of dataset.cases) {
    const key = randomUUID();
    const body = {
      applicationId,
      title: testCase.title,
      inputText: testCase.base,
      inputTextB: testCase.revised,
      destinationIds: [],
      outputLanguage: "zh-CN",
      acknowledgeExternalProcessing: true,
      taskType: "VERSION_DIFF",
    };
    const entry = { id: testCase.id, title: testCase.title, key, startedAt: new Date().toISOString() };
    try {
      const accepted = await api.request(`${prefix}/jobs`, { method: "POST", csrf: true, headers: { "Content-Type": "application/json", "Idempotency-Key": key }, body: JSON.stringify(body) });
      if (accepted.status !== 202) throw new Error(`Submission HTTP ${accepted.status}`);
      const job = await accepted.json();
      entry.jobId = job.jobId;
      const metadata = await poll(api, prefix, job.jobId, args.timeoutSeconds);
      entry.status = metadata.status;
      entry.errorCode = metadata.errorCode ?? null;
      entry.modelCalls = metadata.modelCalls ?? null;
      entry.elapsedMs = Date.now() - new Date(entry.startedAt).getTime();
      if (metadata.taskType !== "VERSION_DIFF") { entry.integrityError = "TASK_TYPE_MISMATCH"; infraFailures++; }
      if (metadata.modelProfile !== profile.profileId) { entry.integrityError = "PROFILE_MISMATCH"; infraFailures++; }
      if (metadata.modelCalls !== 1) { entry.integrityError = "MODEL_CALLS_MISMATCH"; infraFailures++; }
      if (metadata.status === "SUCCEEDED") {
        const result = await api.json(`${prefix}/jobs/${job.jobId}/result`);
        writeFileSync(join(out, "results", `${testCase.id}.json`), JSON.stringify(result, null, 2));
        entry.result = "saved";
        if (metadata.modelCalls !== 1) entry.note = `unexpected modelCalls=${metadata.modelCalls}`;
      } else {
        entry.result = "missing";
      }
      if (metadata.status === "UNKNOWN" || INFRA_CODES.has(metadata.errorCode)) infraFailures++;
    } catch (error) {
      entry.status = "RUNNER_ERROR";
      entry.errorCode = "RUNNER_ERROR";
      entry.error = error instanceof Error ? error.message : String(error);
      infraFailures++;
    }
    manifest.cases.push(entry);
    writeFileSync(join(out, "manifest.json"), JSON.stringify({ ...manifest, endedAt: new Date().toISOString(), infraFailures }, null, 2));
    console.log(JSON.stringify({ event: "case", id: entry.id, status: entry.status, errorCode: entry.errorCode ?? null, elapsedMs: entry.elapsedMs ?? null, result: entry.result ?? null }));
    if (infraFailures >= args.maxInfraFailures) {
      console.log(JSON.stringify({ event: "stop", reason: "infrastructure failures", infraFailures }));
      break;
    }
    if (args.delayMs) await sleep(args.delayMs);
  }
  manifest.endedAt = new Date().toISOString();
  manifest.infraFailures = infraFailures;
  writeFileSync(join(out, "manifest.json"), JSON.stringify(manifest, null, 2));
  console.log(JSON.stringify({ event: "done", cases: manifest.cases.length, succeeded: manifest.cases.filter(item => item.status === "SUCCEEDED").length, infraFailures, out }));
  return manifest.cases.every(item => item.status === "SUCCEEDED") ? 0 : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; });
}
