export type ThemeMode = "dark" | "light" | "custom";
export interface Colors {
  background: string;
  panel: string;
  text: string;
  accent: string;
}
export interface Theme {
  mode: ThemeMode;
  base: "dark" | "light";
  colors: Colors;
}
export const presets: Record<"dark" | "light", Colors> = {
  dark: {
    background: "#18212b",
    panel: "#202c38",
    text: "#e6edf3",
    accent: "#d8aa62",
  },
  light: {
    background: "#f4f6f8",
    panel: "#ffffff",
    text: "#243241",
    accent: "#8e631f",
  },
};
export const themeKey = () =>
  `ae2dash:theme:1:${location.origin}:${new URL("./", document.baseURI).pathname}`;
function luminance(hex: string) {
  const rgb = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
}
export function contrast(a: string, b: string) {
  const x = luminance(a),
    y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}
export function validColors(colors: Colors) {
  return (
    Object.values(colors).every((c) => /^#[\da-f]{6}$/i.test(c)) &&
    contrast(colors.text, colors.background) >= 4.5 &&
    contrast(colors.text, colors.panel) >= 4.5 &&
    contrast(colors.accent, colors.panel) >= 3
  );
}
export function loadTheme(): Theme {
  const fallback: Theme = {
    mode: "dark",
    base: "dark",
    colors: { ...presets.dark },
  };
  try {
    const t = JSON.parse(localStorage.getItem(themeKey()) ?? "null");
    if (
      t &&
      ["dark", "light", "custom"].includes(t.mode) &&
      ["dark", "light"].includes(t.base) &&
      t.colors &&
      validColors(t.colors)
    )
      return t;
  } catch {
    /* Storage is optional. */
  }
  return fallback;
}
function mix(a: string, b: string, weight: number) {
  return (
    "#" +
    [1, 3, 5]
      .map((i) =>
        Math.round(
          parseInt(a.slice(i, i + 2), 16) * (1 - weight) +
            parseInt(b.slice(i, i + 2), 16) * weight,
        )
          .toString(16)
          .padStart(2, "0"),
      )
      .join("")
  );
}
export function applyTheme(theme: Theme) {
  const base = theme.mode === "custom" ? theme.base : theme.mode;
  const c = theme.mode === "custom" ? theme.colors : presets[base];
  const light = base === "light";
  const tokens = {
    bg: c.background,
    panel: c.panel,
    text: c.text,
    accent: c.accent,
    muted: mix(c.panel, c.text, 0.74),
    line: mix(c.panel, c.text, 0.23),
    divider: mix(c.panel, c.text, 0.13),
    hover: mix(c.panel, c.accent, 0.09),
    selected: mix(c.panel, c.accent, 0.14),
    button: mix(c.panel, c.text, 0.06),
    focus: light ? "#226883" : "#77b6cf",
    positive: light ? "#397047" : "#a6bc8a",
    negative: light ? "#ac4639" : "#db9885",
    "accent-text":
      contrast(c.accent, "#ffffff") > contrast(c.accent, "#18212b")
        ? "#ffffff"
        : "#18212b",
  };
  for (const [key, value] of Object.entries(tokens))
    document.documentElement.style.setProperty(`--${key}`, value);
  document.documentElement.style.colorScheme = base;
  document.documentElement.dataset.theme = theme.mode;
  document.documentElement.dataset.base = base;
}
