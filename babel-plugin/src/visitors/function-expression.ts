/**
 * Stage 3 visitor: wraps `FunctionExpression` and `ArrowFunctionExpression`
 * with the same ENTER/EXIT/CATCH prelude used by Stage 2's declaration
 * visitor.
 *
 * Differences from Stage 2:
 *
 *  1. Names come from the surrounding context (`VariableDeclarator`,
 *     `AssignmentExpression`, `ObjectProperty`) when the function itself has
 *     no `id`. See `methodNameFor` for the resolution order.
 *  2. Concise arrow bodies (`(a, b) => a + b`) are converted to a block
 *     (`{ return a + b; }`) before wrapping. Arrows that already have a block
 *     body are left structurally alone.
 *  3. Anonymous functions (no derivable name) are NOT wrapped by default.
 *     `instrumentAnonymous: true` enables a synthetic `<anonymous>:<line>`
 *     method name and wraps them.
 *  4. `this` is preserved naturally because the wrapper is plain try/finally
 *     in-place — no IIFE.
 *
 * Out of scope (handled by later stages, do not touch here):
 *  - `ClassMethod`, `ObjectMethod`            → Stage 4
 *  - async / generator function expressions  → Stage 5
 */
import type { NodePath, Visitor } from "@babel/core";
import * as t from "@babel/types";
import { TFState } from "../helpers/state";
import { hasNoTraceComment } from "../helpers/skip";
import { ensureRuntimeImports } from "../helpers/imports";
import {
  methodNameFor,
  collectParamNames,
  isWrappableNamed,
} from "../helpers/naming";
import { buildWrappedBody } from "../helpers/builders";

/** Same flag Stage 2 uses — keeps multi-pass idempotency uniform. */
const WRAPPED_FLAG = "__tfWrapped";

function handle(
  path: NodePath<t.FunctionExpression | t.ArrowFunctionExpression>,
  state: TFState,
): void {
  if (state.tfSkip) return;

  const node = path.node as (
    | t.FunctionExpression
    | t.ArrowFunctionExpression
  ) & {
    [WRAPPED_FLAG]?: boolean;
  };
  if (node[WRAPPED_FLAG]) return;
  if (hasNoTraceComment(node)) return;

  // Stage 5: async function/arrow expressions are now wrapped. Generators
  // remain deferred. The body shape produced by `buildWrappedBody` is
  // unchanged — try/finally semantics are identical for async, so the
  // existing prelude/postlude correctly observes enter before the first
  // await and exit after the returned promise settles.
  if (node.generator) return;

  const name = methodNameFor(path);
  if (!isWrappableNamed(name) && !state.tfOpts.instrumentAnonymous) return;

  // Concise arrow body (`(x) => x * 2`) — wrap the expression in a block so
  // the prelude/postlude has somewhere to live. Only touch arrows whose body
  // is not already a BlockStatement.
  if (
    t.isArrowFunctionExpression(node) &&
    !t.isBlockStatement(node.body)
  ) {
    node.body = t.blockStatement([
      t.returnStatement(node.body as t.Expression),
    ]);
  }

  // Lazy import injection. Only fires once we've decided to actually wrap.
  const programPath = path.scope.getProgramParent()
    .path as NodePath<t.Program>;
  ensureRuntimeImports(programPath, state.tfOpts);

  const newBody = buildWrappedBody(node.body as t.BlockStatement, {
    className: state.tfClassName,
    methodName: name,
    paramNames: state.tfOpts.traceArguments
      ? collectParamNames(node.params)
      : [],
    traceArguments: state.tfOpts.traceArguments,
  });

  node.body = newBody;
  node[WRAPPED_FLAG] = true;
}

export const functionExpressionVisitor: Visitor<TFState> = {
  FunctionExpression: { enter: handle },
  ArrowFunctionExpression: { enter: handle },
};
