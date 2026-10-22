import { expect, it } from "vitest";
import {
  progressPercent,
  estimatedRemaining,
  workPercent,
  type CraftingOrder,
} from "../src/models/crafting";
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

it("estimates AE work duration with exact integer arithmetic", () => {
  expect(estimatedRemaining("100", "0", "10000000000")).toBe("估算中");
  expect(estimatedRemaining("100", "25", "10000000000")).toBe(
    "预计剩余约 30 秒",
  );
  expect(estimatedRemaining("100", "100", "10000000000")).toBe("即将完成");
  expect(
    estimatedRemaining(
      "900719925474099300",
      "450359962737049650",
      "60000000000",
    ),
  ).toBe("预计剩余约 1 分钟 0 秒");
  expect(workPercent("900719925474099300", "450359962737049650")).toBe(50);
});
