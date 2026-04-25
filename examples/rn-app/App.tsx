// App.tsx — the actual root for the Expo demo. Every function in
// this file is auto-instrumented at build time by
// @umutcansu/traceflow-babel-plugin (registered in babel.config.js).
// The RN runtime is initialised once below; ENTER/EXIT/CATCH events
// flow into the configured TraceFlow server.

import { useState } from "react";
import { Button, SafeAreaView, ScrollView, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import {
  initTraceFlow,
  captureException,
  setUserId,
} from "@umutcansu/traceflow-runtime";

// Edit this for your environment.
//   - Physical device on same LAN (recommended): host's LAN IP
//   - Android emulator: 10.0.2.2
//   - iOS simulator / web: localhost
const ENDPOINT = "http://192.168.1.80:4567/traces";

initTraceFlow({
  endpoint: ENDPOINT,
  appId: "com.example.traceflow-rn-app",
  platform: "react-native",
  appVersion: "0.0.0",
  buildNumber: "1",
  userId: "demo-user",
  flushIntervalMs: 1000,
  batchSize: 20,
  // compress is forced off on RN regardless of this flag (OkHttp
  // BridgeInterceptor strip → server 500). See runtime-js README.
  compress: true,
});

// --- functions wrapped automatically by the babel-plugin ---------

function add(a: number, b: number): number {
  return a + b;
}

const multiply = (a: number, b: number): number => a * b;

async function fetchUser(id: number): Promise<{ id: number; ok: boolean }> {
  await new Promise((r) => setTimeout(r, 5));
  return { id, ok: true };
}

class Cart {
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

function dangerouslyParse(input: string): unknown {
  if (input.startsWith("{")) return JSON.parse(input);
  throw new Error(`refusing to parse non-object: ${input}`);
}

function reportSomethingBad(): void {
  try {
    throw new Error("manual: something failed in a non-traced context");
  } catch (e) {
    captureException(e);
  }
}

// --- UI ----------------------------------------------------------

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const append = (s: string) => setLog((prev) => [...prev, s]);

  const cart = new Cart();

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.h1}>TraceFlow RN demo</Text>
      <Text style={styles.muted}>
        Endpoint: {ENDPOINT}{"\n"}
        Watch the IntelliJ plugin or your TraceFlow dashboard while you
        tap the buttons below.
      </Text>
      <ScrollView contentContainerStyle={styles.buttons}>
        <Button title="add(2, 3)" onPress={() => append(`add → ${add(2, 3)}`)} />
        <Button title="multiply(4, 5)" onPress={() => append(`multiply → ${multiply(4, 5)}`)} />
        <Button
          title="await fetchUser(42)"
          onPress={async () => {
            const u = await fetchUser(42);
            append(`fetchUser → ${JSON.stringify(u)}`);
          }}
        />
        <Button
          title="cart.add() + checkout()"
          onPress={async () => {
            cart.add("apple");
            cart.add("bread");
            const r = await cart.checkout();
            append(`checkout → ${JSON.stringify(r)}`);
          }}
        />
        <Button
          title="throw + caught"
          onPress={() => {
            try { dangerouslyParse("not-an-object"); }
            catch (e) { append(`caught → ${(e as Error).message}`); }
          }}
        />
        <Button
          title="manual reportSomethingBad"
          onPress={() => { reportSomethingBad(); append("manual: reported"); }}
        />
        <Button
          title='setUserId("alice")'
          onPress={() => { setUserId("alice"); append("userId set"); }}
        />
      </ScrollView>
      <View style={styles.log}>
        {log.map((line, i) => (
          <Text key={i} style={styles.logLine}>{line}</Text>
        ))}
      </View>
      <StatusBar style="auto" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, backgroundColor: "#fff" },
  h1: { fontSize: 22, fontWeight: "600", marginBottom: 8 },
  muted: { color: "#666", marginBottom: 16, lineHeight: 18 },
  buttons: { gap: 8 },
  log: { marginTop: 16, padding: 8, backgroundColor: "#f4f4f4", borderRadius: 4 },
  logLine: { fontFamily: "monospace", fontSize: 12, marginBottom: 2 },
});
