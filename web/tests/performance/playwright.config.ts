import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
const port = Number(process.env.AE2_UI_PERF_PORT ?? 4182);
export default defineConfig({
  testDir: ".",
  testMatch: "inventory.perf.ts",
  timeout: 750000,
  workers: 1,
  outputDir: process.env.PERF_OUTPUT ?? "../../../.local/performance-browser",
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    headless: true,
    viewport: { width: 1440, height: 940 },
  },
  webServer: {
    cwd: fileURLToPath(new URL("../../", import.meta.url)),
    command: `node node_modules/vite/bin/vite.js preview --outDir ../.local/perf-dist --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: true,
  },
});
