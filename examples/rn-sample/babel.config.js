// babel.config.js — same shape a real React Native project would use.
//
// In a Metro / RN project this file lives at the repo root and Metro
// picks it up automatically. The TraceFlow plugin registers as a
// regular plugin (not a preset) so it sees source-level functions
// before any later transforms strip types or rewrite modules.
//
// Production deployments typically gate the plugin via NODE_ENV; the
// plugin is a no-op when NODE_ENV === "production". Override with
// `enabled: true` if you also want production traces.
module.exports = {
  presets: [
    "@babel/preset-typescript",
    // In a real RN project: "module:metro-react-native-babel-preset",
  ],
  plugins: [
    [
      "@umutcansu/traceflow-babel-plugin",
      {
        // Skip generated files / vendor code that the user doesn't own.
        excludePatterns: [/\.generated\.[jt]sx?$/],
        // Default true — set false if you want callbacks like
        // arr.map(x => x*2) to be wrapped too.
        instrumentAnonymous: false,
      },
    ],
  ],
};
