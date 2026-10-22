import { describe, expect, it } from "vitest";
import {
  quantity,
  signed,
  duration,
  rate,
  inventoryQuantity,
  trend,
} from "../src/models/format";
import type { Resource } from "../src/models/types";
describe("exact inventory", () => {
  it("limits infinite presentation to the catalog and preserves exact statistics", () => {
    const row = {
      quantity: "2147486591840",
      unit: "mB",
      infinite: true,
      delta: "1",
    } as Resource;
    expect(inventoryQuantity(row)).toBe("无限");
    expect(inventoryQuantity(row, true)).toBe("无限");
    expect(quantity(row.quantity, row.unit)).toBe("2,147,486,591,840 mB");
    expect(inventoryQuantity({ ...row, infinite: false })).toBe(
      "2,147,486,591,840 mB",
    );
    expect(trend(row).symbol).toBe("↑");
    expect(trend({ ...row, delta: "-1" }).symbol).toBe("↓");
    expect(trend({ ...row, delta: "0" }).symbol).toBe("→");
    expect(trend({ ...row, delta: null }).label).toBe("暂无趋势");
  });
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
  it("converts interval changes and fractional rates to the selected fluid unit", () => {
    expect(signed("18000", "mB", true)).toBe("+18.000 B");
    expect(rate("300", "mB", true)).toBe("+0.3 B/min");
    expect(rate("-0.000001", "mB", true)).toBe("−0.000000001 B/min");
    expect(rate("9007199254740993001.000001", "mB", true)).toBe(
      "+9,007,199,254,740,993.001000001 B/min",
    );
    expect(rate("0.000000", "mB", true)).toBe("0 B/min");
  });
});
