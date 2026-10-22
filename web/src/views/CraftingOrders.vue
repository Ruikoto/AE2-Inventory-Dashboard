<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { CraftingViewModel } from "../viewmodels/crafting";
import {
  orderStates,
  progressPercent,
  craftingError,
} from "../models/crafting";
import { quantity } from "../models/format";
import CraftingMaterialGrid from "./CraftingMaterialGrid.vue";
import ResourceIcon from "./ResourceIcon.vue";
const props = defineProps<{ vm: CraftingViewModel }>();
const {
  ordersOpen,
  history,
  orders,
  nextCursor,
  orderBusy,
  cancelling,
  orderError,
  orderCpus,
  selectedCpuId,
  selectedCpu,
  selectedOrder,
  unboundOrders,
  cpuStatus,
  statusStale,
  statusLoading,
  statusError,
  cpuProgress,
  eta,
} = props.vm;
const dialog = ref<HTMLDialogElement>();
const displayOrders = computed(() =>
  history.value ? orders.value : unboundOrders.value,
);
let opener: HTMLElement | null = null;
const cancelTarget = ref("");
watch(
  ordersOpen,
  (open) => {
    cancelTarget.value = "";
    if (open) {
      opener = document.activeElement as HTMLElement;
      dialog.value?.showModal();
    } else {
      dialog.value?.close();
      if (opener?.isConnected) opener.focus();
    }
  },
  { flush: "post" },
);
watch([history, selectedCpuId], () => {
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
    <div v-if="!history" class="cpu-workspace">
      <nav class="cpu-list" aria-label="合成 CPU 列表">
        <button
          v-for="cpu in orderCpus"
          :key="cpu.id"
          class="cpu-list-item"
          :aria-pressed="selectedCpuId === cpu.id"
          @click="vm.selectCpu(cpu.id)"
        >
          <span class="cpu-list-heading"
            ><strong>{{ cpu.name || "未命名 CPU" }}</strong
            ><i :class="{ active: cpu.busy }"></i
          ></span>
          <span class="cpu-list-output"
            ><ResourceIcon
              v-if="cpu.output"
              :row="cpu.output"
              :size="24"
            /><span>{{
              cpu.output?.name || (cpu.busy ? "合成中" : "空闲")
            }}</span></span
          >
          <small
            >{{ quantity(cpu.storage) }} B ·
            {{ cpu.coprocessors }} 协处理器</small
          >
        </button>
        <p v-if="!orderCpus.length" class="plan-empty">
          {{ orderBusy ? "正在读取 CPU…" : "网络中没有合成 CPU" }}
        </p>
      </nav>
      <section class="cpu-detail" aria-label="选中 CPU 状态">
        <article
          v-if="selectedCpu"
          :class="{ 'craft-order': !!selectedOrder }"
          :data-order-id="selectedOrder?.id"
        >
          <div class="order-heading">
            <strong>{{
              selectedCpu.output?.name || selectedCpu.name || "未命名 CPU"
            }}</strong
            ><span class="order-state">{{
              selectedCpu.busy ? "合成中" : "空闲"
            }}</span>
          </div>
          <div v-if="selectedCpu.output" class="order-quantity">
            <ResourceIcon :row="selectedCpu.output" :size="32" />{{
              quantity(selectedCpu.outputQuantity, selectedCpu.output.unit)
            }}
          </div>
          <div class="order-meta">
            <span>{{ selectedCpu.name || "未命名 CPU" }}</span
            ><span
              >{{ quantity(selectedCpu.storage) }} B ·
              {{ selectedCpu.coprocessors }} 协处理器</span
            >
          </div>
          <p
            v-if="selectedCpu.taskId && !selectedCpu.dashboardOrderId"
            class="order-note"
          >
            游戏内任务，只读
          </p>
          <p
            v-else-if="selectedCpu.taskId && !selectedCpu.canCancel"
            class="order-note"
          >
            任务属于其他网络，只读
          </p>
          <div
            v-if="selectedCpu.taskId && cpuProgress !== undefined"
            class="order-progress"
          >
            <progress
              :value="cpuProgress"
              max="100"
              aria-label="合成工作量进度"
            /><span>工作量 {{ cpuProgress.toFixed(1) }}%</span>
          </div>
          <p v-if="selectedCpu.taskId" class="cpu-eta" role="status">
            {{ eta }}
          </p>
          <p v-if="statusStale && cpuStatus" class="order-note">
            显示上次状态，正在等待新的采样
          </p>
          <p v-if="cpuStatus?.cantStoreItems" class="error-text craft-error">
            无法存回网络，请检查存储空间或存储权限。
          </p>
          <p v-if="statusError" class="error-text craft-error" role="status">
            {{ statusError }}
          </p>
          <CraftingMaterialGrid
            v-if="cpuStatus?.detailsAvailable"
            :key="selectedCpu.id + '/' + selectedCpu.taskId"
            :entries="cpuStatus.entries"
            mode="status"
            remote
            :offset="cpuStatus.offset"
            :total="cpuStatus.total"
            :loading="statusLoading"
            @window="vm.setCpuWindow"
          />
          <p
            v-else-if="cpuStatus && !cpuStatus.detailsAvailable"
            class="plan-empty"
          >
            材料明细超出显示上限，仍可查看工作量与预计时间。
          </p>
          <p v-else class="plan-empty">
            {{ statusLoading ? "正在读取材料…" : "暂无可用材料状态" }}
          </p>
          <div
            v-if="selectedOrder && selectedCpu.canCancel"
            class="order-actions"
          >
            <template v-if="cancelTarget === selectedOrder.id"
              ><span>确定取消此网络的订单？</span
              ><button
                :disabled="!!cancelling"
                @click="
                  vm.cancelOrder(selectedOrder);
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
              :disabled="!!cancelling"
              :aria-label="'取消订单 ' + selectedOrder.item.name"
              @click="cancelTarget = selectedOrder.id"
            >
              {{ cancelling === selectedOrder.id ? "取消中…" : "取消订单" }}
            </button>
          </div>
        </article>
        <p v-else class="plan-empty">从左侧选择 CPU 查看材料状态</p>
      </section>
    </div>
    <h3 v-if="!history && unboundOrders.length" class="unbound-heading">
      等待绑定 CPU 的订单
    </h3>
    <div class="orders-scroll" :aria-busy="orderBusy">
      <article
        v-for="order in displayOrders"
        :key="order.id"
        class="craft-order"
        :data-order-id="order.id"
      >
        <div class="order-heading">
          <strong class="history-item"
            ><ResourceIcon :row="order.item" />{{ order.item.name }}</strong
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
      <div v-if="history && !orders.length" class="plan-empty">
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
