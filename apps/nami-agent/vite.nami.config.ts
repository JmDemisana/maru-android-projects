import { resolve } from "node:path";
import { defineConfig } from "vite";

// Separate Vite config for the nami-agent web bundle
export default defineConfig({
  root: resolve(__dirname, "nami-agent"),
  base: "./",
  build: {
    outDir: resolve(__dirname, "android/app/src/main/assets/nami-agent"),
    emptyOutDir: true,
    sourcemap: false,
    target: "es2020",
    assetsDir: ".",
  },
});
