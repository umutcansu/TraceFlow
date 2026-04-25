/**
 * Stage 4 visitor: wraps `ClassMethod`, `ClassPrivateMethod`, and
 * `ObjectMethod` nodes with the same ENTER/EXIT/CATCH prelude used by
 * Stages 2-3. Naming is delegated to `methodNameFor`.
 *
 * Rules specific to this stage:
 *
 *  - Derived-class constructors are skipped. The first statement in such a
 *    body must be the `super(...)` call; injecting a `__tf_c?.enter(...)`
 *    expression statement before it is illegal in ES classes. Non-derived
 *    constructors (no `superClass` on the owning class) are wrapped.
 *  - `super.foo()` and `this.x` work without special handling because the
 *    body stays inline (plain try/finally — no IIFE).
 *  - `StaticBlock` and `ClassProperty` are not visited here — they aren't
 *    method-like and Babel doesn't route them through these node types.
 *
 * Out of scope (handled by later stages, do not touch here):
 *  - async / generator methods → Stage 5
 */
import type { NodePath, Visitor } from "@babel/core";
import * as t from "@babel/types";
import { TFState } from "../helpers/state";
import { hasNoTraceComment } from "../helpers/skip";
import { ensureRuntimeClientImport } from "../helpers/imports";
import { methodNameFor, collectParamNames } from "../helpers/naming";
import { buildWrappedBody } from "../helpers/builders";

/** Same flag Stages 2-3 use — keeps multi-pass idempotency uniform. */
const WRAPPED_FLAG = "__tfWrapped";

/**
 * `true` iff `path` is a constructor whose owning class has a `superClass`.
 * Such constructors must run `super(...)` before any other statement, so we
 * leave them untouched.
 */
function isDerivedConstructor(
  path: NodePath<t.ClassMethod | t.ClassPrivateMethod>,
): boolean {
  if (path.node.kind !== "constructor") return false;
  const cls = path.findParent(
    (p) => p.isClassDeclaration() || p.isClassExpression(),
  );
  if (!cls) return false;
  return !!(cls.node as t.ClassDeclaration | t.ClassExpression).superClass;
}

function handle(
  path: NodePath<t.ClassMethod | t.ClassPrivateMethod | t.ObjectMethod>,
  state: TFState,
): void {
  if (state.tfSkip) return;

  const node = path.node as (
    | t.ClassMethod
    | t.ClassPrivateMethod
    | t.ObjectMethod
  ) & { [WRAPPED_FLAG]?: boolean };
  if (node[WRAPPED_FLAG]) return;
  if (hasNoTraceComment(node)) return;

  // Stage 5: async methods are now wrapped. Generator methods (and async
  // generator methods, since `async` + `generator` both flag true) remain
  // deferred. `buildWrappedBody` is unchanged — async-await flows through
  // try/finally with identical observable semantics to sync.
  if (node.generator) return;

  // Skip derived-class constructors — `super()` must remain the first stmt.
  if (
    (path.isClassMethod() || path.isClassPrivateMethod()) &&
    isDerivedConstructor(
      path as NodePath<t.ClassMethod | t.ClassPrivateMethod>,
    )
  ) {
    return;
  }

  // Lazy import injection — only fires when we actually wrap something.
  const programPath = path.scope.getProgramParent()
    .path as NodePath<t.Program>;
  const clientFn = ensureRuntimeClientImport(programPath, state.tfOpts);

  const newBody = buildWrappedBody(node.body, {
    className: state.tfClassName,
    methodName: methodNameFor(path as unknown as NodePath<t.Function>),
    paramNames: state.tfOpts.traceArguments
      ? collectParamNames(node.params)
      : [],
    traceArguments: state.tfOpts.traceArguments,
    clientFn,
  });

  node.body = newBody;
  node[WRAPPED_FLAG] = true;
}

export const classMethodVisitor: Visitor<TFState> = {
  ClassMethod: { enter: handle },
  ClassPrivateMethod: { enter: handle },
  ObjectMethod: { enter: handle },
};
