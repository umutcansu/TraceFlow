import type { Platform } from "./types.js";

/**
 * Public configuration accepted by `initTraceFlow`. All fields except
 * `endpoint`, `appId`, and `platform` are optional.
 */
export interface TraceFlowConfig {
  /** Full URL to the POST /traces endpoint, e.g. "https://tf.example.com/traces". */
  endpoint: string;
  /** Stable app identifier, typically reverse-DNS: "com.bufraz.parla". */
  appId: string;
  /** Either "react-native" or "web-js" — see `Platform` for the full union. */
  platform: Platform;

  /** If the server has TRACEFLOW_INGEST_TOKEN set, pass it here. */
  token?: string;
  /** e.g. "1.2.3" — from app.json / package.json / build config. */
  appVersion?: string;
  /** e.g. "45" — monotonic build counter. */
  buildNumber?: string;
  /** Currently-signed-in user. Can be updated later via setUserId. */
  userId?: string;

  /** Free-form runtime tag; defaults to a best-effort detection. */
  runtime?: string;

  /** How often to flush the buffer when it's non-empty, ms. Default 5000. */
  flushIntervalMs?: number;
  /** Max events per POST batch. Default 50. */
  batchSize?: number;
  /** Hard ceiling; oldest events drop first once exceeded. Default 1000. */
  maxBufferSize?: number;
  /** Whether to gzip request bodies. Default true. */
  compress?: boolean;

  /** PII-mask regex patterns applied to `params` keys. */
  maskPatterns?: RegExp[];

  /**
   * When true, failed posts are kept in-memory and retried on the next flush.
   * A persistent (AsyncStorage / IndexedDB) queue is out of scope for phase 1;
   * events are held in the same ring buffer used for incoming events.
   */
  retryOnError?: boolean;
}

/** Fully-resolved config with defaults applied. Runtime internal. */
export interface ResolvedConfig extends Required<Omit<TraceFlowConfig, "token" | "userId" | "appVersion" | "buildNumber">> {
  token: string | undefined;
  userId: string | undefined;
  appVersion: string | undefined;
  buildNumber: string | undefined;
}

export const DEFAULT_MASK_PATTERNS: RegExp[] = [
  /password/i,
  /token/i,
  /jwt/i,
  /api[_-]?key/i,
  /secret/i,
  /cvv/i,
  /ssn/i,
  /pin/i,
  /creditcard/i,
];

export function resolveConfig(cfg: TraceFlowConfig): ResolvedConfig {
  if (!cfg.endpoint) throw new Error("TraceFlow: endpoint is required");
  if (!cfg.appId) throw new Error("TraceFlow: appId is required");
  if (!cfg.platform) throw new Error("TraceFlow: platform is required");

  return {
    endpoint: cfg.endpoint,
    appId: cfg.appId,
    platform: cfg.platform,
    token: cfg.token,
    appVersion: cfg.appVersion,
    buildNumber: cfg.buildNumber,
    userId: cfg.userId,
    runtime: cfg.runtime ?? "",
    flushIntervalMs: cfg.flushIntervalMs ?? 5000,
    batchSize: cfg.batchSize ?? 50,
    maxBufferSize: cfg.maxBufferSize ?? 1000,
    compress: cfg.compress ?? true,
    maskPatterns: cfg.maskPatterns ?? DEFAULT_MASK_PATTERNS,
    retryOnError: cfg.retryOnError ?? true,
  };
}
