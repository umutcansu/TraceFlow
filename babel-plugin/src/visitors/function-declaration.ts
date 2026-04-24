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
 *  4. async/generator early-return (deferred to Stage 5)
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

      // Stage 2 scope: synchronous, non-generator, named declarations only.
      // The async/generator branches are handled by Stage 5; do not remove.
      if (node.async || node.generator) return;
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
