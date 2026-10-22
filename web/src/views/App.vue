<script setup lang="ts">
import { computed } from "vue";
import { useDashboard } from "../viewmodels/dashboard";
import { useCrafting } from "../viewmodels/crafting";
import {
  historyReason,
  duration,
  timeRanges,
  steps,
  stateNames,
  quantity,
  signed,
  rate,
  statisticsLabel,
} from "../models/format";
import ThemePicker from "./ThemePicker.vue";
import BrandMark from "./BrandMark.vue";
import InventoryTable from "./InventoryTable.vue";
import InventoryGrid from "./InventoryGrid.vue";
import CraftingDialog from "./CraftingDialog.vue";
import CraftingOrders from "./CraftingOrders.vue";
import TrendChart from "./TrendChart.vue";
const vm = useDashboard();
const crafting = useCrafting(vm.status, vm.networkId);
const {
  status,
  networks,
  networkId,
  currentNetwork,
  visibleRows,
  totalRows,
  windowStart,
  resetScroll,
  showEmptyNetworks,
  hiddenNetworks,
  statisticsReady,
  namespaces,
  history,
  events,
  inventory,
  preferences,
  selected,
  selectedRows,
  settings,
  mode,
  envelope,
  error,
  busy,
  mobileDetail,
  storageMessage,
  aliases,
  customFrom,
  customTo,
  password,
} = vm;
const units = computed(() => [
  ...new Set(selectedRows.value.map((row) => row.unit)),
]);
const chosen = computed(() => selectedRows.value[0]);
const chosenSeries = computed(() =>
  history.value?.series.find((s) => s.id === chosen.value?.id),
);
const hasEnvelope = computed(
  () =>
    history.value?.series.some((s) => s.points.some((p) => p.min !== p.max)) ??
    false,
);
const actualRateWindow = computed(() =>
  Math.max(
    preferences.rateWindow,
    ...(history.value?.series ?? []).flatMap((s) =>
      s.points.map((p) => p.rateWindowMs ?? 0),
    ),
  ),
);
const chartGroups = computed(() =>
  units.value.map((unit) => ({
    unit,
    rows: selectedRows.value.filter((r) => r.unit === unit),
  })),
);
</script>
<template>
  <main v-if="!status" class="loading">
    <p>{{ error || "正在连接监测服务…" }}</p>
    <button v-if="error" @click="vm.refresh">重新连接</button>
  </main>
  <main v-else-if="!status.authenticated" class="login-shell">
    <div class="login-theme"><ThemePicker /></div>
    <form class="login" @submit.prevent="vm.login">
      <BrandMark />
      <h1>AE 库存监测</h1>
      <label for="password">访问密码</label
      ><input
        id="password"
        v-model="password"
        type="password"
        autocomplete="current-password"
        autofocus
        required
      />
      <p v-if="error" role="alert" class="error-text">{{ error }}</p>
      <button class="primary" type="submit">进入</button>
    </form>
  </main>
  <main
    v-else
    class="workspace"
    :class="{ 'show-detail': mobileDetail }"
    :style="{ '--table-ratio': `${preferences.ratio}%` }"
  >
    <header class="app-header">
      <div class="brand"><BrandMark /><span>AE 库存监测</span></div>
      <div class="network-picker">
        <label for="network">网络</label
        ><select id="network" v-model="networkId">
          <option v-if="!networks.length" value="">暂无已发现网络</option>
          <option
            v-for="network in networks"
            :key="network.id"
            :value="network.id"
          >
            {{ aliases[network.id] || network.label }}
          </option>
        </select>
      </div>
      <div class="connection">
        <i :class="{ online: currentNetwork?.status === 'online' }"></i
        ><span>{{ stateNames[currentNetwork?.status ?? "unloaded"] }}</span
        ><time
          v-if="inventory?.timestamp"
          :title="new Date(inventory.timestamp).toLocaleString()"
          >{{ new Date(inventory.timestamp).toLocaleTimeString() }}</time
        >
      </div>
      <ThemePicker />
      <button
        class="subtle orders-entry"
        :disabled="!networkId"
        @click="crafting.showOrders"
      >
        合成订单
      </button>
      <button class="subtle" :disabled="busy" @click="vm.refresh">
        {{ busy ? "读取中" : "刷新" }}</button
      ><button class="subtle" @click="settings = !settings">设置</button>
    </header>
    <div v-if="error" role="alert" class="banner error-banner">
      {{ error }}<button @click="error = ''" aria-label="关闭">×</button>
    </div>
    <div v-if="crafting.notice.value" class="banner" role="status">
      {{ crafting.notice.value
      }}<button aria-label="关闭订单提示" @click="crafting.notice.value = ''">
        ×
      </button>
    </div>
    <div v-if="crafting.pendingRequest.value" class="banner">
      有一笔提交尚未确认。<button
        :disabled="crafting.submitting.value"
        @click="crafting.reconcile"
      >
        查询订单结果</button
      ><span v-if="crafting.error.value">{{ crafting.error.value }}</span>
    </div>
    <div v-if="inventory?.stale" class="banner">
      显示最后一次有效库存，曲线已在数据缺口处断开。
    </div>
    <div v-if="status.historyStatus?.startsWith('paused')" class="banner">
      历史记录暂停：{{
        historyReason(status.historyStatus)
      }}。当前库存继续更新。
    </div>
    <section v-if="settings" class="settings-panel" aria-label="本地设置">
      <label class="check"
        ><input v-model="showEmptyNetworks" type="checkbox" />显示空网络</label
      >
      <label
        >网络别名<input
          :value="aliases[networkId] ?? ''"
          maxlength="100"
          placeholder="仅保存在此浏览器"
          @change="
            vm.alias(($event.target as HTMLInputElement).value)
          " /></label
      ><label class="check"
        ><input
          v-model="preferences.zeros"
          type="checkbox"
        />显示零库存历史条目</label
      ><label class="check"
        ><input v-model="preferences.buckets" type="checkbox" />流体用 B
        显示</label
      ><label
        >列表宽度<input
          v-model.number="preferences.ratio"
          type="range"
          min="40"
          max="70" /></label
      ><span>{{
        ["loaded", "partial"].includes(status.assets?.status ?? "")
          ? `已加载名称与图标包 · ${status.assets?.entries ?? 0} 项`
          : "未加载名称与图标包"
      }}</span
      ><span
        >历史 {{ ((status.historyBytes ?? 0) / 1048576).toFixed(1) }} MiB</span
      ><button
        v-if="status.authMode === 'password'"
        class="subtle"
        @click="vm.logout"
      >
        退出
      </button>
      <p v-if="storageMessage" class="storage-message">{{ storageMessage }}</p>
    </section>
    <section class="filter-bar" aria-label="库存筛选">
      <div class="search">
        <svg
          aria-hidden="true"
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
        >
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 5 5" /></svg
        ><input
          v-model="preferences.search"
          aria-label="搜索物品"
          placeholder="搜索名称或注册 ID"
        />
      </div>
      <select v-model="preferences.type" aria-label="资源类型">
        <option value="all">全部类型</option>
        <option value="item">物品</option>
        <option value="fluid">流体</option>
        <option value="other">其他</option></select
      ><select v-model="preferences.namespace" aria-label="Mod 筛选">
        <option value="">全部 Mod</option>
        <option v-for="ns in namespaces" :key="ns">{{ ns }}</option></select
      ><label class="check"
        ><input v-model="preferences.merged" type="checkbox" />合并 NBT</label
      ><label class="check"
        ><input
          v-model="preferences.onlyFavorites"
          type="checkbox"
        />仅收藏</label
      ><label class="check"
        ><input
          v-model="preferences.excludeInfinite"
          type="checkbox"
        />排除无限项</label
      >
      <select v-model="preferences.sort" aria-label="排序">
        <option value="quantity">库存降序</option>
        <option value="name">名称</option>
        <option value="delta">净变化降序</option>
        <option value="rate">净速率降序</option></select
      ><span class="resource-count">{{ totalRows.toLocaleString() }} 项</span>
      <div class="view-switch" aria-label="库存视图">
        <button
          :aria-pressed="preferences.inventoryView === 'grid'"
          @click="preferences.inventoryView = 'grid'"
        >
          网格</button
        ><button
          :aria-pressed="preferences.inventoryView === 'list'"
          @click="preferences.inventoryView = 'list'"
        >
          列表
        </button>
      </div>
    </section>
    <section class="time-bar">
      <label
        >范围<select v-model.number="preferences.range" aria-label="时间范围">
          <option v-for="range in timeRanges" :key="range" :value="range">
            {{ duration(range) }}
          </option>
        </select></label
      >
      <details class="custom-range">
        <summary>自定义</summary>
        <div>
          <label>开始<input v-model="customFrom" type="datetime-local" /></label
          ><label>结束<input v-model="customTo" type="datetime-local" /></label
          ><button
            @click="
              customFrom = '';
              customTo = '';
            "
          >
            恢复最近范围
          </button>
        </div>
      </details>
      <label
        >粒度<select v-model.number="preferences.step" aria-label="数据粒度">
          <option :value="0">自动</option>
          <option
            v-for="step in steps"
            :key="step"
            :value="step"
            :disabled="vm.stepDisabled(step)"
          >
            {{ duration(step) }}
          </option>
        </select></label
      ><span v-if="history" class="granularity"
        >实际 {{ duration(history.step / 1000)
        }}<template v-if="history.truncated"> · 部分历史不可用</template></span
      >
      <label v-if="mode !== 'quantity'"
        >速率平滑<select
          v-model.number="preferences.rateWindow"
          aria-label="速率平滑窗口"
        >
          <option :value="60000">1m</option>
          <option :value="300000">5m</option>
          <option :value="900000">15m</option>
        </select></label
      >
      <div class="mode-switch">
        <button :class="{ active: mode === 'both' }" @click="mode = 'both'">
          数量＋速率
        </button>
        <button
          :class="{ active: mode === 'quantity' }"
          @click="mode = 'quantity'"
        >
          数量</button
        ><button :class="{ active: mode === 'rate' }" @click="mode = 'rate'">
          净速率
        </button>
      </div>
    </section>
    <div class="panels">
      <section class="inventory-panel">
        <div v-if="!networks.length" class="empty">
          <strong>{{
            hiddenNetworks ? "暂无非空网络" : "尚未发现 AE 网络"
          }}</strong>
          <p>
            {{
              hiddenNetworks
                ? `已隐藏 ${hiddenNetworks} 个空网络`
                : "加载包含 ME 控制器的网络后自动开始记录"
            }}
          </p>
          <button v-if="hiddenNetworks" @click="showEmptyNetworks = true">
            显示空网络
          </button>
        </div>
        <component
          v-else
          :is="
            preferences.inventoryView === 'grid'
              ? InventoryGrid
              : InventoryTable
          "
          :rows="visibleRows"
          :total="totalRows"
          :start="windowStart"
          :reset-scroll="resetScroll"
          @window="vm.setWindow"
          :favorites="preferences.favorites"
          :comparisons="preferences.comparisons"
          :selected="selected"
          :buckets="preferences.buckets"
          :can-craft="
            !!crafting.enabled.value &&
            currentNetwork?.status === 'online' &&
            !inventory?.stale
          "
          :initial-index="vm.viewportIndex.value"
          @favorite="vm.toggleFavorite"
          @compare="vm.compare"
          @select="vm.select"
          @craft="crafting.open"
        />
      </section>
      <section class="detail-panel">
        <button class="mobile-back subtle" @click="mobileDetail = false">
          ‹ 返回库存</button
        ><template v-if="chosen"
          ><div class="detail-heading">
            <div>
              <h1>
                {{
                  selectedRows.length > 1
                    ? `${selectedRows.length} 项对比`
                    : chosen.name
                }}
              </h1>
              <p>
                {{
                  selectedRows.length > 1
                    ? "同一网络 · 不同单位分别显示"
                    : chosen.registryId
                }}
              </p>
            </div>
            <label
              class="check"
              :title="
                mode === 'rate'
                  ? '波动范围仅适用于数量'
                  : !hasEnvelope
                    ? '当前粒度下无额外波动范围'
                    : '显示每个统计桶内的最低与最高库存'
              "
              ><input
                v-model="envelope"
                type="checkbox"
                :disabled="mode === 'rate' || !hasEnvelope"
              />波动范围</label
            >
          </div>
          <div v-if="selectedRows.length === 1" class="resource-stats">
            <div>
              <span>{{ inventory?.stale ? "最后库存" : "当前库存" }}</span
              ><strong>{{
                quantity(chosen.quantity, chosen.unit, preferences.buckets)
              }}</strong>
            </div>
            <div>
              <span>区间净变化</span
              ><strong>{{
                signed(chosenSeries?.delta, chosen.unit, preferences.buckets)
              }}</strong>
            </div>
            <div>
              <span>平均净速率</span
              ><strong>{{
                rate(chosenSeries?.rate, chosen.unit, preferences.buckets)
              }}</strong>
            </div>
          </div>
          <p
            v-if="history && history.series.some((s) => !s.complete)"
            class="coverage-note"
          >
            {{ chosenSeries ? statisticsLabel(chosenSeries) : "等待历史统计" }}
          </p>
          <p
            v-if="
              mode !== 'quantity' && actualRateWindow > preferences.rateWindow
            "
            class="coverage-note"
          >
            历史精度限制：实际平滑窗口 {{ duration(actualRateWindow / 1000) }}
          </p>
          <template v-if="history?.series.some((s) => s.points.length)"
            ><TrendChart
              v-for="group in chartGroups"
              :key="group.unit"
              :history="history"
              :rows="group.rows"
              :unit="group.unit"
              :buckets="preferences.buckets"
              :mode="mode"
              :envelope="envelope"
          /></template>
          <div v-else class="empty chart-empty">
            <strong>等待历史样本</strong>
            <p>首次采集后开始记录，至少两次有效采样才能计算速率</p>
          </div>
          <div class="comparison-list">
            <button
              v-for="row in selectedRows"
              :key="row.id"
              @click="vm.compare(row.id)"
              :title="`切换对比 ${row.name}`"
            >
              {{ row.name }}
              <span>{{
                preferences.comparisons.includes(row.id) ? "×" : "+"
              }}</span>
            </button>
          </div></template
        >
        <div v-else class="empty chart-empty">
          <strong>选择资源查看趋势</strong>
          <p>勾选列表右侧复选框可对比最多 6 项</p>
        </div>
        <div
          v-if="preferences.merged && selectedRows.length === 1"
          class="variant-panel"
        >
          <button class="subtle" @click="vm.expandVariants">
            {{ vm.variantsOpen.value ? "收起变体" : "查看精确变体" }}
          </button>
          <div v-if="vm.variantsOpen.value">
            <div
              v-for="row in vm.variants.value"
              :key="row.id"
              class="variant-row"
            >
              <span
                >{{ row.name }} <small>{{ row.id.slice(0, 8) }}</small></span
              >
              <span>{{
                quantity(row.quantity, row.unit, preferences.buckets)
              }}</span>
            </div>
          </div>
        </div>
        <details v-if="events.length" class="network-events">
          <summary>
            网络事件 <span>{{ events.length }}</span>
          </summary>
          <div v-for="(event, index) in events" :key="index">
            <time>{{ new Date(event.timestamp).toLocaleString() }}</time
            ><span>{{
              stateNames[event.type] ??
              { discovered: "发现网络", topology: "网络拓扑变化" }[
                event.type
              ] ??
              event.type
            }}</span>
          </div>
        </details>
      </section>
    </div>
    <footer>
      <span>{{ currentNetwork?.label ?? "等待网络" }}</span
      ><span>{{
        statisticsReady
          ? "净变化按所选范围计算 · * 最近连续段"
          : error
            ? "区间统计暂不可用"
            : "正在计算所选范围的统计…"
      }}</span
      ><span>{{
        status.craftingEnabled ? "合成下单已开放" : "新订单已关闭"
      }}</span>
    </footer>
    <CraftingDialog :vm="crafting" />
    <CraftingOrders :vm="crafting" />
  </main>
</template>
