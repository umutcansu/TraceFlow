/**
 * TraceFlow Babel plugin — Stage 1 entry point.
 *
 * This stage stands up the *infrastructure* for instrumentation without
 * actually rewriting any user code:
 *
 *  - Resolves and validates plugin options once per file (in `pre`).
 *  - Decides whether the file should be skipped (node_modules, runtime self,
 *    user excludes, master `enabled` switch).
 *  - Computes the file's "class name" tag used by future ENTER events.
 *
 * Function-wrapping visitors are intentionally absent; they land in Stage 2
 * along with the lazy `ensureRuntimeImports` call site.
 */

import type { PluginObj, NodePath } from "@babel/core";
import * as t from "@babel/types";
import * as path from "node:path";
import { resolveOptions } from "./options";
import { shouldSkipFile } from "./helpers/skip";
import { TFState } from "./helpers/state";
import { functionDeclarationVisitor } from "./visitors/function-declaration";
import { functionExpressionVisitor } from "./visitors/function-expression";
import { classMethodVisitor } from "./visitors/class-method";

export type { TraceFlowPluginOptions, ResolvedOptions } from "./options";

export default function traceflowBabelPlugin(): PluginObj<TFState> {
  return {
    name: "traceflow",

    /**
     * Called once per file before any visitor runs. We use it to populate the
     * per-pass state (`this.tfOpts`, `this.tfSkip`, `this.tfClassName`) so
     * visitors don't recompute these on every node.
     */
    pre(file) {
      this.tfOpts = resolveOptions(this.opts);

      const filename = file.opts.filename ?? undefined;

      // A file is skipped if either the master switch is off OR file-level
      // checks (paths, excludes, runtime-self) match.
      this.tfSkip =
        !this.tfOpts.enabled || shouldSkipFile(filename, this.tfOpts);

      // ENTER events tag each call site with a "class-like" name. For files
      // that aren't actual class members we fall back to the file basename,
      // which gives readable traces without any user configuration.
      this.tfClassName = filename
        ? path.basename(filename, path.extname(filename))
        : "<unknown>";
    },

    visitor: {
      Program: {
        enter(_programPath: NodePath<t.Program>, state: TFState) {
          if (state.tfSkip) return;
          // Stage 2: wrappable-function visitors (registered below via
          // spread) handle the actual instrumentation. The Program enter
          // hook stays so future stages can attach program-level setup
          // without changing the plugin's overall shape.
        },
      },
      // Spread merges visitor keys (`FunctionDeclaration`, etc.) into the
      // top-level visitor object. Each later stage adds its own visitor
      // export and spreads it the same way, so this list grows linearly.
      ...functionDeclarationVisitor,
      ...functionExpressionVisitor,
      ...classMethodVisitor,
    },
  };
}
