import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
export default defineConfig({
  testDir: ".",
  testMatch: "inventory.perf.ts",
  timeout: 750000,
  workers: 1,
  outputDir: process.env.PERF_OUTPUT ?? "../../../.local/performance-browser",
  use: {
    baseURL: "http://127.0.0.1:4182",
    headless: true,
    viewport: { width: 1440, height: 940 },
  },
  webServer: {
    cwd: fileURLToPath(new URL("../../", import.meta.url)),
    command:
      "node node_modules/vite/bin/vite.js preview --outDir ../.local/perf-dist --host 127.0.0.1 --port 4182 --strictPort",
    url: "http://127.0.0.1:4182",
    reuseExistingServer: true,
  },
});
