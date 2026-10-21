import { afterEach, expect, it, vi } from "vitest";
import {
  defaults,
  loadPreferences,
  preferenceKey,
} from "../src/models/preferences";
import { decimalOrder } from "../src/models/format";
afterEach(() => vi.unstubAllGlobals());
it("recovers corrupted preferences and rejects wrong field types", () => {
  vi.stubGlobal("localStorage", {
    getItem: () =>
      '{"search":1,"ratio":100,"favorites":[null,"one"],"type":"bad"}',
  });
  const p = loadPreferences("key");
  expect(p.search).toBe("");
  expect(p.ratio).toBe(70);
  expect(p.favorites).toEqual(["one"]);
  expect(p.type).toBe("all");
  vi.stubGlobal("localStorage", { getItem: () => "{broken" });
  expect(loadPreferences("key")).toEqual(defaults());
});
it("isolates world and network preferences", () => {
  vi.stubGlobal("location", { origin: "https://ae.example" });
  vi.stubGlobal("document", { baseURI: "https://ae.example/" });
  expect(preferenceKey("world1", "network")).not.toBe(
    preferenceKey("world2", "network"),
  );
  expect(preferenceKey("world1", "a")).not.toBe(preferenceKey("world1", "b"));
});
it("orders rates beyond Number precision exactly", () => {
  expect(
    decimalOrder("9007199254740993.000001", "9007199254740993.000000"),
  ).toBe(1);
  expect(decimalOrder("-0.000001", "0")).toBe(-1);
});
