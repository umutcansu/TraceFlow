// App.tsx — illustrative source that exercises every shape the
// babel-plugin instruments: top-level functions, arrows, async,
// class methods, and a deliberate throw to see CATCH events flow.
//
// In a real React Native app this file would render JSX and call
// initTraceFlow() in App's outermost component. We omit JSX here so
// the example runs in Node without an RN runtime.

import { initTraceFlow, captureException } from "@umutcansu/traceflow-runtime";

// One-shot initialisation. Typically lives in your app's entry point.
initTraceFlow({
  endpoint: "http://localhost:4567/traces",
  appId: "com.example.rn-sample",
  platform: "react-native",
  appVersion: "1.0.0",
  buildNumber: "1",
  userId: "demo-user",
  flushIntervalMs: 200,
  batchSize: 10,
  compress: false, // simulate.mjs prefers raw JSON for inspection
});

// --- pure functions: wrapped with try/finally + ENTER/EXIT --------

export function add(a: number, b: number): number {
  return a + b;
}

export const multiply = (a: number, b: number): number => a * b;

// --- async function: same wrap shape, finally fires after await ---

export async function fetchUser(id: number): Promise<{ id: number; ok: boolean }> {
  // In a real app: const r = await fetch(`/u/${id}`); return r.json();
  await new Promise((r) => setTimeout(r, 5));
  return { id, ok: true };
}

// --- class method: ClassName.method labelling --------------------

export class Cart {
  private items: string[] = [];

  add(item: string): number {
    this.items.push(item);
    return this.items.length;
  }

  async checkout(): Promise<{ count: number }> {
    await new Promise((r) => setTimeout(r, 2));
    return { count: this.items.length };
  }
}

// --- a function that throws: lands in CATCH attributed to itself --

export function dangerouslyParse(input: string): unknown {
  if (input.startsWith("{")) return JSON.parse(input);
  throw new Error(`refusing to parse non-object: ${input}`);
}

// --- explicit captureException for the manual case --------------

export function reportSomethingBad(): void {
  try {
    throw new Error("manual: something failed in a non-traced context");
  } catch (e) {
    captureException(e);
  }
}
