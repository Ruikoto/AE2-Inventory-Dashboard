import { expect, it } from "vitest";
import { progressPercent, type CraftingOrder } from "../src/models/crafting";
it("computes work progress without rounding large integers through Number", () => {
  expect(
    progressPercent({
      total: "90071992547409930",
      progress: "45035996273704965",
    } as CraftingOrder),
  ).toBe(50);
  expect(
    progressPercent({ total: "0", progress: "0" } as CraftingOrder),
  ).toBeUndefined();
});
