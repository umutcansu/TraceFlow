// simulate.mjs — runs the example end-to-end without requiring an
// iOS or Android toolchain. Steps:
//
//   1. Transform src/App.tsx with babel + the TraceFlow plugin.
//   2. Stub fetch so trace events get printed instead of POSTed.
//   3. Load the transformed module dynamically.
//   4. Exercise each function: sync, async, class method, and a
//      deliberate throw.
//   5. Flush + print the captured events.
//
// This mirrors what would happen in a real RN runtime: the plugin
// runs at build time, the runtime initialises in App.tsx, and every
// function call produces ENTER/EXIT/CATCH events. The only thing
// faked here is the network — real apps post to a TraceFlow server.

import { transformFileSync } from "@babel/core";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as path from "node:path";
import { writeFileSync, mkdtempSync, rmSync } from "node:fs";

const here = path.dirname(fileURLToPath(import.meta.url));
const appPath = path.join(here, "src/App.tsx");

console.log("[rn-sample] transforming src/App.tsx with the babel-plugin...");

const transformed = transformFileSync(appPath, {
  filename: appPath,
  babelrc: false,
  configFile: path.join(here, "babel.config.js"),
});
if (!transformed?.code) {
  throw new Error("Babel produced no output — check babel.config.js");
}

// 2. Stub fetch so we can see what would be POSTed.
const captured = [];
globalThis.fetch = async (url, init) => {
  const body = init?.body;
  let parsed = body;
  if (typeof body === "string") {
    try {
      parsed = JSON.parse(body);
    } catch {
      /* leave as-is */
    }
  } else if (body && typeof body !== "string") {
    parsed = "<binary>";
  }
  captured.push({ url, batch: parsed });
  return { ok: true, status: 200, text: async () => "" };
};

// 3. Drop the transformed source next to package.json so Node's
//    module resolution can find @umutcansu/traceflow-runtime via the
//    sample's own node_modules. Cleaned up at the end.
const tmp = mkdtempSync(path.join(here, ".tf-tmp-"));
const modPath = path.join(tmp, "App.mjs");
writeFileSync(modPath, transformed.code);

const mod = await import(pathToFileURL(modPath).href);

// 4. Exercise the wrapped functions.
console.log("\n[rn-sample] calling instrumented functions...");

const sum = mod.add(2, 3);
console.log(`  add(2,3) -> ${sum}`);

const product = mod.multiply(4, 5);
console.log(`  multiply(4,5) -> ${product}`);

const user = await mod.fetchUser(42);
console.log(`  await fetchUser(42) -> ${JSON.stringify(user)}`);

const cart = new mod.Cart();
cart.add("apple");
cart.add("bread");
const receipt = await cart.checkout();
console.log(`  cart.checkout() -> ${JSON.stringify(receipt)}`);

try {
  mod.dangerouslyParse("not-an-object");
} catch (e) {
  console.log(`  dangerouslyParse threw "${e.message}" (caught + reported)`);
}

mod.reportSomethingBad();

// 5. Force a final flush and dump events.
console.log("\n[rn-sample] flushing buffered events...");
const { shutdown } = await import("@umutcansu/traceflow-runtime");
await shutdown();

const allEvents = captured.flatMap((c) => c.batch);
console.log(`\n[rn-sample] captured ${allEvents.length} events:\n`);
for (const e of allEvents) {
  const tag = `${e.type.padEnd(6)}`;
  const where = `${e.class || ""}.${e.method || ""}`.padEnd(28);
  const extra =
    e.type === "CATCH"
      ? ` exception=${e.exception} msg="${(e.message ?? "").slice(0, 60)}"`
      : e.type === "EXIT"
        ? ` durationMs=${e.durationMs ?? "?"}`
        : e.params
          ? ` params=${JSON.stringify(e.params)}`
          : "";
  console.log(`  ${tag} ${where}${extra}`);
}

// Cleanup
rmSync(tmp, { recursive: true, force: true });

console.log("\n[rn-sample] done.");
console.log("In a real RN project you would point `endpoint` at a");
console.log("running TraceFlow server (sample-server, or your own");
console.log("deployment), launch the app, and watch the same events");
console.log("appear in the IntelliJ / Android Studio plugin live.");
