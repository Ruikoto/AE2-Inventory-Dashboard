import { afterEach, expect, it, vi } from "vitest";
import { contrast, loadTheme, presets, validColors } from "../src/models/theme";
afterEach(() => vi.unstubAllGlobals());
it("both presets meet text and action contrast requirements", () => {
  expect(validColors(presets.dark)).toBe(true);
  expect(validColors(presets.light)).toBe(true);
  expect(contrast("#000000", "#ffffff")).toBe(21);
  expect(validColors({ ...presets.light, text: presets.light.panel })).toBe(
    false,
  );
});
it("recovers from malformed or unavailable theme storage", () => {
  vi.stubGlobal("location", { origin: "https://ae.example" });
  vi.stubGlobal("document", { baseURI: "https://ae.example/" });
  vi.stubGlobal("localStorage", { getItem: () => "{broken" });
  expect(loadTheme().mode).toBe("dark");
  vi.stubGlobal("localStorage", {
    getItem: () => {
      throw new Error("unavailable");
    },
  });
  expect(loadTheme().colors).toEqual(presets.dark);
});
