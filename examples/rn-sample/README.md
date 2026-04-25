# traceflow-rn-sample

A minimal example wiring [`@umutcansu/traceflow-runtime`](https://www.npmjs.com/package/@umutcansu/traceflow-runtime)
and [`@umutcansu/traceflow-babel-plugin`](https://www.npmjs.com/package/@umutcansu/traceflow-babel-plugin)
together. The source files mirror what a real React Native project
needs (`App.tsx`, `babel.config.js`); a thin `simulate.mjs` script
runs the babel transform + executes the output in plain Node so you
can see ENTER/EXIT/CATCH events flow without booting iOS or Android.

## Run it

```bash
cd examples/rn-sample
npm install
npm run simulate
```

You should see something like:

```
[rn-sample] transforming src/App.tsx with the babel-plugin...
[rn-sample] calling instrumented functions...
  add(2,3) -> 5
  multiply(4,5) -> 20
  await fetchUser(42) -> {"id":42,"ok":true}
  cart.checkout() -> {"count":2}
  dangerouslyParse threw "refusing to parse non-object: not-an-object" (caught + reported)

[rn-sample] flushing buffered events...

[rn-sample] captured N events:

  ENTER  App.add                       params={"a":2,"b":3}
  EXIT   App.add                       durationMs=0
  ENTER  App.multiply                  params={"a":4,"b":5}
  EXIT   App.multiply                  durationMs=0
  ENTER  App.fetchUser                 params={"id":42}
  EXIT   App.fetchUser                 durationMs=5
  ENTER  Cart.add                      params={"item":"apple"}
  EXIT   Cart.add                      durationMs=0
  ...
  CATCH  App.dangerouslyParse          exception=Error msg="refusing to parse non-object: not-an-object"
  EXIT   App.dangerouslyParse          durationMs=0
```

Every function in `src/App.tsx` was instrumented automatically by the
Babel plugin — no `trace()` calls in the source. The CATCH event is
attributed to `dangerouslyParse` because the plugin emits the client's
`caught()` method (runtime-js >= 0.2.0) instead of the generic
`captureException` slot.

## Drop into a real React Native project

The same three pieces translate one-to-one into a real RN app:

1. **Install both packages**:
   ```bash
   yarn add @umutcansu/traceflow-runtime
   yarn add -D @umutcansu/traceflow-babel-plugin
   # Recommended on RN so deviceId persists across launches:
   yarn add @react-native-async-storage/async-storage
   ```

2. **Register the plugin** in your project's `babel.config.js`:
   ```js
   module.exports = {
     presets: ["module:metro-react-native-babel-preset"],
     plugins: [
       "@umutcansu/traceflow-babel-plugin",
     ],
   };
   ```

3. **Initialise the runtime** once, in your app entry:
   ```ts
   import { initTraceFlow } from "@umutcansu/traceflow-runtime";

   initTraceFlow({
     endpoint: "https://your-traceflow-server/traces",
     appId: "com.yourcompany.app",
     platform: "react-native",
     appVersion: "1.0.0",
     userId: currentUserId,
     token: process.env.TRACEFLOW_TOKEN, // when the server enforces it
   });
   ```

4. **Reset Metro** once:
   ```bash
   yarn start --reset-cache
   ```

5. **Run the sample server** (or your own TraceFlow deployment):
   ```bash
   cd ../../sample-server && ./gradlew run
   ```
   For Android emulator point `endpoint` at `http://10.0.2.2:4567/traces`
   instead of `localhost`.

6. Open the **Android Studio TraceFlow plugin** ([JetBrains
   Marketplace](https://plugins.jetbrains.com/plugin/30959-traceflow))
   → Remote tab → connect → events appear live as you exercise the app.

## What gets wrapped

`src/App.tsx` deliberately mixes shapes the plugin handles:

- top-level `function add(...)` declarations
- `const multiply = (...) => ...` arrow expressions
- `async function fetchUser(...)` async functions
- `class Cart { add(); async checkout(); }` class methods (sync + async)
- a function that throws (`dangerouslyParse`) to see CATCH attributed to the originating function
- a manual `captureException` call (`reportSomethingBad`) for the cases where you want to report into a non-instrumented context

Anonymous callbacks (e.g. `arr.map(x => x*2)`) are left alone by
default — flip `instrumentAnonymous: true` in `babel.config.js` if
you want them too. Generators (`function*`) and async generators are
deferred and stay unwrapped.

## Disabling the plugin temporarily

If you hit a transform issue and need to bypass the plugin without
removing dependencies:

```js
// babel.config.js
module.exports = {
  presets: [...],
  plugins: process.env.NODE_ENV === "production"
    ? []                                          // no plugin in prod
    : ["@umutcansu/traceflow-babel-plugin"],      // dev only
};
```

The plugin's own `enabled` option already defaults to "off in
production" (`process.env.NODE_ENV !== "production"`), so most teams
don't need an extra gate.
