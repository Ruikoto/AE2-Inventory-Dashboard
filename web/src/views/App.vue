<script setup lang="ts">
import { computed } from "vue";
import { useDashboard } from "../viewmodels/dashboard";
import {
  duration,
  timeRanges,
  steps,
  stateNames,
  quantity,
  signed,
  rate,
} from "../models/format";
import InventoryTable from "./InventoryTable.vue";
import TrendChart from "./TrendChart.vue";
const vm = useDashboard();
const {
  status,
  networks,
  networkId,
  currentNetwork,
  visibleRows,
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
</script>
<template>
  <main v-if="!status" class="loading">正在连接监测服务…</main>
  <main v-else-if="!status.authenticated" class="login-shell">
    <form class="login" @submit.prevent="vm.login">
      <span class="brand-mark">▤</span>
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
      <div class="brand">
        <span class="brand-mark">▤</span><span>AE 库存监测</span>
      </div>
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
      <button class="subtle" :disabled="busy" @click="vm.refresh">
        {{ busy ? "读取中" : "刷新" }}</button
      ><button class="subtle" @click="settings = !settings">设置</button>
    </header>
    <div v-if="error" role="alert" class="banner error-banner">
      {{ error }}<button @click="error = ''" aria-label="关闭">×</button>
    </div>
    <div v-if="inventory?.stale" class="banner">
      显示最后一次有效库存，曲线已在数据缺口处断开。
    </div>
    <div v-if="status.historyStatus?.startsWith('paused')" class="banner">
      历史记录暂停：{{ status.historyStatus }}。当前库存继续更新。
    </div>
    <section v-if="settings" class="settings-panel" aria-label="本地设置">
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
        status.assets?.status === "loaded"
          ? `已加载名称与图标包 · ${status.assets.entries} 项`
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
        <span aria-hidden="true">⌕</span
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
      ><select v-model="preferences.sort" aria-label="排序">
        <option value="quantity">库存降序</option>
        <option value="name">名称</option>
        <option value="delta">净变化降序</option>
        <option value="rate">净速率降序</option></select
      ><span class="resource-count"
        >{{ visibleRows.length.toLocaleString() }} 项</span
      >
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
      <div class="mode-switch">
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
          <strong>尚未发现 AE 网络</strong>
          <p>加载包含 ME 控制器的网络后自动开始记录</p>
        </div>
        <InventoryTable
          v-else
          :rows="visibleRows"
          :favorites="preferences.favorites"
          :comparisons="preferences.comparisons"
          :selected="selected"
          :buckets="preferences.buckets"
          @favorite="vm.toggleFavorite"
          @compare="vm.compare"
          @select="vm.select"
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
            <label class="check"
              ><input v-model="envelope" type="checkbox" />波动范围</label
            >
          </div>
          <div v-if="selectedRows.length === 1" class="resource-stats">
            <div>
              <span>当前库存</span
              ><strong>{{
                quantity(chosen.quantity, chosen.unit, preferences.buckets)
              }}</strong>
            </div>
            <div>
              <span>区间净变化</span
              ><strong>{{ signed(history?.series[0]?.delta) }}</strong>
            </div>
            <div>
              <span>区间净速率</span
              ><strong>{{ rate(history?.series[0]?.rate) }}</strong>
            </div>
          </div>
          <p
            v-if="history && history.series.some((s) => !s.complete)"
            class="coverage-note"
          >
            区间包含缺口或观测不足，完整区间统计暂不可用
          </p>
          <template v-if="history?.series.some((s) => s.points.length)"
            ><TrendChart
              v-for="unit in units"
              :key="unit"
              :history="history"
              :rows="selectedRows.filter((r) => r.unit === unit)"
              :unit="unit"
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
      ><span>净变化来自库存采样 · 表格显示相邻采样变化</span><span>只读</span>
    </footer>
  </main>
</template>
