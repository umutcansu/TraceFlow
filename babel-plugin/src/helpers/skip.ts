/**
 * File-level and node-level skip predicates.
 *
 * These guard the plugin against:
 *  - instrumenting third-party code in `node_modules`
 *  - instrumenting the runtime package itself (which would create an import
 *    cycle, since the runtime is what we'd import *from*)
 *  - **virtual / synthetic bundler files** (Metro polyfills like
 *    `\0polyfill:external-require`, Rollup virtual modules with the same
 *    `\0` prefix). These run *outside* the module system — Hermes will
 *    crash at boot with "Property 'require' doesn't exist" if we inject
 *    a `require(...)` call into them.
 *  - user-supplied excludePatterns
 *  - explicit `@notrace` opt-outs on individual nodes
 */

import * as t from "@babel/types";
import { ResolvedOptions } from "../options";

/**
 * Decide whether the given file should be skipped entirely.
 *
 * Returns `true` (skip) when:
 *  - filename is undefined — Babel sometimes runs without a filename (e.g. in
 *    REPL/eval contexts); we have no safe way to instrument those.
 *  - filename lives under `node_modules` — third-party code is out of scope.
 *  - filename appears to belong to the TraceFlow runtime itself — protects
 *    against an instrumented runtime importing itself.
 *  - filename matches any user-supplied `excludePatterns` regex.
 */
export function shouldSkipFile(
  filename: string | undefined,
  opts: ResolvedOptions,
): boolean {
  if (!filename) return true;

  // Virtual / synthetic files used by bundlers. Metro joins its
  // virtual-module marker (a NUL byte `\0`) onto the project root and
  // hands Babel a string like
  //   /abs/path/to/project/\0polyfill:external-require
  // Rollup uses the same `\0` convention, just without the absolute
  // prefix. NUL is illegal in real filesystem paths, so its presence
  // anywhere in `filename` is a reliable virtual-file signal.
  //
  // These polyfills bootstrap the module system itself: they run as a
  // pre-amble before any module wrapper is in place. Injecting a
  // `require("@umutcansu/traceflow-runtime")` into them yields a hard
  // boot crash on Hermes ("Property 'require' doesn't exist") because
  // the runtime hasn't installed module-system globals yet.
  if (filename.includes("\0")) return true;

  // node_modules: forward-slashes only is sufficient because Babel normalizes
  // paths to POSIX separators in `file.opts.filename` even on Windows.
  if (filename.includes("/node_modules/")) return true;

  // Runtime self-detection — heuristic.
  //
  // We can't truly resolve `opts.runtimeImport` to an absolute path here
  // without taking on a resolver dependency, so we approximate:
  //   1. `runtime-js` is the sibling source-package directory used in this
  //      monorepo. Anything under it is part of the runtime.
  //   2. If the resolved file path contains the literal `runtimeImport`
  //      string (e.g. `.../@umutcansu/traceflow-runtime/dist/...`), assume
  //      it's the published runtime resolved via node_modules.
  //
  // Both checks are cheap substring tests; false positives only mean the user
  // doesn't get tracing on something that happens to live in a path with one
  // of those substrings, which is acceptable.
  if (filename.includes("/runtime-js/")) return true;
  if (opts.runtimeImport && filename.includes(opts.runtimeImport)) return true;

  // User-supplied excludes.
  for (const pattern of opts.excludePatterns) {
    if (pattern.test(filename)) return true;
  }

  return false;
}

/**
 * Returns `true` when the node carries a leading comment containing the
 * `@notrace` opt-out marker (case-insensitive).
 *
 * Stage 2+ will call this on each candidate function; Stage 1 ships it now
 * so the helper module has a stable shape.
 */
export function hasNoTraceComment(node: t.Node): boolean {
  const comments = node.leadingComments;
  if (!comments || comments.length === 0) return false;
  for (const c of comments) {
    if (c.value.toLowerCase().includes("@notrace")) return true;
  }
  return false;
}
