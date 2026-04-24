/**
 * Lazy injection of the TraceFlow runtime imports.
 *
 * Stage 2+ visitors will call `ensureRuntimeImports` the first time they wrap
 * a function in a given file. This module centralizes both:
 *  - the canonical alias names used inside emitted code
 *  - the idempotency flag that prevents the import from being inserted twice
 *
 * Stage 1 builds the helper but never invokes it — emitting an import would
 * change the output of unmodified files, breaking the "byte-identical" test.
 */

import type { NodePath } from "@babel/core";
import * as t from "@babel/types";
import { ResolvedOptions } from "../options";

/** Local alias under which the runtime's `_getActiveClient` is bound. */
const ALIAS_GET_CLIENT = "__tf_getClient";
/** Local alias under which the runtime's `captureException` is bound. */
const ALIAS_CAPTURE = "__tf_capture";

/**
 * Internal symbol name (via cast) used to mark a Program node as already
 * having had imports injected. Kept as a string constant so the same key is
 * read and written by both stage 1 (this file) and future stages.
 */
const IMPORTED_FLAG = "__tfImported";

/**
 * Insert the TraceFlow runtime imports at the top of the program, exactly
 * once per file.
 *
 * Equivalent source:
 *   import {
 *     _getActiveClient as __tf_getClient,
 *     captureException as __tf_capture,
 *   } from "<opts.runtimeImport>";
 *
 * Idempotency: the Program node is tagged with a `__tfImported` boolean.
 * Subsequent calls observe the flag and short-circuit, so it's safe (and
 * expected) for every wrapped function to call this helper.
 */
export function ensureRuntimeImports(
  programPath: NodePath<t.Program>,
  opts: ResolvedOptions,
): void {
  const program = programPath.node as t.Program & { [IMPORTED_FLAG]?: boolean };
  if (program[IMPORTED_FLAG]) return;

  const importDecl = t.importDeclaration(
    [
      // imported name (right) -> local alias (left): `imported as local`
      t.importSpecifier(
        t.identifier(ALIAS_GET_CLIENT),
        t.identifier("_getActiveClient"),
      ),
      t.importSpecifier(
        t.identifier(ALIAS_CAPTURE),
        t.identifier("captureException"),
      ),
    ],
    t.stringLiteral(opts.runtimeImport),
  );

  // Insert at the very top of the file so the bindings are available
  // anywhere they might be referenced by injected wrappers.
  program.body.unshift(importDecl);
  program[IMPORTED_FLAG] = true;
}
