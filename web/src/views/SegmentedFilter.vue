<script setup lang="ts">
const props = defineProps<{
  modelValue: string;
  label: string;
  options: { value: string; label: string; path: string }[];
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
function move(event: KeyboardEvent, index: number) {
  let next: number;
  if (event.key === "ArrowRight" || event.key === "ArrowDown")
    next = (index + 1) % props.options.length;
  else if (event.key === "ArrowLeft" || event.key === "ArrowUp")
    next = (index + props.options.length - 1) % props.options.length;
  else if (event.key === "Home") next = 0;
  else if (event.key === "End") next = props.options.length - 1;
  else return;
  event.preventDefault();
  emit("update:modelValue", props.options[next].value);
  (event.currentTarget as HTMLElement).parentElement
    ?.querySelectorAll<HTMLButtonElement>("button")
    [next].focus();
}
</script>
<template>
  <div class="segmented-filter" role="radiogroup" :aria-label="label">
    <button
      v-for="(option, index) in options"
      :key="option.value"
      type="button"
      role="radio"
      :aria-label="option.label"
      :aria-checked="modelValue === option.value"
      :tabindex="modelValue === option.value ? 0 : -1"
      @click="emit('update:modelValue', option.value)"
      @keydown="move($event, index)"
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.7"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path :d="option.path" />
      </svg>
      <span class="control-tooltip" role="tooltip">{{ option.label }}</span>
    </button>
  </div>
</template>
