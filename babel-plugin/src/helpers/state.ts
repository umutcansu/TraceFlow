/**
 * Per-pass state carried by the plugin's `this` (PluginPass).
 *
 * Babel creates a fresh PluginPass per file, so the fields here are scoped
 * to a single source file. We compute them once in `pre()` and read them
 * from visitors throughout the rest of the pass.
 */

import type { PluginPass } from "@babel/core";
import { ResolvedOptions } from "../options";

export interface TFState extends PluginPass {
  /** Validated, fully-defaulted plugin options. */
  tfOpts: ResolvedOptions;

  /**
   * Whether this file should be skipped entirely. Cached so each visitor
   * doesn't re-run the (cheap) regex/path checks per node.
   */
  tfSkip: boolean;

  /**
   * The "class-like" tag used in emitted ENTER events. Stage 1 computes it
   * but doesn't yet emit anything; Stage 2 visitors will use it as the
   * receiver name when wrapping standalone functions. Defaults to the file
   * basename (extension stripped), or `"<unknown>"` when no filename is
   * available.
   */
  tfClassName: string;
}
