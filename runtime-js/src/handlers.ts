import { isBrowser, isReactNative } from "./platform.js";

/**
 * Installs best-effort global error hooks for the current runtime. The
 * returned teardown restores any prior handlers — call it from shutdown().
 *
 * Coverage:
 *   - React Native: global.ErrorUtils.setGlobalHandler (chains previous)
 *                   + addEventListener('unhandledrejection') if present
 *   - Browser:      window 'error' + 'unhandledrejection'
 *   - Node/other:   process 'uncaughtException' + 'unhandledRejection'
 *
 * The `onError` callback is expected to push a CATCH event into the buffer.
 */
export type ErrorContext = { isFatal?: boolean };
export type ErrorSink = (err: unknown, ctx?: ErrorContext) => void;

export function installGlobalHandlers(onError: ErrorSink): () => void {
  const teardowns: Array<() => void> = [];

  if (isReactNative()) {
    // ErrorUtils is injected onto globalThis by the RN runtime at startup.
    const eu = (globalThis as { ErrorUtils?: {
      getGlobalHandler?: () => ((err: Error, isFatal?: boolean) => void) | undefined;
      setGlobalHandler?: (fn: (err: Error, isFatal?: boolean) => void) => void;
    } }).ErrorUtils;
    if (eu?.setGlobalHandler) {
      const prev = eu.getGlobalHandler?.();
      eu.setGlobalHandler((err, isFatal) => {
        try { onError(err, { isFatal }); } catch { /* swallow */ }
        prev?.(err, isFatal);
      });
      teardowns.push(() => { if (prev) eu.setGlobalHandler!(prev); });
    }
    // RN's Promise polyfill emits 'unhandledrejection' on globalThis.
    const addRN = (globalThis as { addEventListener?: typeof addEventListener }).addEventListener;
    if (typeof addRN === "function") {
      const handler = (e: Event | PromiseRejectionEvent) => {
        const reason = (e as PromiseRejectionEvent).reason ?? e;
        onError(reason);
      };
      addRN.call(globalThis, "unhandledrejection", handler);
      teardowns.push(() => {
        (globalThis as { removeEventListener?: typeof removeEventListener }).removeEventListener
          ?.call(globalThis, "unhandledrejection", handler);
      });
    }
  } else if (isBrowser()) {
    const errHandler = (e: ErrorEvent) => onError(e.error ?? new Error(e.message));
    const rejHandler = (e: PromiseRejectionEvent) => onError(e.reason);
    window.addEventListener("error", errHandler);
    window.addEventListener("unhandledrejection", rejHandler);
    teardowns.push(() => window.removeEventListener("error", errHandler));
    teardowns.push(() => window.removeEventListener("unhandledrejection", rejHandler));
  } else if (typeof process !== "undefined" && typeof process.on === "function") {
    // Node / Bun — useful for smoke tests.
    const uncaught = (err: Error) => onError(err, { isFatal: true });
    const rejection = (reason: unknown) => onError(reason);
    process.on("uncaughtException", uncaught);
    process.on("unhandledRejection", rejection);
    teardowns.push(() => process.off("uncaughtException", uncaught));
    teardowns.push(() => process.off("unhandledRejection", rejection));
  }

  return () => {
    for (const t of teardowns) {
      try { t(); } catch { /* swallow */ }
    }
  };
}
