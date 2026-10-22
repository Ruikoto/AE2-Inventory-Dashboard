<script setup lang="ts">
import { ref, watch } from "vue";
import type { CraftingViewModel } from "../viewmodels/crafting";
import {
  orderStates,
  progressPercent,
  craftingError,
} from "../models/crafting";
import { quantity } from "../models/format";
const props = defineProps<{ vm: CraftingViewModel }>();
const {
  ordersOpen,
  history,
  orders,
  nextCursor,
  orderBusy,
  cancelling,
  orderError,
} = props.vm;
const dialog = ref<HTMLDialogElement>();
const cancelTarget = ref("");
watch(
  ordersOpen,
  (open) => {
    cancelTarget.value = "";
    if (open) dialog.value?.showModal();
    else dialog.value?.close();
  },
  { flush: "post" },
);
watch(history, () => {
  cancelTarget.value = "";
});
</script>
<template>
  <dialog
    ref="dialog"
    class="crafting-dialog orders-dialog"
    aria-labelledby="orders-title"
    @cancel.prevent="vm.closeOrders"
    @click="$event.target === dialog && vm.closeOrders()"
  >
    <div class="dialog-heading">
      <div>
        <p class="dialog-context">当前网络共享</p>
        <h2 id="orders-title">合成订单</h2>
      </div>
      <button class="icon-button" aria-label="关闭订单" @click="vm.closeOrders">
        ×
      </button>
    </div>
    <div class="orders-toolbar">
      <div class="view-switch" aria-label="订单分类">
        <button :aria-pressed="!history" @click="history = false">进行中</button
        ><button :aria-pressed="history" @click="history = true">历史</button>
      </div>
      <span v-if="history">已结束订单保留 30 天</span
      ><button class="subtle" :disabled="orderBusy" @click="vm.loadOrders()">
        刷新订单
      </button>
    </div>
    <p v-if="orderError" role="alert" class="error-text craft-error">
      {{ orderError }}
    </p>
    <div class="orders-scroll" :aria-busy="orderBusy">
      <article
        v-for="order in orders"
        :key="order.id"
        class="craft-order"
        :data-order-id="order.id"
      >
        <div class="order-heading">
          <strong>{{ order.item.name }}</strong
          ><span
            class="order-state"
            :class="{
              positive: order.state === 'completed',
              negative: order.state === 'failed',
            }"
            >{{ orderStates[order.state] }}</span
          >
        </div>
        <div class="order-quantity">
          {{ quantity(order.quantity, order.item.unit) }}
        </div>
        <div class="order-meta">
          <time :datetime="new Date(order.createdAt).toISOString()">{{
            new Date(order.createdAt).toLocaleString()
          }}</time
          ><span>{{
            order.cpuName ||
            (order.state === "submitting" ? "正在分配 CPU" : "未指定 CPU")
          }}</span>
        </div>
        <div
          v-if="
            order.state === 'running' && progressPercent(order) !== undefined
          "
          class="order-progress"
        >
          <progress
            :value="progressPercent(order)"
            max="100"
            :aria-label="`${order.item.name} 合成工作量进度`"
          ></progress
          ><span>工作量 {{ progressPercent(order)?.toFixed(1) }}%</span>
        </div>
        <p v-if="order.state === 'recovering'" class="order-note">
          等待网络或 CPU 加载后确认进度，不会重复下单。
        </p>
        <p v-if="order.state === 'unknown'" class="order-note">
          无法确认任务是否完成，请检查游戏内 CPU。
        </p>
        <p v-if="order.error" class="error-text">
          {{ craftingError(order.error) }}
        </p>
        <div
          v-if="
            !['completed', 'cancelled', 'failed', 'unknown'].includes(
              order.state,
            )
          "
          class="order-actions"
        >
          <template v-if="cancelTarget === order.id"
            ><span>确定取消此网络的订单？</span
            ><button
              :disabled="!!cancelling"
              @click="
                vm.cancelOrder(order);
                cancelTarget = '';
              "
            >
              确定取消</button
            ><button class="subtle" @click="cancelTarget = ''">
              保留
            </button></template
          >
          <button
            v-else
            class="subtle"
            :disabled="!!cancelling || order.state === 'submitting'"
            :aria-label="`取消订单 ${order.item.name}`"
            @click="cancelTarget = order.id"
          >
            {{ cancelling === order.id ? "取消中…" : "取消订单" }}
          </button>
        </div>
      </article>
      <div v-if="!orders.length" class="plan-empty">
        {{
          orderBusy
            ? "正在读取订单…"
            : history
              ? "最近 30 天没有已结束的订单"
              : "当前没有进行中的订单"
        }}
      </div>
      <button
        v-if="nextCursor"
        class="load-orders"
        :disabled="orderBusy"
        @click="vm.loadOrders(true)"
      >
        加载更多订单
      </button>
    </div>
  </dialog>
</template>
