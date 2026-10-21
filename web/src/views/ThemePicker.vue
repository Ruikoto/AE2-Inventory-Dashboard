<script setup lang="ts">
import { useTheme } from "../viewmodels/theme";
import type { Colors, ThemeMode } from "../models/theme";
const vm = useTheme();
const modes: { id: ThemeMode; label: string }[] = [
  { id: "dark", label: "深色" },
  { id: "light", label: "浅色" },
  { id: "custom", label: "自定义" },
];
const fields: { id: keyof Colors; label: string }[] = [
  { id: "background", label: "背景" },
  { id: "panel", label: "面板" },
  { id: "text", label: "文字" },
  { id: "accent", label: "强调色" },
];
</script>
<template>
  <div class="theme-picker" @keydown.esc="vm.open.value = false">
    <button
      class="subtle"
      aria-label="切换主题"
      :aria-expanded="vm.open.value"
      @click="vm.open.value = !vm.open.value"
    >
      <svg
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="8" />
        <path d="M12 4v16a8 8 0 0 0 0-16" fill="currentColor" /></svg
      ><span>主题</span>
    </button>
    <div v-if="vm.open.value" class="theme-popover" aria-label="主题设置">
      <div class="theme-options">
        <button
          v-for="mode in modes"
          :key="mode.id"
          :aria-pressed="vm.theme.mode === mode.id"
          @click="vm.select(mode.id)"
        >
          {{ mode.label }}
        </button>
      </div>
      <template v-if="vm.theme.mode === 'custom'">
        <label
          >基础方案<select
            :value="vm.theme.base"
            @change="
              vm.reset(
                ($event.target as HTMLSelectElement).value as 'dark' | 'light',
              )
            "
          >
            <option value="dark">深色</option>
            <option value="light">浅色</option>
          </select></label
        >
        <label v-for="field in fields" :key="field.id"
          >{{ field.label
          }}<input
            type="color"
            :aria-label="`${field.label}颜色`"
            :value="vm.draft[field.id]"
            @input="
              vm.edit(field.id, ($event.target as HTMLInputElement).value)
            "
        /></label>
        <button class="subtle" @click="vm.reset(vm.theme.base)">
          恢复基础配色
        </button>
      </template>
      <p v-if="vm.message.value" role="status">{{ vm.message.value }}</p>
      <button class="subtle" @click="vm.open.value = false">完成</button>
    </div>
  </div>
</template>
