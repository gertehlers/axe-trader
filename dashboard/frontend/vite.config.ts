import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // shared/vocab.ts lives above this root and is imported by src/ — allow it.
  server: {
    fs: { allow: [".."] },
    proxy: { "/api": "http://localhost:8787" },
  },
  // emptyOutDir clears stale hashed assets, but it also wipes the tracked ../public/.gitkeep that
  // keeps the dir present on a fresh clone — so the build script restores it immediately after.
  build: { outDir: "../public", emptyOutDir: true },
});
