// Smoke test: exercises the public API against a locally-running
// TraceFlow sample-server. Assumes the server is reachable at
// http://localhost:4567 (override via TF_ENDPOINT).
//
// Usage:
//   1. cd sample-server && ./gradlew run
//   2. cd runtime-js && npm run build && npm run smoke
//
// If TRACEFLOW_INGEST_TOKEN is set on the server, also set
// TF_TOKEN to the same value here.

import { initTraceFlow, captureException, trace } from "../../dist/index.js";

const endpoint = process.env.TF_ENDPOINT ?? "http://localhost:4567/traces";
const token = process.env.TF_TOKEN;

const client = initTraceFlow({
  endpoint,
  appId: "com.example.smoke",
  platform: "web-js",
  appVersion: "0.0.1",
  buildNumber: "1",
  userId: "smoke-user",
  token,
  flushIntervalMs: 500,
  batchSize: 10,
});

console.log(`[smoke] Posting to ${endpoint}`);

// One manual capture.
try { throw new Error("smoke test boom"); } catch (e) { captureException(e); }

// One traced block.
trace("smoke.addition", () => 2 + 2);

// Force a flush and shut down to exit cleanly.
await client.flush();
await client.shutdown();

// Read back via GET /traces filtered by our appId.
const u = new URL(endpoint);
u.pathname = "/traces";
u.searchParams.set("since", "0");
u.searchParams.set("appId", "com.example.smoke");
u.searchParams.set("limit", "10");

const res = await fetch(u, {
  headers: token ? { Authorization: `Bearer ${token}` } : {},
});
if (!res.ok) {
  console.error(`[smoke] readback failed: HTTP ${res.status}`);
  process.exit(1);
}
const page = await res.json();
console.log(`[smoke] Server returned ${page.events.length} events`);
for (const e of page.events) {
  const stack = Array.isArray(e.stack) ? `stack_len=${e.stack.length}` : "";
  console.log(`  - ${e.type} ${e.class}.${e.method} platform=${e.platform} ${stack}`);
}

if (page.events.length === 0) {
  console.error("[smoke] FAIL — no events round-tripped");
  process.exit(1);
}
console.log("[smoke] OK");
