# 协议 v1

同源 HTTP，API 前缀 /api/v1。时间是 UTC Unix 毫秒，数量/差值/速率为十进制字符串。错误结构 `{"error":"CODE"}`。监测接口只接受 GET/HEAD；会话及下述合成路由按明确方法开放写入。

| 路径 | 参数和结果 |
|---|---|
| status | 认证模式/状态；认证后有 worldId、历史状态/占用/丢样、资源包状态及 craftingEnabled/craftingStatus |
| session | POST JSON password 登录，要求 Origin 同源；DELETE 退出，HttpOnly/SameSite Cookie |
| networks | 网络 id、label、status、lastSeen、anchors，含 worldId；nonZeroResources/craftableResources/observedAt 为最后完整观测的非零/可合成精确资源数及时间，未知为缺省或 null |
| networks/{uuid}/inventory | merged、zeros、offset、limit（至多 2000）、可选 group；含 available、timestamp、version、stale、windowMs、rows、total |
| networks/{uuid}/statistics | merged、from/to、offset、limit（至多 2000）、可选 version；返回 worldId/networkId/version/total/rows，分页版本变化或查询与写入维护重叠时 409 SNAPSHOT_CHANGED |
| networks/{uuid}/history | ids（至多六项）、from/to（毫秒）、step（毫秒；0 自动）、rateWindowMs（60000/300000/900000，默认 300000）；每条至多 1200 桶 |
| networks/{uuid}/events | 最近至多一百条状态或拓扑事件 |
| resources | offset/limit 分页资源，无 identity/NBT |
| assets/manifest | missing/invalid/loaded 及覆盖信息；无包不是错误 |
| assets/{sha256}.png | 已校验图集，受认证 |

库存行包含 id、groupId、type、registryId、name、unit、amountPerUnit、quantity、delta、rate、variantCount、craftable、infinite 和可选 icon。可合成零库存默认进入目录，普通历史零库存受 zeros 控制；合并行的能力标记对精确成员取或。infinite 来自已确认的无限单元，不按数量阈值判断；quantity 始终保留真实数值。分页必须核对同一 version；变化则重新读取。库存端点的旧 delta/rate 字段仍为相邻有效采样，windowMs 表示时间，以兼容既有调用。新版网页不使用它们显示区间指标，而是调用 statistics；该端点与 history 的汇总独立于显示粒度，使用所选范围内最新连续有效段。至少两个不同时间的有效样本才返回 delta/rate，JSON 缺省值等同 null，客户端不能沿用上次值。

历史返回 from/to、step、availableFrom、truncated、series。序列有 id、points、complete、可用的 delta/rate。点包含桶起点 timestamp、segment、实际 firstAt/lastAt、firstQuantity、末值 quantity、min/max、samples、rate。segment 改变必须断线，缺口不计算速率。旧层级边界不完整的桶不能伪造精确观测。新增 smoothedRate 为底层观测计算的滚动平均净速率；rateWindowMs 返回实际窗口下限（不低于存储精度），开始处可以使用尚不足整窗的已有连续观测，绝不跨段。Point.rate 保持相邻显示点的旧语义。

statistics 的行与 history 的序列均包含 statisticsFrom/statisticsTo（真实端点）、statisticsSamples、statisticsScope（full/latest-continuous/insufficient）。complete=false 不再使有效的最近连续段指标消失。latest-continuous 以实际端点间的经过时间作分母，不以整个请求范围作分母。分页 version 是本次服务运行的按网络修订号；采样和历史维护均会更新，不是 Unix 采样时间，客户端只比较相等性。

精确身份为旧 AE2WI `ae2wi-1.2.0-pre-forge-1.20.1` 合约，128 位 Murmur3 的 22 字符 Base64URL，不是授权令牌。共享编码包含类型、注册 ID 和完整规范化原生 NBT，数量不参与。256 固定向量取自哈希核对后的发行 JAR，来源见 provenance.json。

分组 ID `group:<type>:<registryId>`；合计来自同次采样，不能相加各变体的降采样极值。Forge 流体单位 mB，1000 mB = 1 B。

显示包 ZIP 根包含 manifest.json 与哈希命名 PNG。formatVersion=1、identityContract 同上、Minecraft 1.20.1、AE2 15.4.10、language=zh_cn；附 Forge、模组、资源包顺序、生成时间和跳过数。entries 为 id/registryId/type/name/page/x/y；pages 为 hash/width/height。64px 图标坐标不含外侧边距。

包/展开总量默认至多 512 MiB，manifest 至多 32 MiB，映射至多十万，图集至多 512、单页至多 4096²；校验路径、重复、大小、图片与哈希。启动读取，无包不影响监测。单项图标失败允许 page 为空，仍保留名称；manifest 状态也可为 partial。原生身份清单仅管理员本地导出，网页无下载或上传接口。

手动历史粒度不合法时返回 HTTP 400，含 `error=INVALID_PARAMETER` 和以毫秒表示的 `suggestedStep`。同一次历史读取使用短 SQLite 读取事务，避免清理或采样穿插造成混合视图，响应发送前释放事务。

SQLite schema=2；从 schema=1 事务迁移，保留统计数据并新增 inventory_capabilities、crafting_orders。worldId、retainedFrom、identityContract 位于 meta，PRAGMA user_version 标记 schema。结构以 HistoryStore.schema 为准。完整采样、能力标记、变化和聚合同事务；能力只在变化时写入。series.last_active 帮助回收三十天未出现的变体，仍可合成的零库存资源保留。WAL/NORMAL 的断电持久性限制见 OPERATIONS.md。

## 合成接口

以下路径相对于 `/api/v1/networks/{uuid}`。访问权限与监测相同，同网络所有访客共享订单；public 模式允许访客下单。POST/DELETE 要求同源 Origin 和 application/json，写请求体最多 4096 字节。没有游戏账户、个人订单过滤或网页配置写入。

| 方法与路径 | 请求与响应 |
|---|---|
| GET crafting/cpus | `{cpus:[{id,name,storage,coprocessors,busy,selectable}]}` |
| POST crafting/plans | `{resourceId,quantity}`；202 返回 Plan，数量为正整数字符串且不超过 Long.MAX_VALUE，流体为 mB |
| GET crafting/plans/{id} | Plan：id/networkId/item/quantity/state/bytes/materials/error/createdAt |
| DELETE crafting/plans/{id} | 取消未提交计算，返回 `{cancelled:true}`；已提交订单不受影响 |
| POST crafting/orders | `{planId,cpuId,requestId}`；cpuId 空字符串为自动选择，requestId 为 UUID；返回 Order |
| GET crafting/orders | history=false/true、limit 默认50最大100、cursor；返回 `{orders,nextCursor}` |
| GET crafting/orders?requestId={uuid} | 查找不确定提交的既有结果，返回同样 OrderPage；仍处理中返回503 REQUEST_PENDING，确认未接受时 orders 为空 |
| GET crafting/orders/{id} | 单个 Order |
| POST crafting/orders/{id}/cancel | 请求体 `{}`；核对当前原生 UUID 和网络后取消，返回 Order |

Item 包含 id/name/unit/type/registryId，无原始 NBT。Material 包含 item/stored/craft/missing，数量与空间均为十进制字符串。Plan.state 为 calculating、ready、missing、failed、cancelled 或 expired；只有 ready 可以提交。每网络至多3个、全局至多32个预览；计算60秒超时，完成后保留15分钟。原生命令队列32条，每tick最多处理4条。

Order 包含 id/requestId/networkId/planId/item/quantity/requestedCpuId/cpuId/cpuName/nativeId/state/createdAt/updatedAt/finishedAt/error/total/progress。state 为 submitting、running、recovering、completed、cancelled、failed 或 unknown；total/progress 是工作项数量，不是成品数量。finishedAt=0 表示未结束。历史按 createdAt、id 倒序游标分页；结束30天后清理，活动订单不按时间清理，活动订单上限1024。

先保存提交意图再调用 AE；同网络同 requestId 的重试返回同一订单，参数不同返回 REQUEST_CONFLICT。超时后查询 requestId，不自动生成新请求重下。确认未接受后，网页清除待确认请求，要求用户重新预览并手动提交。仅按原生 UUID 恢复；无法确认结果为 unknown，不将任务消失当完成。完成/取消由原生生命周期钩子提供，只处理 Dashboard 订单。跨网重组后保留原网络历史，拒绝跨网络取消。

craftingEnabled=false 禁止创建预览和提交新订单，但允许查看历史及取消已有订单。craftingStatus 描述初始化、可用或持久化故障；持久化故障拒绝新下单，最新未写入状态保留并有界重试。
