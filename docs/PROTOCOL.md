# 协议 v1

同源 HTTP，API 前缀 /api/v1。时间是 UTC Unix 毫秒，数量/差值/速率为十进制字符串。错误结构 `{"error":"CODE"}`。登录与退出外只接受 GET/HEAD。

| 路径 | 参数和结果 |
|---|---|
| status | 认证模式/状态；认证后有 worldId、历史状态/占用/丢样和资源包状态 |
| session | POST JSON password 登录，要求 Origin 同源；DELETE 退出，HttpOnly/SameSite Cookie |
| networks | 网络 id、label、status、lastSeen、anchors，含 worldId；nonZeroResources/observedAt 为最后完整观测的非零精确资源数/时间，未知为缺省或 null |
| networks/{uuid}/inventory | merged、zeros、offset、limit（至多 2000）、可选 group；含 available、timestamp、version、stale、windowMs、rows、total |
| networks/{uuid}/statistics | merged、from/to、offset、limit（至多 2000）、可选 version；返回 worldId/networkId/version/total/rows，分页版本变化或查询与写入维护重叠时 409 SNAPSHOT_CHANGED |
| networks/{uuid}/history | ids（至多六项）、from/to（毫秒）、step（毫秒；0 自动）、rateWindowMs（60000/300000/900000，默认 300000）；每条至多 1200 桶 |
| networks/{uuid}/events | 最近至多一百条状态或拓扑事件 |
| resources | offset/limit 分页资源，无 identity/NBT |
| assets/manifest | missing/invalid/loaded 及覆盖信息；无包不是错误 |
| assets/{sha256}.png | 已校验图集，受认证 |

库存行包含 id、groupId、type、registryId、name、unit、amountPerUnit、quantity、delta、rate、variantCount 和可选 icon。分页必须核对同一 version；变化则重新读取。库存端点的旧 delta/rate 字段仍为相邻有效采样，windowMs 表示时间，以兼容既有调用。新版网页不使用它们显示区间指标，而是调用 statistics；该端点与 history 的汇总独立于显示粒度，使用所选范围内最新连续有效段。至少两个不同时间的有效样本才返回 delta/rate，JSON 缺省值等同 null，客户端不能沿用上次值。

历史返回 from/to、step、availableFrom、truncated、series。序列有 id、points、complete、可用的 delta/rate。点包含桶起点 timestamp、segment、实际 firstAt/lastAt、firstQuantity、末值 quantity、min/max、samples、rate。segment 改变必须断线，缺口不计算速率。旧层级边界不完整的桶不能伪造精确观测。新增 smoothedRate 为底层观测计算的滚动平均净速率；rateWindowMs 返回实际窗口下限（不低于存储精度），开始处可以使用尚不足整窗的已有连续观测，绝不跨段。Point.rate 保持相邻显示点的旧语义。

statistics 的行与 history 的序列均包含 statisticsFrom/statisticsTo（真实端点）、statisticsSamples、statisticsScope（full/latest-continuous/insufficient）。complete=false 不再使有效的最近连续段指标消失。latest-continuous 以实际端点间的经过时间作分母，不以整个请求范围作分母。分页 version 是本次服务运行的按网络修订号；采样和历史维护均会更新，不是 Unix 采样时间，客户端只比较相等性。

精确身份为旧 AE2WI `ae2wi-1.2.0-pre-forge-1.20.1` 合约，128 位 Murmur3 的 22 字符 Base64URL，不是授权令牌。共享编码包含类型、注册 ID 和完整规范化原生 NBT，数量不参与。256 固定向量取自哈希核对后的发行 JAR，来源见 provenance.json。

分组 ID `group:<type>:<registryId>`；合计来自同次采样，不能相加各变体的降采样极值。Forge 流体单位 mB，1000 mB = 1 B。

显示包 ZIP 根包含 manifest.json 与哈希命名 PNG。formatVersion=1、identityContract 同上、Minecraft 1.20.1、AE2 15.4.10、language=zh_cn；附 Forge、模组、资源包顺序、生成时间和跳过数。entries 为 id/registryId/type/name/page/x/y；pages 为 hash/width/height。64px 图标坐标不含外侧边距。

包/展开总量默认至多 512 MiB，manifest 至多 32 MiB，映射至多十万，图集至多 512、单页至多 4096²；校验路径、重复、大小、图片与哈希。启动读取，无包不影响监测。单项图标失败允许 page 为空，仍保留名称；manifest 状态也可为 partial。原生身份清单仅管理员本地导出，网页无下载或上传接口。

手动历史粒度不合法时返回 HTTP 400，含 `error=INVALID_PARAMETER` 和以毫秒表示的 `suggestedStep`。同一次历史读取使用短 SQLite 读取事务，避免清理或采样穿插造成混合视图，响应发送前释放事务。

SQLite schema=1；worldId、retainedFrom、identityContract 位于 meta，PRAGMA user_version 标记 schema。结构以 HistoryStore.schema 为准。完整采样、变化和聚合同事务，覆盖与边界基线共同恢复平稳区间。series.last_active 帮助回收三十天未出现的变体。WAL/NORMAL 的断电持久性限制见 OPERATIONS.md。
