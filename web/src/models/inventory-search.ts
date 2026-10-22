import { pinyin } from "pinyin-pro";

// Worker 跨采样复用名称索引；只保留字符串，不枚举多音字组合。
const names = new Map<string, string>();
let bytes = 0;
export function phoneticName(name: string): string {
  const cached = names.get(name);
  if (cached !== undefined) return cached;
  if (!/\p{Script=Han}/u.test(name)) return "";
  const parts = pinyin(name, {
    toneType: "none",
    type: "all",
    nonZh: "consecutive",
    v: true,
  });
  const full = parts
    .map((part) => (part.isZh ? part.result : part.origin))
    .join("");
  const initials = parts
    .map((part) => (part.isZh ? part.first : part.origin))
    .join("");
  const value = `${full} ${initials}`.toLocaleLowerCase().replace(/\s+/g, " ");
  const size = (name.length + value.length) * 2;
  while (names.size >= 100000 || bytes + size > 16 * 1024 * 1024) {
    const oldest = names.entries().next().value;
    if (!oldest) break;
    names.delete(oldest[0]);
    bytes -= (oldest[0].length + oldest[1].length) * 2;
  }
  names.set(name, value);
  bytes += size;
  return value;
}
