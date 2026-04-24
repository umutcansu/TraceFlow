import { isBrowser, isReactNative } from "./platform.js";

const STORAGE_KEY = "traceflow_device_id";

/**
 * Resolve a stable per-install device id. On web we persist to
 * localStorage; on React Native we attempt AsyncStorage dynamically
 * (peer import) and fall back to an in-memory id if it's not installed.
 * In Node / tests we always fall back to an in-memory id.
 *
 * Returned as a Promise because RN's AsyncStorage is async, and we'd
 * rather have one uniform API than a sync-in-some-cases / async-in-others
 * split.
 */
export async function resolveDeviceId(): Promise<string> {
  // 1. Web — synchronous localStorage.
  if (isBrowser()) {
    try {
      const existing = window.localStorage.getItem(STORAGE_KEY);
      if (existing) return existing;
      const fresh = newUuid();
      window.localStorage.setItem(STORAGE_KEY, fresh);
      return fresh;
    } catch {
      // Private-mode browsers can throw; fall through to ephemeral id.
    }
  }

  // 2. React Native — optional AsyncStorage peer. We never hard-depend on
  //    it because the host app may be using MMKV or a different store.
  if (isReactNative()) {
    try {
      // @ts-expect-error: optional peer dependency supplied by the host app
      const mod = await import("@react-native-async-storage/async-storage");
      const storage = mod.default ?? mod;
      const existing: string | null = await storage.getItem(STORAGE_KEY);
      if (existing) return existing;
      const fresh = newUuid();
      await storage.setItem(STORAGE_KEY, fresh);
      return fresh;
    } catch {
      // fall through
    }
  }

  // 3. Ephemeral (unit tests, servers, or RN without AsyncStorage).
  return newUuid();
}

export function newSessionId(): string {
  return newUuid();
}

function newUuid(): string {
  // crypto.randomUUID is in Node 19+, all modern browsers, Bun, Hermes 0.12+.
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (c?.randomUUID) return c.randomUUID();
  // RFC 4122 v4 fallback using Math.random — acceptable for a non-secret id.
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    const v = ch === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
