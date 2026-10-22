# AE 库存监测：项目指令

本文件只约束本仓库。用户最新明确要求优先；不要把相邻 AE2-Web-Integration 仓库的分支、构建命令或部署约定套用到这里。中文回复，文本显式使用 UTF-8；新增注释以简洁中文为主，保留有用的既有注释。

## 接手与范围

1. 确认当前路径属于本独立仓库，检查 Git 状态，保留其他任务的变更。
2. 先读 [README](README.md)、[交接状态](docs/HANDOFF.md)；修改前读 [实施基线](docs/IMPLEMENTATION_PLAN.md)、[验收清单](docs/ACCEPTANCE.md) 及对应模块协议。
3. 统计以 [0.1.2 实施约定](docs/UX_012_PLAN.md) 为依据；网格、无限项和合成订单以 [0.1.3 实施约定](docs/CRAFTING_013_PLAN.md)、[协议](docs/PROTOCOL.md) 为依据。原始完整计划不能覆盖后续确认的行为。
4. 简述本次目标、修改模块、验证及交付范围。普通实现选择自行决定；只有影响已确认行为、架构或交付边界的未知项才需要澄清。
5. [验证记录](docs/VALIDATION.md)、[0.1.2 验证](docs/UX_012_VALIDATION.md) 和 [0.1.3 验证](docs/CRAFTING_013_VALIDATION.md) 是有日期的证据，不是当前服务器状态，也不是所有未来修改的通过证明。

## 模块与责任入口

| 模块 | 责任与定位 |
|---|---|
| contracts | 原生无关模型、身份与资源包协议；History 负责精确统计，NetworkIdentity 负责重识别。Minecraft NBT 桥接位于 contracts/src/native/java，由两个 Forge 模块编译 |
| server/forge | MonitoringGridService 发现网络，AeSampler 在游戏线程读取 AE，DashboardMod 管理生命周期 |
| server | DashboardService 发布不可变快照与有界写入；CraftingService 协调持久订单，forge/AeCrafting 负责原生合成；HistoryStore 保存统计和订单，SqliteWatchdog 管理读取取消；http 负责 API 和会话；assets 负责可选显示包 |
| client-exporter | ClientCommands 采集本地变体，IconRenderer 在渲染线程生成像素，PackWriter 后台编码并完整发布资源包；只装客户端 |
| web | Vue MVVM：models 为协议、精确数值、偏好和 Worker；viewmodels 管理状态、请求和取消；views 渲染，不自行请求历史 |

Java 路径均从模块 src/main/java/local/ae2dashboard 开始；网页路径从 web/src 开始。不要把所有业务逻辑堆进 App.vue，或为一次性操作另造转发层。

## 不可破坏的行为

- 基线为 Minecraft 1.20.1、Forge 47.4.16、AE2 15.4.10、Java 17。Maven AE2 依赖是开发映射产物，实机测试需使用已核实的运行 JAR，见 [现场基线](docs/SERVER_BASELINE.md)。升级依赖前核实实际 API，不凭记忆猜接口。
- 仅监测已加载且有真正 ME 控制器的网络，不加载区块。ExtendedAE Plus 的网络样板控制器不能仅凭名称纳入。
- Minecraft/AE 对象仅在所属线程访问；使用 getCachedInventory，不修改缓存或主动失效。HTTP 不扫描游戏网络；后台只接收脱离游戏对象的完整快照。
- SQLite 单写入线程、最多两个读取连接、三秒读取取消；保持事务与连接所有权。读取超时只能取消该次读取所属的原生连接，不得串扰写连接或已结束任务后的新操作；写入容量中止仍保留自己的取消及恢复逻辑，不得为掩盖事务错误删除数据库。相关修复背景见 [SQLite 验证](docs/SQLITE_FIX_VALIDATION.md)。
- 队列、查询和缓存有界；丢弃整份未写入采样时明确记录缺口。未知、离线或失败不能记成零；后续完整快照中资源消失才归零。
- 数量以十进制字符串传输，合计用 BigInteger/BigInt；流体为 mB。精确身份和合并组分别记录，合并极值不能由不同变体极值相加。
- 表格与详情共用所选时间范围；缺数据只统计最近连续有效段，显示真实时段，绝不跨缺口/重启/拓扑段计算速率。显示粒度不改变区间指标。
- inventory 的旧 delta/rate 是相邻采样兼容字段；网页区间值取 statistics。Point.rate 保持相邻显示点语义，smoothedRate 独立表示滚动平均。缺省或 null 的指标必须清除旧值；分页遇到版本变化不能混合结果。
- 图表默认数量＋速率双轴，不同单位分图，默认五分钟平滑。波动带只表示真实 min/max，不制造精度或产量。
- 资源包只增强名称和图标；缺失、损坏、部分覆盖不得影响身份、数量、历史或收藏。服务端不得加载客户端渲染代码。
- 监测 HTTP 接口只读；仅明确的 crafting 路由允许合成写入，要求现有访问权限及同源 Origin。craftingEnabled 默认开启，关闭后禁止新预览/提交但保留已有订单查看和取消；public 模式允许访客下单。同网络订单共享，不增加配置写入或上传接口。API 不返回原始 NBT、密码、路径或堆栈。
- 订单意图先落盘再调用 AE，所有写入复用唯一写线程；完成/取消来自原生生命周期钩子，不能靠任务消失判断完成。重启只按原生 UUID 恢复，结果未知不自动重下。只跟踪本项目订单，取消前核对 UUID 和网络归属。
- 无限标记只影响库存目录的显示/筛选，数量和统计保持真实值；从当前网络中 ExtendedAE InfinityCellInventory 来源识别，不按大整数阈值猜测，不把有限的大容量单元当无限。
- 网页偏好按站点/API/worldId/networkId 隔离，精确与合并收藏分开；已知空网络默认隐藏，未知不当空。Worker 处理大目录，主线程只接可见窗口；保留刷新时的滚动、选择和图表交互，取消及过期响应不得覆盖新上下文。

## 构建与必要验证

从本仓库根目录运行。Node 24；Gradle 9.4.0 用完整 JDK25，Mod 编译/测试用 JDK17，Minecraft 处理工具用 JDK21；服务器只需 Java17。Wrapper、lockfile 和依赖校验配置纳入版本管理；个人工具路径、代理和凭据不写进通用配置。环境准备见 [README](README.md)。

| 改动 | 验证入口 |
|---|---|
| contracts/存储/HTTP | ./gradlew.bat :contracts:test :server:coreTest；选取相关回归，交付前 build |
| 原生身份或 AE 适配 | 上述检查加 :server:nativeTest；需要运行语义时做隔离 Forge 验证，不能以编译替代实机证据 |
| 客户端导出 | :client-exporter:test 与 build；涉及真实渲染须区分单元测试和匹配客户端 GPU 实测 |
| 网页 | npm --prefix web run typecheck、npm --prefix web test、npm --prefix web run test:browser、npm --prefix web run format:check；UI 完成前生产构建必须通过 |
| 安装包 | ./gradlew.bat build，包含网页、重映射与 verifyRelease 检查；服务端使用 *-server.jar，客户端使用 *-client-only.jar，禁止拿 *-dev.jar 安装 |
| 纯文档/指令 | 核对命令、路径、相对链接和完整 diff；git diff --check。不因文档变更重跑游戏或性能测试 |

Linux 用 ./gradlew。需要代理的 Windows 构建可用 ./tools/gradle.ps1；它读取当前 HTTPS_PROXY，不保存认证信息。依赖齐全时可 --offline；缺少输入、跳过或被阻断的检查必须如实报告。

- :server:coreTest 是 :server:test 的别名，不必重复当成两套测试。完整 build 也包含 contracts、server、nativeTest 和 client-exporter 测试。
- 浏览器测试使用 context.route 拦截 API，确保 Worker 请求也受控；只使用合成数据，不指向真实库存服务。测试服务端口需确认未被其他任务占用。
- 存储性能使用 :server:performanceTest 的 large/long/dense 工作负载；网页性能见 [复现说明](web/tests/performance/README.md)。仅相关性能改动才重跑，长短测试分开输出，防止覆盖证据。报告基线、机器、浏览器、样本和测量边界；主线程堆不包含 Worker，网页帧时间不代表 AE 采样 tick。
- 修改统计时保留上涨后末次回落、平稳前缀、季度中途启动、新资源、归零、断段、保留期截断、分页清理、大整数及两端口径一致的回归。
- 修改网页时覆盖切网络/隐藏/退出/取消、空数据/单样本、存储失败、无资源包、六项与不同单位、刷新保持和 1440/1024/390px。按实际改动选择测试，不扩展无关检查。

## Git、打包与部署

- 现有项目交付基线为 main；先核对当前任务和 worktree，保留他人变更。纯指令/文档维护不切换分支、不创建工作树。不要修改相邻仓库或套用它的 core 分支规则。
- 提交前检查最终完整差异、必要验证和敏感文件，向用户展示提交说明；提交不带 AI/生成器署名。提交、推送、上传、重启分别按已有会话授权执行，已有授权不重复询问，历史文档不构成新任务的永久授权。
- 版本变更同步 gradle.properties、web/package.json 和 package-lock.json。正式发行条件未满足时使用 -dev，不自动打正式标签。
- 构建验证、源文件提交后运行 ./tools/package.ps1 生成两个安装包及带提交号、哈希和脏树标记的清单。脚本不执行构建，也不验证产物是否过期；只对当前已验证源码的产物打包，不在纯文档收尾中重新标注旧安装包。
- 远程诊断从只读开始，先核实当前日志和运行版本；不把旧现场记录当现状。SSH 路线和实例位置留在父工作区运维资料，不复制凭据或个人路径到公共仓库。
- 上传已获授权时，先暂存并校验安装包，将旧包移到 mods 外备份，再切换；确保 mods 只有一个本项目服务端 JAR。保留配置、世界、SQLite 和显示包。上传成功不等于新版本已运行。
- 用户负责手动重启；除非后续明确授权，不能代为重启、调整网络或改线上配置。不要以测试为由操作生产世界。
- 禁止提交真实库存/身份清单、玩家信息、配置密码、密钥、世界、SQLite/WAL、资源包、运行日志、截图原始数据、工具链或缓存。保留经过筛选的验证结论和必要的发行指纹。
