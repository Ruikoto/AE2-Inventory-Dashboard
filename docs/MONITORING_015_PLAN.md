# 0.1.5 监测与备份实施约定

2026-10-23 用户批准执行。维护仓库为本仓库；旧 AE2-Web-Integration 已退役。调查证据与执行结果分别记录，不以计划作为通过证明。

## 采样与存储

- `forge/AeSampler.tick` 按原定相位推进 `next += ((now-next)/interval+1)*interval`；迟到时跳过已错过的槽，每网络每 tick 最多采一次，不以当前 tick 重置相位。允许恢复后相邻采样不足完整周期。
- `DashboardService` 保留单写线程及 32 任务队列，把全局两个采样许可改为活动与排队快照合计 64 MiB 的 `estimatedBytes` 预算。成功、异常、拒绝入队和停服丢弃各释放一次；真实超限仍整份丢弃并只断对应网络。
- `HistoryStore.coverage` 聚合查询按 `bucket,first_at,last_at,segment` 排序，避免随机 UUID 颠倒同桶内时间。分钟和十五分钟两层均覆盖反向 ID 回归。
- executor `terminated` 最后关闭数据库前保存并清除中断位，结束时恢复；不改变正常读取消和写容量中止的语义。
- DEBUG 记录采样延迟、耗时、排队等待、队列深度/估算字节、写入/维护/检查点/查询耗时和失败类别；丢样告警限频。不得记录库存、NBT、凭据，不做逐资源日志。

## 速率与图表

- `HistoryStore.history/readSeries` 用原始 from 选择 tier 和 step；window=max(tier,rateWindow)，readStart=max(0,retainedFrom,from-window)。最多额外读取十五分钟，不跨层补点。
- `History.summarize` 可用完整同段源观测计算 smoothedRate，但显示桶、极值、样本数、区间统计、availableFrom 和 truncated 只取可见范围。旧数量种子不是历史观测。
- 首个可见点有同段历史则计算平滑速率；真正新段首点无前序样本则 null，不补零、不借未来点。Point.rate 仍为相邻可见桶语义，首桶 null。API 和 schema2 不变。
- 线性每十秒加十：末一分钟七样本、delta=60/rate=60、首可见 smoothedRate=60。末次额外加一百：两可见样本 delta=110/rate=660，一/五/十五分钟平滑分别160/80/66.666667；单个可见样本可有曲线速率，但区间统计 insufficient。验证断段、保留期、层级边缘和单显示桶不泄漏回看数据。
- `preferences.smoothCurves` 默认 true，显式 false 可恢复，使用既有 origin/API/world/network 偏好生命周期。详情右上角“波动范围”旁增加“曲线平滑”；主线及波动带统一 ECharts smooth、smoothMonotone=x，保留 null 缺口。只改视觉，不发历史请求，不改变统计、缩放、图例和画布实例。

## 一致性备份

- 修正 SimpleBackups 排除在线 ae2-dashboard 和 .ae2-dashboard-backup-staging 整个目录。3.1.24 的 ignored_paths 仅匹配直接父目录，因此同时使用目录前缀正则覆盖嵌套文件。
- `tools/backup/history_backup.py` 在独立 Linux 进程使用 SQLite backup API，在同文件系统暂存、关闭连接后 quick_check，原子发布 `world/ae2-dashboard-backup/history.sqlite`；仅完成副本进入现有 ZIP/NAS 链。
- 互斥、防重叠、总超时与空间预检；失败保留上一份，只清理本次临时文件并输出失败。systemd 每小时运行，生产路径保存在私有 env 文件。
- 本次安装脚本、units 和配置但不启用 timer；用户重启并确认排除生效后，首次备份成功再启用 timer。不代用户重启，不换 SQLite/JDK/第三方 Mod，不删除库或 WAL/SHM。

## 验证与交付

- 覆盖采样相位、五个小网络同时入队、预算/拒绝/异常/停服释放、关闭检查点、同桶排序、速率首点与边界；合成多网络至少六轮真实维护。
- 网页偏好与无请求视觉更新、刷新/切网/存储失败、1440/1024/390px 深浅布局，完成类型/单测/浏览器/格式和生产构建。
- Linux 备份失败路径和真实 SimpleBackups JAR 排除/ZIP恢复验证。完整 Gradle build；审核最终累计 diff。
- 更新两层 AGENTS、协议/运维/事故记录，版本0.1.5-dev；直接 main 提交并推送 origin/main。先展示提交说明：`fix: 修复监测采样、速率与数据库备份`。
- 源码提交后 package.ps1，暂存校验 JAR、旧包移到 mods 外备份再安装；客户端不需重新导出。线上新版本验收与 timer 启用等待用户手动重启。
