// run-live.mjs — variant of simulate.mjs that posts to a REAL
// running TraceFlow sample-server instead of stubbing fetch.
// Use this when you want to see events arrive at the server end-to-
// end on the same machine.
//
// Prereqs:
//   1. Start the server:
//        cd ../../sample-server && ./gradlew run
//      (default port 4567)
//   2. From this directory:
//        node run-live.mjs
//
// What it proves
//   - The babel-plugin's emitted code actually compiles and runs
//   - The runtime POSTs over real HTTP (not a stub)
//   - The server accepts the event shape (gzip-or-not handling
//     correct for the platform, etc.)
//   - The events round-trip back through GET /traces

import { transformFileSync } from "@babel/core";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as path from "node:path";
import { writeFileSync, mkdtempSync, rmSync } from "node:fs";

const here = path.dirname(fileURLToPath(import.meta.url));
const ENDPOINT = process.env.TF_ENDPOINT ?? "http://localhost:4567/traces";

console.log(`[live] target endpoint: ${ENDPOINT}`);

// 1. Transform App.tsx with the plugin.
const appPath = path.join(here, "src/App.tsx");
const transformed = transformFileSync(appPath, {
  filename: appPath,
  babelrc: false,
  configFile: path.join(here, "babel.config.js"),
});
if (!transformed?.code) {
  throw new Error("Babel produced no output");
}

// 2. Drop transformed code in a temp module so we can dynamically import.
const tmp = mkdtempSync(path.join(here, ".tf-tmp-"));
const modPath = path.join(tmp, "App.mjs");
// Override the endpoint in the source so we hit our local server.
const code = transformed.code.replace(
  /endpoint:\s*"[^"]*"/,
  `endpoint: ${JSON.stringify(ENDPOINT)}`,
);
writeFileSync(modPath, code);

// 3. Import + exercise.
const mod = await import(pathToFileURL(modPath).href);

console.log("[live] calling instrumented functions...");
const a = mod.add(2, 3);
const b = mod.multiply(4, 5);
const u = await mod.fetchUser(42);
const cart = new mod.Cart();
cart.add("apple");
cart.add("bread");
const r = await cart.checkout();
try { mod.dangerouslyParse("not-an-object"); } catch {}
mod.reportSomethingBad();
console.log(`[live]   add=${a}  multiply=${b}  user=${JSON.stringify(u)}  receipt=${JSON.stringify(r)}`);

// 4. Force flush + shutdown so all events leave the buffer.
const { shutdown } = await import("@umutcansu/traceflow-runtime");
await shutdown();

// Brief wait for the last batch to land before we query the server.
await new Promise((r) => setTimeout(r, 300));

// 5. Query server: how many events for our appId?
const u2 = new URL(ENDPOINT);
u2.pathname = "/traces";
u2.searchParams.set("since", "0");
u2.searchParams.set("appId", "com.example.rn-sample");
u2.searchParams.set("limit", "100");
const res = await fetch(u2);
if (!res.ok) {
  console.error(`[live] GET /traces failed: HTTP ${res.status}`);
  process.exit(1);
}
const page = await res.json();
console.log(`\n[live] server returned ${page.events.length} events for our appId:\n`);

const byMethod = {};
for (const e of page.events) {
  const k = `${e.class}.${e.method}`;
  byMethod[k] = (byMethod[k] || []).concat(e);
}
for (const [m, evs] of Object.entries(byMethod).sort()) {
  console.log(`  ${m.padEnd(32)}  ${evs.length} event${evs.length > 1 ? "s" : ""}  (${evs.map((e) => e.type).join(", ")})`);
}

// Cleanup
rmSync(tmp, { recursive: true, force: true });

console.log("\n[live] done.");
