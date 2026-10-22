<script setup lang="ts">
import { ref, watch } from "vue";
import type { CraftingViewModel } from "../viewmodels/crafting";
import { quantity } from "../models/format";
const props = defineProps<{ vm: CraftingViewModel }>();
const {
  dialogOpen,
  variants,
  resourceId,
  item,
  amount,
  plan,
  cpuId,
  cpus,
  busy,
  submitting,
  error,
  enabled,
  validAmount,
  canSubmit,
  pendingRequest,
} = props.vm;
const dialog = ref<HTMLDialogElement>();
watch(
  dialogOpen,
  (open) => {
    if (open) dialog.value?.showModal();
    else dialog.value?.close();
  },
  { flush: "post" },
);
</script>
<template>
  <dialog
    ref="dialog"
    class="crafting-dialog"
    aria-labelledby="craft-title"
    @cancel.prevent="vm.closeDialog"
    @click="$event.target === dialog && vm.closeDialog()"
  >
    <div class="dialog-heading">
      <div>
        <p class="dialog-context">自动合成</p>
        <h2 id="craft-title">{{ item?.name || "选择精确变体" }}</h2>
      </div>
      <button class="icon-button" aria-label="关闭下单" @click="vm.closeDialog">
        ×
      </button>
    </div>
    <form @submit.prevent="vm.preview">
      <div class="craft-inputs">
        <label v-if="variants.length > 1"
          >精确变体<select
            v-model="resourceId"
            :disabled="submitting || !!pendingRequest"
            aria-label="合成变体"
          >
            <option v-for="row in variants" :key="row.id" :value="row.id">
              {{ row.name }} · {{ row.id.slice(0, 8) }}
            </option>
          </select></label
        >
        <label
          >数量（{{ item?.unit || "个" }}）<input
            v-model="amount"
            inputmode="numeric"
            pattern="[1-9][0-9]*"
            maxlength="19"
            autofocus
            :disabled="submitting || !!pendingRequest"
            aria-label="合成数量"
        /></label>
        <button
          type="submit"
          class="subtle"
          :disabled="
            !enabled ||
            !validAmount ||
            !resourceId ||
            busy ||
            submitting ||
            !!pendingRequest
          "
        >
          {{ busy ? "读取中…" : plan ? "重新预览" : "预览合成" }}
        </button>
      </div>
      <p v-if="!validAmount" class="error-text">
        请输入 1 至 9,223,372,036,854,775,807 之间的整数
      </p>
    </form>
    <p v-if="!enabled" class="banner">
      服务器已关闭新订单，已有订单仍可查看与取消。
    </p>
    <p v-if="error" role="alert" class="error-text craft-error">{{ error }}</p>
    <div v-if="pendingRequest" class="banner pending-order">
      提交结果尚未确认。<button :disabled="submitting" @click="vm.reconcile">
        查询订单结果
      </button>
    </div>
    <div v-if="plan" class="plan-preview">
      <div v-if="plan.state === 'calculating'" class="plan-empty" role="status">
        正在计算材料与合成空间…
      </div>
      <template v-else>
        <div class="preview-heading">
          <h3>材料预览</h3>
          <span>合成空间 {{ quantity(plan.bytes || "0") }} B</span>
        </div>
        <div class="materials-scroll">
          <table class="material-table">
            <thead>
              <tr>
                <th>材料</th>
                <th>已有</th>
                <th>合成</th>
                <th>缺少</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="material in plan.materials" :key="material.item.id">
                <th scope="row" :title="material.item.registryId">
                  {{ material.item.name
                  }}<small>{{ material.item.unit }}</small>
                </th>
                <td>{{ quantity(material.stored) }}</td>
                <td>{{ quantity(material.craft) }}</td>
                <td :class="{ negative: BigInt(material.missing) > 0n }">
                  {{ quantity(material.missing) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="plan.state === 'missing'" class="error-text craft-error">
          材料不足，补齐后请重新预览。
        </p>
        <p
          v-else-if="['failed', 'cancelled', 'expired'].includes(plan.state)"
          class="error-text craft-error"
        >
          此预览不可提交，请重新计算。
        </p>
        <label v-if="plan.state === 'ready'" class="cpu-picker"
          >合成 CPU
          <select
            v-model="cpuId"
            aria-label="合成 CPU"
            :disabled="submitting || !!pendingRequest"
          >
            <option value="">自动选择合适的 CPU</option>
            <option
              v-for="cpu in cpus"
              :key="cpu.id"
              :value="cpu.id"
              :disabled="
                !cpu.selectable ||
                cpu.busy ||
                BigInt(cpu.storage) < BigInt(plan.bytes)
              "
            >
              {{ cpu.name || "未命名 CPU" }} · {{ quantity(cpu.storage) }} B{{
                cpu.busy
                  ? " · 占用中"
                  : !cpu.selectable
                    ? " · 不可用"
                    : BigInt(cpu.storage) < BigInt(plan.bytes)
                      ? " · 空间不足"
                      : ""
              }}
            </option>
          </select>
        </label>
      </template>
    </div>
    <div v-else-if="!busy" class="plan-empty">
      预览会列出材料需求，确认后才开始合成。
    </div>
    <div class="dialog-actions">
      <span v-if="plan?.state === 'ready'">{{
        quantity(plan.quantity, plan.item.unit)
      }}</span
      ><button class="subtle" @click="vm.closeDialog">关闭</button
      ><button class="primary" :disabled="!canSubmit" @click="vm.submit">
        {{ submitting ? "提交中…" : "确认下单" }}
      </button>
    </div>
  </dialog>
</template>
