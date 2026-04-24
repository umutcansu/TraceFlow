/**
 * Name-derivation helpers used by Stage 2+ visitors.
 *
 * Two concerns live here:
 *
 *  - `methodNameFor` resolves the "method" slot of an ENTER/EXIT event from a
 *    `t.Function` node. Stage 2 only needs the `FunctionDeclaration` branch,
 *    but the function is typed against the wider `t.Function` so future stages
 *    (FunctionExpression, ArrowFunctionExpression, ClassMethod, ObjectMethod)
 *    can extend the same helper without changing call sites.
 *
 *  - `collectParamNames` extracts a flat list of identifier-like names from a
 *    function's `params`. The names are emitted as keys in the params object
 *    that Stage 2 passes to ENTER. Anything that isn't a plain identifier (or
 *    a plain identifier wrapped in an `AssignmentPattern` for default values)
 *    becomes a stable placeholder so downstream consumers don't trip over a
 *    missing key, and the runtime never sees an undefined identifier.
 */
import type { NodePath } from "@babel/core";
import * as t from "@babel/types";

/**
 * Derive the "method" slot for a wrappable function.
 *
 * Stage 2 only handles `FunctionDeclaration`, where the parser guarantees an
 * `id`. Other function-like nodes either lack an `id` (arrows) or live under
 * an `ObjectProperty`/`ClassMethod`/`AssignmentExpression` whose key supplies
 * the name. We type-narrow defensively so a future caller can pass any
 * `t.Function` without a runtime crash.
 */
export function methodNameFor(path: NodePath<t.Function>): string {
  const node = path.node;

  // FunctionDeclaration / FunctionExpression both have an optional `id`.
  if (
    (t.isFunctionDeclaration(node) || t.isFunctionExpression(node)) &&
    node.id &&
    t.isIdentifier(node.id)
  ) {
    return node.id.name;
  }

  return "<anonymous>";
}

/**
 * Walk a function's `params` array and emit a name for each entry.
 *
 *  - `Identifier`            → the identifier name (`a`)
 *  - `AssignmentPattern`     → recurse into `left` (default values: `a = 1`)
 *  - `RestElement`           → `${name}_rest` when the rest's argument is an
 *                              identifier, otherwise the destructure
 *                              placeholder for `_destr_${index}` semantics
 *  - `ObjectPattern` /
 *    `ArrayPattern`          → `_destr_${index}` (we cannot safely synthesize
 *                              meaningful keys without re-evaluating the
 *                              pattern at runtime, which is out of scope)
 *
 * Stage 2's FunctionDeclarations realistically only contain identifier
 * params, but the destructuring fallback keeps the helper future-proof for
 * Stages 3+.
 */
export function collectParamNames(params: t.Function["params"]): string[] {
  const out: string[] = [];

  params.forEach((p, index) => {
    out.push(nameForParam(p, index));
  });

  return out;
}

/** Internal: name a single param by node shape. */
function nameForParam(node: t.Node, index: number): string {
  if (t.isIdentifier(node)) {
    return node.name;
  }

  if (t.isAssignmentPattern(node)) {
    // Default value: descend into the pattern on the left-hand side.
    return nameForParam(node.left, index);
  }

  if (t.isRestElement(node)) {
    if (t.isIdentifier(node.argument)) {
      return `${node.argument.name}_rest`;
    }
    return `_destr_${index}`;
  }

  if (t.isObjectPattern(node) || t.isArrayPattern(node)) {
    return `_destr_${index}`;
  }

  // TSParameterProperty (TypeScript) and any other exotic shape: fall back to
  // the destructure placeholder so we always emit a valid key.
  return `_destr_${index}`;
}
