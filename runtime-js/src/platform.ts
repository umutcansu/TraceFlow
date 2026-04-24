/**
 * Small detection helpers used to set sensible `runtime` defaults and to
 * decide which global-error hooks to install. Kept minimal — we only look
 * at globals that are stable across environments.
 */

declare const navigator: { userAgent?: string; product?: string } | undefined;
// React Native exposes global.HermesInternal when running on Hermes.
declare const HermesInternal: unknown;

export function detectRuntime(fallback: string = ""): string {
  // Node
  if (typeof process !== "undefined" && process.versions?.node) {
    return `node-${process.versions.node}`;
  }
  // React Native on Hermes
  if (typeof HermesInternal !== "undefined") {
    return "hermes";
  }
  // React Native on JSC / classic
  if (typeof navigator !== "undefined" && navigator.product === "ReactNative") {
    return "react-native";
  }
  // Browser
  if (typeof navigator !== "undefined" && navigator.userAgent) {
    return `web-${navigator.userAgent.split(" ")[0]}`;
  }
  return fallback;
}

export function isReactNative(): boolean {
  return (
    typeof HermesInternal !== "undefined" ||
    (typeof navigator !== "undefined" && navigator.product === "ReactNative")
  );
}

export function isBrowser(): boolean {
  return typeof window !== "undefined" && typeof document !== "undefined";
}
