/**
 * Module-aware import injection for the TraceFlow runtime.
 *
 * Earlier versions of this file used `t.importDeclaration` directly to
 * unshift a raw `import { ... } from "@umutcansu/traceflow-runtime"` at
 * the top of every transformed program. That works in pure ESM tooling
 * but breaks under Metro / React Native: Metro's module-transform pass
 * doesn't see imports added after the visitor that converts ESM->CJS
 * has already run, so the orphan `import` survives into the bundle and
 * Hermes rejects it with "import declaration must be at top level of
 * module".
 *
 * `@babel/helper-module-imports` is the canonical fix. `addNamed`:
 *
 *  - Picks the right syntax for the active module system (real ESM
 *    `import { x as y } from ...`, CJS interop `var y = require(...).x`,
 *    or AMD/UMD as configured by the consuming preset).
 *  - Deduplicates within a single source file, so calling it from
 *    every wrapped function is cheap and idempotent.
 *  - Returns a `t.Identifier` that we can clone into call sites — the
 *    name may end up uniquified (e.g. `_tf_getClient2` if `nameHint`
 *    collides with user code), so callers MUST thread the returned
 *    identifier through rather than hard-coding the alias.
 *
 * The return value is what the visitors / builders use to construct
 * the `__tf_c = <runtimeFn>()` declaration in each wrapped body.
 */

import type { NodePath } from "@babel/core";
import * as t from "@babel/types";
import { addNamed } from "@babel/helper-module-imports";
import { ResolvedOptions } from "../options";

/** Preferred local name. Babel will uniquify if it would collide. */
const NAME_HINT = "_tf_getClient";

/**
 * Idempotently register an `import { _getActiveClient as <hinted> }`
 * for the current program (or the equivalent in CJS / other module
 * systems). Returns the local Identifier reference that wrapper code
 * should clone into call expressions.
 *
 * Caches the resolved Identifier on the Program node so repeated calls
 * within the same file return the same instance without going through
 * the helper's own dedup machinery a second time.
 */
export function ensureRuntimeClientImport(
  programPath: NodePath<t.Program>,
  opts: ResolvedOptions,
): t.Identifier {
  const program = programPath.node as t.Program & {
    __tfClientId?: t.Identifier;
  };
  if (program.__tfClientId) return program.__tfClientId;

  const id = addNamed(programPath, "_getActiveClient", opts.runtimeImport, {
    nameHint: NAME_HINT,
  });

  program.__tfClientId = id;
  return id;
}
