/**
 * Stage 2 visitor: wraps top-level `FunctionDeclaration`s with the
 * ENTER/EXIT/CATCH prelude built by `helpers/builders`.
 *
 * Scope (intentional — broader cases land in later stages):
 *  - Stage 2:  named, synchronous, non-generator function declarations.
 *  - Stage 3:  function expressions / arrow functions assigned to bindings.
 *  - Stage 4:  class methods / object methods.
 *  - Stage 5:  async + generator variants.
 *
 * Each visit performs:
 *  1. file-level skip check (cached on `state.tfSkip`)
 *  2. per-node `__tfWrapped` flag check (idempotency under multi-pass usage)
 *  3. `@notrace` opt-out scan on leading comments
 *  4. generator early-return (still deferred; async is wrapped as of Stage 5)
 *  5. lazy import injection (so files with no wrappable functions stay clean)
 *  6. body replacement via `buildWrappedBody`
 */
import type { NodePath, Visitor } from "@babel/core";
import * as t from "@babel/types";
import { TFState } from "../helpers/state";
import { hasNoTraceComment } from "../helpers/skip";
import { ensureRuntimeImports } from "../helpers/imports";
import { methodNameFor, collectParamNames } from "../helpers/naming";
import { buildWrappedBody } from "../helpers/builders";

/**
 * Marker stored on each wrapped node so that re-running the plugin (e.g. via
 * a second Babel pass in tests, or in dev tooling that double-transforms)
 * does not produce nested wrappers.
 */
const WRAPPED_FLAG = "__tfWrapped";

export const functionDeclarationVisitor: Visitor<TFState> = {
  FunctionDeclaration: {
    enter(path: NodePath<t.FunctionDeclaration>, state: TFState) {
      if (state.tfSkip) return;

      const node = path.node as t.FunctionDeclaration & {
        [WRAPPED_FLAG]?: boolean;
      };
      if (node[WRAPPED_FLAG]) return;
      if (hasNoTraceComment(node)) return;

      // Stage 5 scope: async functions are now wrapped. Generators remain
      // deferred — wrapping a generator with the current try/finally shape
      // would break the lazy `yield` semantics.
      //
      // Async functions need NO body-shape change: JS try/finally has the
      // same observable semantics for async — the awaited promise's result
      // (or rejection) is settled before the generated `return`/`throw`
      // executes, so `finally` still runs after the function's logical end
      // and `catch` still sees rejected awaits as thrown errors. The wall
      // clock measured by `Date.now() - __tf_t0` correctly includes await
      // time. See `helpers/builders.ts` — Stage 5 added zero lines there.
      if (node.generator) return;
      if (!node.id) return; // unreachable for declarations, but type-narrowed.

      // Lazy import injection — only fires when the file actually has at
      // least one wrappable function. Idempotent for subsequent calls.
      const programPath = path.scope.getProgramParent()
        .path as NodePath<t.Program>;
      ensureRuntimeImports(programPath, state.tfOpts);

      const newBody = buildWrappedBody(node.body, {
        className: state.tfClassName,
        methodName: methodNameFor(path),
        paramNames: state.tfOpts.traceArguments
          ? collectParamNames(node.params)
          : [],
        traceArguments: state.tfOpts.traceArguments,
      });

      node.body = newBody;
      node[WRAPPED_FLAG] = true;
    },
  },
};
