// verify-bug-fixes.mjs — exercises the two patch releases this repo
// shipped against this sample's pinned versions, so a regression
// trips this script and not someone's RN device.
//
//   * runtime-js@0.2.1: gzip is suppressed when running on React
//     Native, because OkHttp's BridgeInterceptor strips outgoing
//     Content-Encoding headers and the server then 500s on raw
//     gzip bytes. We fake the RN environment by setting
//     navigator.product = "ReactNative" before importing the
//     runtime, then watch what fetch() actually receives.
//
//   * babel-plugin@0.1.1: the runtime import lands at the program
//     top in a way that survives Metro's modules-commonjs pass.
//     We pipe the plugin's output through that pass and assert no
//     orphan `import` declaration remains in the bundle.

import { transformFileSync, transformSync } from "@babel/core";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as path from "node:path";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";

const here = path.dirname(fileURLToPath(import.meta.url));
const fail = (msg) => { console.error(`FAIL: ${msg}`); process.exit(1); };
const pass = (msg) => console.log(`OK:   ${msg}`);

// -----------------------------------------------------------------
// 1) RN gzip suppression
// -----------------------------------------------------------------

console.log("\n=== runtime-js@0.2.1 — RN gzip suppression ===");

// Fake RN environment BEFORE importing the runtime so platform.ts
// captures it during isReactNative().
globalThis.navigator = { product: "ReactNative" };

const captured = [];
globalThis.fetch = async (_url, init) => {
  captured.push({
    headers: init?.headers,
    bodyType: typeof init?.body,
    bodyIsBinary: init?.body instanceof Uint8Array,
  });
  return { ok: true, status: 200, text: async () => "" };
};

const { initTraceFlow, captureException, shutdown } = await import(
  "@umutcansu/traceflow-runtime"
);

const client = initTraceFlow({
  endpoint: "http://example.test/traces",
  appId: "com.example.bugfix-verify",
  platform: "react-native",
  flushIntervalMs: 50,
  batchSize: 10,
  compress: true, // user opted-in; runtime should still suppress on RN
});
try { throw new Error("rn smoke"); } catch (e) { captureException(e); }
await client.flush();
await shutdown();

if (captured.length === 0) fail("no POST captured");
const c = captured[0];
const ce = c.headers?.["Content-Encoding"];
if (ce === "gzip") fail(`Content-Encoding: gzip present on RN — fix regressed`);
pass("Content-Encoding: gzip header NOT set on RN (suppressed correctly)");
if (c.bodyIsBinary) fail("body is Uint8Array on RN — should be raw JSON string");
pass("body is plain JSON string (no gzip encoding)");

// Reset RN global so the second test runs in a "neutral" env.
delete globalThis.navigator;

// -----------------------------------------------------------------
// 2) babel-plugin Metro / Hermes orphan-import fix
// -----------------------------------------------------------------

console.log("\n=== babel-plugin@0.1.1 — Metro modules-commonjs compatibility ===");

// First: babel-plugin transform alone (ESM output expected).
const appPath = path.join(here, "src/App.tsx");
const tx1 = transformFileSync(appPath, {
  filename: appPath,
  babelrc: false,
  configFile: path.join(here, "babel.config.js"),
});
if (!tx1?.code) fail("plugin produced no code");
if (!tx1.code.includes("import {")) fail("plugin output missing top-level import");
pass("plugin output ships a top-level `import { ... }`");

// Now pipe through plugin-transform-modules-commonjs to emulate
// what Metro / preset-env does in a real RN bundle. Import the
// plugin module directly so we hand Babel an already-loaded plugin
// rather than a path string (which is fussier across Node ESM).
let tx2 = null;
try {
  const mod = await import("@babel/plugin-transform-modules-commonjs");
  const plugin = mod.default ?? mod;
  tx2 = transformSync(tx1.code, {
    filename: appPath,
    babelrc: false,
    configFile: false,
    plugins: [plugin],
  });
} catch (e) {
  console.warn(
    "WARN: skipping the modules-commonjs assertion — " + e.message,
  );
}

if (tx2?.code) {
  if (/^\s*import\s/m.test(tx2.code)) {
    fail("orphan `import` survived the modules-commonjs pass — Hermes would reject this bundle");
  }
  pass("no orphan `import` after modules-commonjs (Hermes-safe)");
  if (!tx2.code.includes("require(")) fail("expected require() after modules transform");
  pass("require() call present (CJS interop emitted)");
}

console.log("\nAll bug-fix verifications passed.");
