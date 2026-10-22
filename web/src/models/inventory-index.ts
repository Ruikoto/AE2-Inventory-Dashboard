import type { Resource } from "./types";
import type { Preferences } from "./preferences";
import { decimalValue } from "./format";
import { phoneticName } from "./inventory-search";

export class InventoryIndex {
  readonly byId = new Map<string, Resource>();
  private keys = new Map<
    string,
    { text: string; phonetic: string; namespace: string; quantity: bigint }
  >();
  private collator = new Intl.Collator("zh-CN");
  constructor(rows: Resource[]) {
    for (const row of rows) {
      this.byId.set(row.id, row);
      this.keys.set(row.id, {
        text: `${row.name} ${row.registryId} ${row.id}`.toLocaleLowerCase(),
        phonetic: phoneticName(row.name).replace(/\s/g, ""),
        namespace: row.registryId.split(":")[0],
        quantity: BigInt(row.quantity),
      });
    }
  }
  filter(p: Preferences): Resource[] {
    const query = p.search.trim().toLocaleLowerCase();
    const phoneticQuery = query.replace(/\s/g, "");
    const favorites = new Map(p.favorites.map((id, i) => [id, i]));
    const sortedValues = new Map<string, bigint>();
    if (p.sort === "delta" || p.sort === "rate")
      for (const row of this.byId.values()) {
        const value = row[p.sort];
        if (value != null) sortedValues.set(row.id, decimalValue(value));
      }
    return [...this.byId.values()]
      .filter((row) => {
        const key = this.keys.get(row.id)!;
        return (
          row.id.startsWith("group:") === p.merged &&
          (!query ||
            key.text.includes(query) ||
            key.phonetic.includes(phoneticQuery)) &&
          (p.type === "all" ||
            (p.type === "other"
              ? !["item", "fluid"].includes(row.type)
              : row.type === p.type)) &&
          (!p.namespace || key.namespace === p.namespace) &&
          (!p.onlyFavorites || favorites.has(row.id)) &&
          (!p.excludeInfinite || !row.infinite) &&
          (p.availability !== "stock" || key.quantity > 0n) &&
          (p.availability !== "craftable" || row.craftable === true) &&
          (p.zeros || key.quantity !== 0n || row.craftable === true)
        );
      })
      .sort((a, b) => {
        const ai = favorites.get(a.id),
          bi = favorites.get(b.id);
        if (ai !== undefined || bi !== undefined)
          return ai === undefined ? 1 : bi === undefined ? -1 : ai - bi;
        if (p.sort === "name")
          return (
            this.collator.compare(a.name, b.name) || a.id.localeCompare(b.id)
          );
        if (p.sort === "quantity") {
          const av = this.keys.get(a.id)!.quantity,
            bv = this.keys.get(b.id)!.quantity;
          return av < bv ? 1 : av > bv ? -1 : a.id.localeCompare(b.id);
        }
        const av = sortedValues.get(a.id),
          bv = sortedValues.get(b.id);
        if (av == null || bv == null)
          return av == null ? (bv == null ? a.id.localeCompare(b.id) : 1) : -1;
        return av < bv ? 1 : av > bv ? -1 : a.id.localeCompare(b.id);
      });
  }
}
