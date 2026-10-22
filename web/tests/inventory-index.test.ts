import { describe, expect, it } from "vitest";
import { InventoryIndex } from "../src/models/inventory-index";
import { defaults } from "../src/models/preferences";
import type { Resource } from "../src/models/types";

const row = (
  id: string,
  quantity: string,
  rate: string | null = "0",
): Resource => ({
  id,
  registryId: `minecraft:${id}`,
  groupId: `group:item:minecraft:${id}`,
  name: id,
  quantity,
  rate,
  delta: rate,
  type: "item",
  unit: "个",
  amountPerUnit: 1,
  variantCount: 1,
});
describe("inventory index", () => {
  it("filters only proven infinite sources and keeps zero-stock craftables", () => {
    const infinite = {
      ...row("water", "2147486591840"),
      type: "fluid",
      unit: "mB",
      infinite: true,
    };
    const index = new InventoryIndex([
      infinite,
      row("huge", "9007199254740993"),
      { ...row("craft", "0"), craftable: true },
      row("zero", "0"),
    ]);
    expect(index.filter(defaults()).map((value) => value.id)).toEqual([
      "huge",
      "water",
      "craft",
    ]);
    expect(
      index
        .filter({ ...defaults(), excludeInfinite: true })
        .map((value) => value.id),
    ).toEqual(["huge", "craft"]);
    expect(
      index
        .filter({
          ...defaults(),
          excludeInfinite: true,
          onlyFavorites: true,
          favorites: ["water", "craft"],
        })
        .map((value) => value.id),
    ).toEqual(["craft"]);
    expect(
      index.filter({ ...defaults(), excludeInfinite: true, type: "fluid" }),
    ).toEqual([]);
    const grouped = new InventoryIndex([
      { ...row("group:item:test:one", "9007199254740993"), infinite: true },
    ]);
    expect(
      grouped.filter({ ...defaults(), merged: true, excludeInfinite: true }),
    ).toEqual([]);
  });
  it("sorts exact huge quantities, rates and unavailable statistics", () => {
    const index = new InventoryIndex([
      row("lower", "9007199254740992", "9007199254740992.999999"),
      row("upper", "9007199254740993", "9007199254740993.000001"),
      row("missing", "1", null),
    ]);
    expect(index.filter(defaults()).map((r) => r.id)).toEqual([
      "upper",
      "lower",
      "missing",
    ]);
    expect(
      index.filter({ ...defaults(), sort: "rate" }).map((r) => r.id),
    ).toEqual(["upper", "lower", "missing"]);
  });
  it("keeps favorite order and exact/group identity isolated", () => {
    const index = new InventoryIndex([
      row("one", "1"),
      row("two", "2"),
      row("group:item:minecraft:one", "3"),
    ]);
    expect(
      index
        .filter({ ...defaults(), favorites: ["one", "two"] })
        .map((r) => r.id),
    ).toEqual(["one", "two"]);
    expect(
      index.filter({ ...defaults(), merged: true }).map((r) => r.id),
    ).toEqual(["group:item:minecraft:one"]);
  });
  it("filters registry, namespace and zeros without dropping exact identity", () => {
    const index = new InventoryIndex([row("one", "1"), row("zero", "0")]);
    expect(
      index.filter({ ...defaults(), search: "MINECRAFT:ONE" }).map((r) => r.id),
    ).toEqual(["one"]);
    expect(index.filter({ ...defaults(), namespace: "create" })).toEqual([]);
    expect(index.filter({ ...defaults(), zeros: true })).toHaveLength(2);
  });
});
