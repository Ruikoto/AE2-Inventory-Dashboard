import { describe, expect, it } from "vitest";
import { quantity, signed, duration } from "../src/models/format";
describe("exact inventory", () => {
  it("keeps integers above Number precision", () => {
    expect(quantity("9007199254740993")).toBe("9,007,199,254,740,993");
  });
  it("converts fluid without floating point rounding", () => {
    expect(quantity("9007199254740993001", "mB", true)).toBe(
      "9,007,199,254,740,993.001 B",
    );
  });
  it("formats missing and negative changes", () => {
    expect(signed(null)).toBe("—");
    expect(signed("-1")).toBe("-1");
    expect(duration(604800)).toBe("7d");
  });
});
