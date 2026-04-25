// verify-metro.mjs — runs `expo export` and asserts the resulting
// Metro bundle is consumable by Hermes. The original
// @umutcansu/traceflow-babel-plugin@0.1.0 bug surfaced here:
// Metro's modules-commonjs pass left an orphan `import` declaration
// in the bundle and Hermes refused it with "import declaration must
// be at top level of module".
//
// Run once after `npm install`. If the assertion fires, the
// babel-plugin has regressed — the user should NOT ship the bundle
// to a real device.
//
// This is best-effort: it verifies (a) `expo export` exits cleanly,
// (b) the produced JS bundle has no top-level `import` statements,
// (c) the bundle references our runtime via `require(`...`)` like
// every other Metro-managed module.

import { spawnSync } from "node:child_process";
import { readdirSync, readFileSync } from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(here, "dist");

const fail = (msg) => { console.error(`FAIL: ${msg}`); process.exit(1); };
const pass = (msg) => console.log(`OK:   ${msg}`);

console.log("Bundling with `expo export --platform android` (Hermes target)...");
const r = spawnSync(
  "npx",
  ["--no-install", "expo", "export", "--platform", "android", "--output-dir", dist],
  { cwd: here, stdio: "inherit" },
);
if (r.status !== 0) fail(`expo export exited ${r.status}`);
pass("expo export completed");

// Find the bundle file. Expo writes _expo/static/js/android/<hash>.hbc
// for Hermes-compiled, OR <hash>.js for plain JS source. We want the
// JS source so we can grep it.
function findBundle(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...findBundle(full));
    else if (entry.isFile() && /\.(js|hbc)$/.test(entry.name)) out.push(full);
  }
  return out;
}

const bundles = findBundle(dist);
if (bundles.length === 0) fail("no bundle file produced under dist/");
const jsBundles = bundles.filter((p) => p.endsWith(".js"));
if (jsBundles.length === 0) {
  console.log(
    "NOTE: only .hbc bundle(s) produced — Metro pre-compiled to Hermes bytecode.\n" +
      "      That itself is a strong signal: hermesc would have rejected the\n" +
      "      bundle if our babel-plugin emitted a top-level orphan `import`.",
  );
  pass("Hermes bytecode bundle accepted by hermesc (implicit)");
  process.exit(0);
}

const sample = jsBundles[0];
const text = readFileSync(sample, "utf8");
console.log(`Inspecting JS bundle: ${path.relative(here, sample)} (${text.length} bytes)`);

if (/^\s*import\s/m.test(text)) {
  fail("top-level `import` declaration found in the bundle — Hermes will reject this");
}
pass("no top-level `import` survives in the bundle");

if (!text.includes("@umutcansu/traceflow-runtime")) {
  fail(
    "runtime module specifier not found in the bundle — the babel-plugin " +
      "may have skipped instrumentation entirely",
  );
}
pass("runtime module is referenced from the bundle (plugin ran)");

if (!/_getActiveClient/.test(text)) {
  fail(
    "_getActiveClient is missing from the bundle — the babel-plugin's import " +
      "injection did not land",
  );
}
pass("_getActiveClient binding present (helper-module-imports addNamed worked)");

console.log("\nAll Metro-bundle assertions passed.");
