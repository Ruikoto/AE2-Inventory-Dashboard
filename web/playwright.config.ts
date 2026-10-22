import { defineConfig } from "@playwright/test";
const port = Number(process.env.AE2_UI_TEST_PORT ?? 4173);
export default defineConfig({
  testDir: "./tests/browser",
  timeout: 30000,
  use: { baseURL: `http://127.0.0.1:${port}`, headless: true },
  webServer: {
    command: `npm run dev -- --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: true,
  },
});
