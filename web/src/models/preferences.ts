export interface Preferences {
  favorites: string[];
  comparisons: string[];
  search: string;
  type: string;
  namespace: string;
  merged: boolean;
  onlyFavorites: boolean;
  zeros: boolean;
  range: number;
  step: number;
  sort: "quantity" | "name" | "delta" | "rate";
  buckets: boolean;
  ratio: number;
}
export const defaults = (): Preferences => ({
  favorites: [],
  comparisons: [],
  search: "",
  type: "all",
  namespace: "",
  merged: false,
  onlyFavorites: false,
  zeros: false,
  range: 3600,
  step: 0,
  sort: "quantity",
  buckets: false,
  ratio: 55,
});
export function preferenceKey(world: string, network: string): string {
  return `ae2dash:1:${location.origin}:${new URL("./", document.baseURI).pathname}:${world}:${network}`;
}
export function loadPreferences(key: string): Preferences {
  try {
    const input = JSON.parse(localStorage.getItem(key) ?? "{}");
    const output = { ...defaults(), ...input } as Preferences;
    output.favorites = Array.isArray(input.favorites)
      ? input.favorites
          .filter((x: unknown) => typeof x === "string")
          .slice(0, 10000)
      : [];
    output.comparisons = Array.isArray(input.comparisons)
      ? input.comparisons
          .filter((x: unknown) => typeof x === "string")
          .slice(0, 6)
      : [];
    return output;
  } catch {
    return defaults();
  }
}
