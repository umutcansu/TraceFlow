/**
 * Public and resolved option types for the TraceFlow Babel plugin, plus the
 * `resolveOptions` helper that normalizes user-supplied configuration.
 *
 * The plugin separates the *public* surface (`TraceFlowPluginOptions`, all
 * fields optional) from the *internal* surface (`ResolvedOptions`, all fields
 * required and validated). Visitors operate exclusively on `ResolvedOptions`
 * so they never have to deal with `undefined`.
 */

/**
 * Options accepted by the Babel plugin in babel.config.js / .babelrc.
 *
 * Every field is optional; missing fields are filled in by `resolveOptions`.
 */
export interface TraceFlowPluginOptions {
  /**
   * Module specifier from which the runtime helpers are imported.
   * Defaults to the canonical npm package name `@umutcansu/traceflow-runtime`.
   */
  runtimeImport?: string;

  /**
   * Regex patterns matched against the absolute filename. Any match means the
   * file is *not* instrumented. Always combined with the built-in skips
   * (node_modules, runtime self).
   */
  excludePatterns?: RegExp[];

  /**
   * When true, the wrapper records arguments alongside the ENTER event.
   * Stage 1 only stores this — Stage 2+ visitors will consume it.
   */
  traceArguments?: boolean;

  /**
   * Master kill-switch. When false, the plugin becomes a pure no-op.
   * Defaults to `true` unless `process.env.NODE_ENV === "production"`, since
   * production builds typically want zero tracing overhead.
   */
  enabled?: boolean;

  /**
   * Stage 3+: when true, arrow / function-expression nodes that have no
   * derivable name (e.g. inline callbacks like `arr.map(x => x*2)`) are still
   * wrapped, with a synthetic `<anonymous>:<line>` method name.
   *
   * Defaults to `false` so casual callbacks don't pollute traces.
   */
  instrumentAnonymous?: boolean;
}

/**
 * Same shape as `TraceFlowPluginOptions` but with every field guaranteed to
 * be present after `resolveOptions` runs. This is what the visitors consume.
 */
export interface ResolvedOptions {
  runtimeImport: string;
  excludePatterns: RegExp[];
  traceArguments: boolean;
  enabled: boolean;
  instrumentAnonymous: boolean;
}

/** Default runtime package name — kept as a constant so it appears once. */
const DEFAULT_RUNTIME_IMPORT = "@umutcansu/traceflow-runtime";

/**
 * Validate and normalize the raw `this.opts` object received from Babel.
 *
 * - `null` / `undefined` is treated as an empty options object.
 * - Each field is type-checked; mistakes throw with a descriptive message
 *   that names the offending field, since silent fallback behavior is hard
 *   to debug in build pipelines.
 */
export function resolveOptions(raw: unknown): ResolvedOptions {
  // Babel may pass `undefined` when the user supplies no plugin options.
  // Normalize to an empty object so destructuring below stays simple.
  const input: Record<string, unknown> =
    raw === null || raw === undefined ? {} : (raw as Record<string, unknown>);

  if (typeof input !== "object") {
    throw new TypeError(
      `[traceflow] plugin options must be an object, got ${typeof input}`,
    );
  }

  // ---- runtimeImport ------------------------------------------------------
  let runtimeImport: string = DEFAULT_RUNTIME_IMPORT;
  if (input.runtimeImport !== undefined) {
    if (typeof input.runtimeImport !== "string" || input.runtimeImport.length === 0) {
      throw new TypeError(
        `[traceflow] option "runtimeImport" must be a non-empty string, ` +
          `got ${JSON.stringify(input.runtimeImport)}`,
      );
    }
    runtimeImport = input.runtimeImport;
  }

  // ---- excludePatterns ----------------------------------------------------
  let excludePatterns: RegExp[] = [];
  if (input.excludePatterns !== undefined) {
    if (!Array.isArray(input.excludePatterns)) {
      throw new TypeError(
        `[traceflow] option "excludePatterns" must be an array of RegExp, ` +
          `got ${typeof input.excludePatterns}`,
      );
    }
    for (const p of input.excludePatterns) {
      if (!(p instanceof RegExp)) {
        throw new TypeError(
          `[traceflow] option "excludePatterns" entries must be RegExp instances; ` +
            `got ${typeof p}`,
        );
      }
    }
    excludePatterns = input.excludePatterns as RegExp[];
  }

  // ---- traceArguments -----------------------------------------------------
  let traceArguments = true;
  if (input.traceArguments !== undefined) {
    if (typeof input.traceArguments !== "boolean") {
      throw new TypeError(
        `[traceflow] option "traceArguments" must be a boolean, ` +
          `got ${typeof input.traceArguments}`,
      );
    }
    traceArguments = input.traceArguments;
  }

  // ---- enabled ------------------------------------------------------------
  // Default: enabled UNLESS NODE_ENV === "production". Evaluated lazily on
  // each call so tests that mutate process.env see fresh behaviour.
  let enabled = process.env.NODE_ENV !== "production";
  if (input.enabled !== undefined) {
    if (typeof input.enabled !== "boolean") {
      throw new TypeError(
        `[traceflow] option "enabled" must be a boolean, ` +
          `got ${typeof input.enabled}`,
      );
    }
    enabled = input.enabled;
  }

  // ---- instrumentAnonymous ------------------------------------------------
  let instrumentAnonymous = false;
  if (input.instrumentAnonymous !== undefined) {
    if (typeof input.instrumentAnonymous !== "boolean") {
      throw new TypeError(
        `[traceflow] option "instrumentAnonymous" must be a boolean, ` +
          `got ${typeof input.instrumentAnonymous}`,
      );
    }
    instrumentAnonymous = input.instrumentAnonymous;
  }

  return {
    runtimeImport,
    excludePatterns,
    traceArguments,
    enabled,
    instrumentAnonymous,
  };
}
