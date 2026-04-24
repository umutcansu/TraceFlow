/**
 * AST builders for Stage 2's function-body wrapper.
 *
 * The wrapper deliberately avoids any code-rewriting tricks (no IIFE, no
 * return-value capture variable). Instead we keep the original body verbatim
 * inside a `try` block and rely on JavaScript's well-defined try/finally
 * semantics to evaluate `return` expressions before `finally` runs. That
 * preserves `super`, `arguments`, `this`, and source positions for everything
 * inside the body — only the prelude/postlude shifts.
 *
 * Output shape:
 *
 *   const __tf_c  = __tf_getClient();
 *   const __tf_t0 = Date.now();
 *   __tf_c?.enter("<class>", "<method>", <paramsObj | undefined>);
 *   try {
 *     <...originalStatements...>
 *   } catch (__tf_e) {
 *     __tf_capture(__tf_e);
 *     throw __tf_e;
 *   } finally {
 *     __tf_c?.exit("<class>", "<method>", undefined, Date.now() - __tf_t0);
 *   }
 *
 * The `?.` is emitted as a real `OptionalCallExpression` over an
 * `OptionalMemberExpression` so any downstream Babel preset that targets
 * pre-ES2020 environments can lower it cleanly.
 */
import * as t from "@babel/types";

/** Local identifier names — kept in one place so renames are atomic. */
const ID_CLIENT = "__tf_c";
const ID_T0 = "__tf_t0";
const ID_GET_CLIENT = "__tf_getClient";
const ID_CAPTURE = "__tf_capture";
const ID_ERR = "__tf_e";

/** Inputs needed to build the wrapped body. */
export interface WrapInputs {
  /** Class-like tag (typically the file basename). */
  className: string;
  /** Method name as it should appear in ENTER/EXIT events. */
  methodName: string;
  /** Param identifier names; ignored when `traceArguments` is false. */
  paramNames: string[];
  /** Whether the params object should be emitted at all. */
  traceArguments: boolean;
}

/**
 * Build the wrapped body. Returns a fresh `BlockStatement` that should
 * REPLACE the original function's body. The original block is reused as the
 * `try` block's body without being cloned — Babel is fine with this since
 * the original parent (the function node) drops its reference.
 */
export function buildWrappedBody(
  originalBody: t.BlockStatement,
  w: WrapInputs,
): t.BlockStatement {
  const classLit = t.stringLiteral(w.className);
  const methodLit = t.stringLiteral(w.methodName);

  // const __tf_c = __tf_getClient();
  const declClient = t.variableDeclaration("const", [
    t.variableDeclarator(
      t.identifier(ID_CLIENT),
      t.callExpression(t.identifier(ID_GET_CLIENT), []),
    ),
  ]);

  // const __tf_t0 = Date.now();
  const declT0 = t.variableDeclaration("const", [
    t.variableDeclarator(
      t.identifier(ID_T0),
      t.callExpression(
        t.memberExpression(t.identifier("Date"), t.identifier("now")),
        [],
      ),
    ),
  ]);

  // Params object for ENTER. When `traceArguments` is false OR the function
  // has no params, we pass `undefined` so the runtime sees a single argument
  // shape regardless of opts.
  const paramsArg: t.Expression =
    w.traceArguments && w.paramNames.length > 0
      ? t.objectExpression(
          w.paramNames.map((name) =>
            t.objectProperty(
              t.identifier(name),
              t.identifier(name),
              false,
              true, // shorthand: { a } not { a: a }
            ),
          ),
        )
      : t.identifier("undefined");

  // __tf_c?.enter("<class>", "<method>", paramsArg);
  const enterCall = t.expressionStatement(
    t.optionalCallExpression(
      t.optionalMemberExpression(
        t.identifier(ID_CLIENT),
        t.identifier("enter"),
        false, // computed
        true, // optional
      ),
      [classLit, methodLit, paramsArg],
      false, // the call itself is not optional; only the member access is
    ),
  );

  // __tf_capture(__tf_e); throw __tf_e;
  const catchClause = t.catchClause(
    t.identifier(ID_ERR),
    t.blockStatement([
      t.expressionStatement(
        t.callExpression(t.identifier(ID_CAPTURE), [t.identifier(ID_ERR)]),
      ),
      t.throwStatement(t.identifier(ID_ERR)),
    ]),
  );

  // __tf_c?.exit("<class>", "<method>", undefined, Date.now() - __tf_t0);
  const elapsed = t.binaryExpression(
    "-",
    t.callExpression(
      t.memberExpression(t.identifier("Date"), t.identifier("now")),
      [],
    ),
    t.identifier(ID_T0),
  );
  const finallyBlock = t.blockStatement([
    t.expressionStatement(
      t.optionalCallExpression(
        t.optionalMemberExpression(
          t.identifier(ID_CLIENT),
          t.identifier("exit"),
          false,
          true,
        ),
        [
          t.cloneNode(classLit),
          t.cloneNode(methodLit),
          t.identifier("undefined"),
          elapsed,
        ],
        false,
      ),
    ),
  ]);

  const tryStmt = t.tryStatement(originalBody, catchClause, finallyBlock);

  return t.blockStatement([declClient, declT0, enterCall, tryStmt]);
}
