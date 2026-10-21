import { reactive, ref } from "vue";
import {
  applyTheme,
  loadTheme,
  presets,
  themeKey,
  validColors,
  type Colors,
  type ThemeMode,
} from "../models/theme";
const theme = reactive(loadTheme());
export function useTheme() {
  const open = ref(false),
    message = ref("");
  const draft = reactive({ ...theme.colors });
  function save() {
    applyTheme(theme);
    try {
      localStorage.setItem(themeKey(), JSON.stringify(theme));
    } catch {
      message.value = "主题仅在本次会话保留";
    }
  }
  function select(mode: ThemeMode) {
    theme.mode = mode;
    save();
  }
  function reset(base: "dark" | "light") {
    theme.base = base;
    Object.assign(theme.colors, presets[base]);
    Object.assign(draft, presets[base]);
    message.value = "";
    save();
  }
  function edit(key: keyof Colors, value: string) {
    draft[key] = value;
    if (!validColors(draft)) {
      message.value = "文字或强调色对比度不足，请调整配色";
      return;
    }
    Object.assign(theme.colors, draft);
    message.value = "";
    save();
  }
  return { theme, draft, open, message, select, reset, edit };
}
