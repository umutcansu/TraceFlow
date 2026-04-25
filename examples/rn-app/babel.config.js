// babel.config.js — exact shape we recommend for a real RN project.
//
// The TraceFlow plugin sits in `plugins:` so it runs BEFORE the
// `babel-preset-expo` preset (Babel applies plugins, then presets in
// reverse). That order matters: the plugin sees the original ESM
// source, then the preset's modules-commonjs pass converts every
// import — including the one we inject — into a CJS `require()` call.
// Hermes accepts the resulting bundle without "import declaration
// must be at top level" errors.
module.exports = function (api) {
  api.cache(true);
  return {
    presets: ["babel-preset-expo"],
    plugins: ["@umutcansu/traceflow-babel-plugin"],
  };
};
