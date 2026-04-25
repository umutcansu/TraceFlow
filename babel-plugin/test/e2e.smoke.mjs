// End-to-end smoke test: babel-plugin + runtime-js together.
//
// Steps:
//   1. Take a small JS source string with a few function shapes.
//   2. Transform it with the babel-plugin, but rewrite the
//      `runtimeImport` so it resolves to the local runtime-js dist
//      (because the npm-published version may not be available in
//      this monorepo's node_modules).
//   3. Initialise the runtime, point its endpoint at a recording
//      dummy server (we just monkey-patch fetch).
//   4. Eval the transformed code, call the wrapped functions.
//   5. Assert ENTER/EXIT/CATCH events were posted with the right
//      class/method names and arguments.

import { transformSync } from "@babel/core";
import plugin from "../dist/index.cjs";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as path from "node:path";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";

const fail = (msg) => { console.error(`[smoke] FAIL: ${msg}`); process.exit(1); };
const ok   = (msg) => { console.log(`[smoke] OK: ${msg}`); };

// 1. Source under test.
const src = `
function add(a, b) {
  return a + b;
}

const greet = (name) => "hi " + name;

class Repo {
  async load(id) {
    return { id, ok: true };
  }
}

function bad() {
  throw new Error("boom");
}
`;

// 2. Transform with the plugin. Point runtimeImport at the local
//    runtime-js dist so a workspace-style require works.
const runtimeAbs = path.resolve(
  fileURLToPath(import.meta.url),
  "../../../runtime-js/dist/index.cjs"
);

const out = transformSync(src, {
  filename: "/abs/demo.js",
  babelrc: false,
  configFile: false,
  plugins: [[plugin.default, { runtimeImport: runtimeAbs }]],
});

if (!out?.code) fail("transform produced no code");
ok("transform produced code");

// 3. Capture the events the runtime would have flushed by stubbing fetch.
const captured = [];
globalThis.fetch = async (url, init) => {
  // Body may be gzipped or plain JSON; for the smoke just decode if string.
  const body = init?.body;
  let parsed;
  if (typeof body === "string") {
    try { parsed = JSON.parse(body); } catch { parsed = body; }
  } else {
    parsed = "<binary>";
  }
  captured.push({ url, body: parsed });
  return { ok: true, status: 200, text: async () => "" };
};

// 4. Init runtime + load the transformed module.
const { initTraceFlow } = await import(pathToFileURL(runtimeAbs).href);

const client = initTraceFlow({
  endpoint: "http://example.com/traces",
  appId: "com.smoke.test",
  platform: "web-js",
  flushIntervalMs: 50,
  batchSize: 10,
  compress: false,
});

// Write the transformed code to a temp module so we can dynamically import.
const tmp = mkdtempSync(path.join(tmpdir(), "tf-smoke-"));
const modPath = path.join(tmp, "demo.mjs");
// Babel produced CJS-style requires for the runtimeImport when targeting the
// .cjs path; rewrite the require into a dynamic ESM import shim.
const moduleSource = `
import { _getActiveClient as __tf_getClient } from ${JSON.stringify(pathToFileURL(runtimeAbs).href)};
${out.code.replace(
  /import\s*\{[^}]+\}\s*from\s*["'][^"']+["'];?/,
  "" // drop the original import — we'll provide bindings via the prelude above
)}
export { add, greet, Repo, bad };
`;
writeFileSync(modPath, moduleSource);

const mod = await import(pathToFileURL(modPath).href);

// 5. Exercise the wrapped functions.
const sum = mod.add(2, 3);
if (sum !== 5) fail(`add(2,3) returned ${sum}`);
ok("add returns correct value");

const hi = mod.greet("world");
if (hi !== "hi world") fail(`greet("world") returned ${JSON.stringify(hi)}`);
ok("greet returns correct value");

const repo = new mod.Repo();
const loaded = await repo.load(7);
if (loaded.id !== 7 || loaded.ok !== true) fail(`Repo.load returned ${JSON.stringify(loaded)}`);
ok("async Repo.load resolves correctly");

let badThrew = false;
try { mod.bad(); } catch (e) { badThrew = e.message === "boom"; }
if (!badThrew) fail("bad() did not throw the expected error");
ok("bad() throws and the error is forwarded");

await client.flush();

// Wait for any in-flight flush to complete.
await new Promise(r => setTimeout(r, 100));
await client.shutdown();

// 6. Verify events.
if (captured.length === 0) fail("no batches were posted");

const allEvents = captured.flatMap(c => c.body);
const byMethod = {};
for (const e of allEvents) {
  byMethod[e.method] = (byMethod[e.method] || []).concat(e);
}

console.log(`[smoke] captured ${allEvents.length} events across ${captured.length} batches`);
console.log(`[smoke] methods: ${Object.keys(byMethod).join(", ")}`);

const expectMethods = ["add", "greet", "Repo.load", "bad"];
for (const m of expectMethods) {
  if (!byMethod[m]) fail(`no events for method "${m}"`);
}
ok("every wrapped function produced at least one event");

// Check ENTER/EXIT pairing on add
const addEnter = byMethod.add.find(e => e.type === "ENTER");
const addExit  = byMethod.add.find(e => e.type === "EXIT");
if (!addEnter) fail("add has no ENTER");
if (!addExit) fail("add has no EXIT");
if (addEnter.params?.a !== "2" && addEnter.params?.a !== 2) {
  fail(`add ENTER params.a unexpected: ${JSON.stringify(addEnter.params)}`);
}
ok("add ENTER carries params + EXIT followed");

// Check that bad() generated a CATCH (now attributed to bad itself via
// the client's caught() method) AND an EXIT.
const badCatch = byMethod.bad.find(e => e.type === "CATCH");
const badExit  = byMethod.bad.find(e => e.type === "EXIT");
if (!badCatch) fail("bad() did not produce a CATCH event under method=bad");
if (!badExit) fail("bad() did not produce an EXIT event");
if (!badCatch.message?.includes("boom")) fail(`CATCH message wrong: ${badCatch.message}`);
if (badCatch.class !== "demo") fail(`CATCH class should be "demo", got "${badCatch.class}"`);
ok("bad() produced CATCH attributed to bad() with message");

// Check async Repo.load round-tripped
const loadEnter = byMethod["Repo.load"].find(e => e.type === "ENTER");
const loadExit  = byMethod["Repo.load"].find(e => e.type === "EXIT");
if (!loadEnter || !loadExit) fail("Repo.load missing ENTER or EXIT");
ok("async Repo.load round-tripped ENTER + EXIT");

// Cleanup
rmSync(tmp, { recursive: true, force: true });

console.log("[smoke] ALL ASSERTIONS PASSED");
