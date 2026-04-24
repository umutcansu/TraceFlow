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

  // Stage 4: ClassMethod / ClassPrivateMethod live inside a class body. The
  // method-name slot is `${ClassName}.${kindPrefix?}${keyName}`, with kind
  // prefixes for getters/setters (`get_`, `set_`) and a special-case for the
  // constructor.
  if (path.isClassMethod() || path.isClassPrivateMethod()) {
    const cmPath = path as NodePath<t.ClassMethod | t.ClassPrivateMethod>;
    const cls = classNameFor(cmPath);
    const m = cmPath.node;
    if (m.kind === "constructor") return `${cls}.constructor`;
    const key = keyNameFor(m.key);
    if (m.kind === "get") return `${cls}.get_${key}`;
    if (m.kind === "set") return `${cls}.set_${key}`;
    return `${cls}.${key}`;
  }

  // Stage 4: ObjectMethod (the `{ foo() { ... } }` shorthand). Owner is the
  // enclosing ObjectExpression — we walk one level up from there to look for
  // an assigning context (VariableDeclarator / AssignmentExpression /
  // ObjectProperty) for a friendlier label. If no such context exists the
  // owner is anonymous and we fall back to `<obj>`.
  if (path.isObjectMethod()) {
    const om = path.node as t.ObjectMethod;
    const keyName = keyNameFor(om.key);
    const owner = objectOwnerNameFor(path as NodePath<t.ObjectMethod>);
    return `${owner}.${keyName}`;
  }

  // FunctionDeclaration / FunctionExpression both have an optional `id`. The
  // node's own `id` is the most specific name source — for a named function
  // expression `const handler = function clicked() {}` it should win over the
  // surrounding `VariableDeclarator`'s `handler` binding, since `clicked` is
  // what the function self-references and what shows up in stack traces.
  if (
    (t.isFunctionDeclaration(node) || t.isFunctionExpression(node)) &&
    node.id &&
    t.isIdentifier(node.id)
  ) {
    return node.id.name;
  }

  // For unnamed FunctionExpression / ArrowFunctionExpression, look upward at
  // the parent node to see if the function is being assigned/bound to a name.
  if (t.isFunctionExpression(node) || t.isArrowFunctionExpression(node)) {
    const parent = path.parent;

    // const fn = () => ... ;  /  let fn = function () {};
    if (t.isVariableDeclarator(parent) && t.isIdentifier(parent.id)) {
      return parent.id.name;
    }

    // module.exports.fn = () => ...   or   exports.fn = () => ...
    // also plain `fn = () => ...` with an Identifier LHS.
    if (t.isAssignmentExpression(parent) && parent.right === node) {
      const lhsName = nameFromAssignmentLHS(parent.left);
      if (lhsName !== null) return lhsName;
    }

    // const obj = { fn: () => ... }   /   { "fn": () => ... }   /   { [k]: () => ... }
    if (t.isObjectProperty(parent) && parent.value === node) {
      if (parent.computed) return "<computed>";
      if (t.isIdentifier(parent.key)) return parent.key.name;
      if (t.isStringLiteral(parent.key)) return parent.key.value;
      return "<computed>";
    }
  }

  // Anonymous fallback — use the source line so traces stay distinguishable.
  const line = node.loc?.start.line ?? 0;
  return `<anonymous>:${line}`;
}

/**
 * Resolve a "class name" tag for a ClassMethod or ClassPrivateMethod path.
 *
 * Order:
 *  1. Owning class's own `id` (`class Foo {}`).
 *  2. Surrounding `VariableDeclarator` for anonymous `class` expressions
 *     (`const Helper = class { ... }`).
 *  3. `<AnonClass>` placeholder when neither is available — bare class
 *     expressions like `(class { ping() {} })`.
 */
function classNameFor(
  path: NodePath<t.ClassMethod | t.ClassPrivateMethod>,
): string {
  const cls = path.findParent(
    (p) => p.isClassDeclaration() || p.isClassExpression(),
  );
  if (!cls) return "<NoClass>";
  const node = cls.node as t.ClassDeclaration | t.ClassExpression;
  if (node.id) return node.id.name;
  if (cls.parentPath?.isVariableDeclarator()) {
    const vd = cls.parentPath.node as t.VariableDeclarator;
    if (t.isIdentifier(vd.id)) return vd.id.name;
  }
  return "<AnonClass>";
}

/**
 * Resolve the "owner" name for an ObjectMethod's containing ObjectExpression.
 * Looks one parent above the object literal:
 *  - `const obj = { foo() {} }`               → "obj"
 *  - `module.exports = { foo() {} }`          → "exports" (member-expr LHS)
 *  - `const x = { sub: { foo() {} } }`        → "sub"   (ObjectProperty key)
 *  - bare object literals                     → "<obj>" placeholder
 */
function objectOwnerNameFor(path: NodePath<t.ObjectMethod>): string {
  const objExpr = path.parentPath; // ObjectExpression
  if (!objExpr || !objExpr.isObjectExpression()) return "<obj>";

  const grand = objExpr.parentPath;
  if (!grand) return "<obj>";

  if (grand.isVariableDeclarator()) {
    const vd = grand.node as t.VariableDeclarator;
    if (t.isIdentifier(vd.id)) return vd.id.name;
  }
  if (grand.isAssignmentExpression()) {
    const lhsName = nameFromAssignmentLHS(
      (grand.node as t.AssignmentExpression).left,
    );
    if (lhsName !== null) return lhsName;
  }
  if (grand.isObjectProperty()) {
    const op = grand.node as t.ObjectProperty;
    if (!op.computed) {
      if (t.isIdentifier(op.key)) return op.key.name;
      if (t.isStringLiteral(op.key)) return op.key.value;
    }
  }
  return "<obj>";
}

/**
 * Resolve a method/property key node into a printable string.
 * Mirrors the conventions used in trace events:
 *  - Identifier      → its name
 *  - PrivateName     → `#${id.name}`
 *  - StringLiteral   → its value
 *  - NumericLiteral  → its stringified value
 *  - anything else   → `<computed>`
 */
function keyNameFor(
  key: t.ClassMethod["key"] | t.ObjectMethod["key"] | t.PrivateName,
): string {
  if (t.isIdentifier(key)) return key.name;
  if (t.isPrivateName(key)) return `#${key.id.name}`;
  if (t.isStringLiteral(key)) return key.value;
  if (t.isNumericLiteral(key)) return String(key.value);
  return "<computed>";
}

/**
 * Predicate used by visitors to decide whether a name is "real" enough to
 * justify wrapping. Anonymous fallbacks all start with `<anonymous>` so a
 * single prefix check is sufficient. The `instrumentAnonymous` opt overrides
 * this in the visitor.
 */
export function isWrappableNamed(name: string): boolean {
  return !name.startsWith("<anonymous>");
}

/**
 * Internal: extract a usable name from the LHS of an `AssignmentExpression`.
 *
 *  - `Identifier` → its `name`.
 *  - `MemberExpression` (non-computed) → the deepest property identifier name.
 *    For `module.exports.fn = ...` this is `"fn"`.
 *  - `MemberExpression` (computed) → the StringLiteral value if present, else
 *    the placeholder `<computed>`.
 *
 * Returns `null` when the LHS shape is something we don't handle (e.g.
 * destructuring patterns), so the caller can fall through to the
 * `<anonymous>:<line>` branch.
 */
function nameFromAssignmentLHS(lhs: t.LVal | t.OptionalMemberExpression): string | null {
  if (t.isIdentifier(lhs)) return lhs.name;

  if (t.isMemberExpression(lhs) || t.isOptionalMemberExpression(lhs)) {
    if (lhs.computed) {
      if (t.isStringLiteral(lhs.property)) return lhs.property.value;
      return "<computed>";
    }
    if (t.isIdentifier(lhs.property)) return lhs.property.name;
    return "<computed>";
  }

  return null;
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
