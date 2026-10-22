export interface Preferences {
  favorites: string[];
  comparisons: string[];
  search: string;
  type: string;
  availability: "all" | "stock" | "craftable";
  namespace: string;
  merged: boolean;
  onlyFavorites: boolean;
  excludeInfinite: boolean;
  inventoryView: "grid" | "list";
  zeros: boolean;
  range: number;
  step: number;
  sort: "quantity" | "name" | "delta" | "rate";
  buckets: boolean;
  ratio: number;
  mode: "quantity" | "rate" | "both";
  rateWindow: number;
  envelope: boolean;
}
export const defaults = (): Preferences => ({
  favorites: [],
  comparisons: [],
  search: "",
  type: "all",
  availability: "all",
  namespace: "",
  merged: false,
  onlyFavorites: false,
  excludeInfinite: false,
  inventoryView: "grid",
  zeros: false,
  range: 3600,
  step: 0,
  sort: "quantity",
  buckets: false,
  ratio: 55,
  mode: "both",
  rateWindow: 300000,
  envelope: false,
});
export function preferenceKey(world: string, network: string): string {
  return `ae2dash:1:${location.origin}:${new URL("./", document.baseURI).pathname}:${world}:${network}`;
}
export function loadPreferences(
  key: string,
  sessionValue?: string,
): Preferences {
  try {
    const input = JSON.parse(sessionValue ?? localStorage.getItem(key) ?? "{}");
    const output = defaults();
    for (const field of ["search", "type", "namespace"] as const)
      if (typeof input[field] === "string")
        output[field] = input[field].slice(0, 512);
    for (const field of [
      "merged",
      "onlyFavorites",
      "excludeInfinite",
      "zeros",
      "buckets",
      "envelope",
    ] as const)
      if (typeof input[field] === "boolean") output[field] = input[field];
    if (["quantity", "name", "delta", "rate"].includes(input.sort))
      output.sort = input.sort;
    if (["grid", "list"].includes(input.inventoryView))
      output.inventoryView = input.inventoryView;
    if (["all", "stock", "craftable"].includes(input.availability))
      output.availability = input.availability;
    if (["quantity", "rate", "both"].includes(input.mode))
      output.mode = input.mode;
    if ([60000, 300000, 900000].includes(input.rateWindow))
      output.rateWindow = input.rateWindow;
    if (
      Number.isFinite(input.range) &&
      input.range >= 60 &&
      input.range <= 2592000
    )
      output.range = input.range;
    if (Number.isFinite(input.step) && input.step >= 0)
      output.step = input.step;
    if (Number.isFinite(input.ratio))
      output.ratio = Math.max(40, Math.min(70, input.ratio));
    output.favorites = Array.isArray(input.favorites)
      ? input.favorites
          .filter((x: unknown) => typeof x === "string")
          .slice(0, 10000)
      : [];
    output.comparisons = Array.isArray(input.comparisons)
      ? input.comparisons
          .filter((x: unknown) => typeof x === "string")
          .slice(0, 12)
      : [];
    output.comparisons = output.comparisons.filter(
      (id, index, list) =>
        list.indexOf(id) === index &&
        list
          .slice(0, index)
          .filter(
            (other) => other.startsWith("group:") === id.startsWith("group:"),
          ).length < 6,
    );
    if (!["all", "item", "fluid"].includes(output.type)) output.type = "all";
    return output;
  } catch {
    return defaults();
  }
}
