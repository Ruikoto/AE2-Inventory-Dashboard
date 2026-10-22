# AE 库存监测

Minecraft 1.20.1 / Forge 47.4.16 / AE2 15.4.10。服务端记录库存，浏览器查看数量、净变化和趋势；客户端资源工具仅补充中文名称与图标。

## 无资源包开始使用

1. 构建后将 `server/build/libs/*-server.jar` 放入**隔离测试服务器**的 mods；不要使用 `-dev.jar`。
2. 启动后在 `config/ae2-dashboard.properties` 查看自动生成的主密码。
3. 浏览器打开服务器的 18766 端口，输入密码。加载有 ME 控制器的网络后约十秒采样。
4. 没有客户端导出器、没有名称表、没有资源包也能使用全部监测功能。名称可能是英文或注册 ID，图标为类型占位符。

这是 0.1.4-dev 开发构建。品质显示、持久登录、库存筛选和CPU合成界面约定见 [0.1.4计划](docs/UX_014_PLAN.md)，验证见 [0.1.4记录](docs/UX_014_VALIDATION.md)。既有统计与无限项分别依据 [0.1.2验证](docs/UX_012_VALIDATION.md) 和 [0.1.3验证](docs/CRAFTING_013_VALIDATION.md)。2026-10-22后续授权上传已完成，等待用户手动重启；上传不代表新版本已运行。

右上角可选深色、浅色或自定义主题。时间范围同时控制表格和详情的区间净变化、平均净速率；缺数据时显示最近连续段及真实时段。图表默认叠加数量和五分钟平滑速率，平滑窗口可调整。网页记住上次网络，默认隐藏已知空网络（设置中可开启）。

库存默认以网格显示，可切换列表；可合成的零库存条目也能下单。点击条目查看统计，点击“下单”输入数量、检查材料和 CPU 后确认合成。网络内所有访问者共享本网页订单及取消权限，已结束订单保留30天。`craftingEnabled=false` 可关闭新下单，仍能查看和取消已有订单；公开模式同样允许访客下单。

目录支持“全部／库存／可合成”与“全部／物品／流体”两组图标开关，搜索支持中文、英文、注册ID、拼音全拼和首字母，例如小麦种子可用xiaomai或xmzz查询。拼音索引在浏览器Worker本地建立，不向外部服务发送名称。合成预览以材料网格显示库存使用、需要合成和缺少数量；缺料或没有合适CPU时不能下单。进行中面板显示全部CPU的实时材料和预计剩余时间，游戏内发起的任务只读，网页只能取消自身订单。预计时间基于AE工作量；等待、停滞或离线时不会显示虚构倒计时。

密码登录在当前浏览器保留30天，服务器重启后仍有效。浏览器仅保存HttpOnly登录凭证；主动退出或服务器修改主密码后失效。会话文件属于私有配置，见运维说明。

已识别的 ExtendedAE 无限单元在目录中显示“无限”，勾选“排除无限项”可隐藏；统计仍使用真实数值。当前识别范围是网络中的驱动器和 ME 箱子，不按库存阈值猜测其他来源。

## 开发接手

项目约束与验证入口见 [AGENTS.md](AGENTS.md)，最近交付、已验证范围和遗留事项见 [交接状态](docs/HANDOFF.md)。部署记录不代表服务器当前已加载该版本；版本与运行状态需要现场核查。

## 构建

需要 Node 24、完整 JDK 25（Gradle daemon）、JDK 17（Mod 编译/测试）和 JDK 21（Minecraft 处理工具）。Wrapper 固定 9.4.0，ModDevGradle 2.0.86。构建工具可通过 Foojay 下载缺失工具链，服务器只需 Java 17。

PowerShell，在项目根目录运行：

```powershell
npm --prefix web ci
./gradlew.bat build
npm --prefix web test
npm --prefix web run browser:install
npm --prefix web run test:browser
npm --prefix web run format:check
```

设置 JAVA_HOME 指向 JDK 25；必要时在个人 Gradle 配置中设置工具链安装路径。HTTP_PROXY/HTTPS_PROXY 环境下，Java 不会自动使用代理，可运行 `./tools/gradle.ps1 build`；脚本读取当前代理但不保存配置或凭据。Linux 使用 `./gradlew`。

输出分别位于 server/build/libs 和 client-exporter/build/libs，选择 `-server.jar`、`-client-only.jar`。主库和原生驱动随服务端 JAR 打包，网页无运行时 CDN，不需安装 SQLite/Node 服务。

快速测试不需要 Minecraft 启动：

```powershell
./gradlew.bat :contracts:test :server:coreTest
./gradlew.bat :server:nativeTest
./gradlew.bat :server:runDemo
./gradlew.bat :server:performanceTest
./gradlew.bat :server:performanceTest -Pworkload=long
./gradlew.bat :server:performanceTest -Pworkload=dense
./tools/package.ps1
```

runDemo 仅本机 18766，公开访问合成数据，输出到忽略的 .local/demo；不连接真实服务器。性能数据同样为合成数据，不能当作 AE 原生读取或真实 TPS 测量。

`package.ps1` 将两个安装包及带 Git 提交、工具版本和 SHA-256 的 `manifest.json` 复制到 `build/distributions/`。服务端通过 Forge Jar-in-Jar 内嵌 SQLite Forge 驱动；与现场现有 `sqlite-jdbc-3.36.0.3+20211227-all.jar` 已做本地共存测试，不需移除它。

## 名称和图标

将 `client-exporter/build/libs/*-client-only.jar` 装到匹配整合包客户端，切换简体中文并进入测试世界：

```text
/ae2dashboardassets sample
/ae2dashboardassets export
/ae2dashboardassets cancel
```

完整导出会读取注册表、创造模式变体、背包副本和流体并逐帧渲染。输出 `ae2-dashboard-assets/assets-*.zip`。工具不修改背包和世界，不发送游戏操作包。

补齐实际 NBT 变体：服务器管理员运行 `/ae2dashboard export-keys`，手动将存档下 `ae2-dashboard/resource-keys.json` 放到客户端 `ae2-dashboard-assets/resource-keys.json`，再导出。清单含原生身份数据，属于私有运行数据，不提交到 Git。

将生成 ZIP 手动复制为服务端 `config/ae2-dashboard-assets.zip`，重启加载。更新显示包不改变历史或收藏。资源包缺失、失败或覆盖不全会使用基础显示，监测继续。不要把私有整合包资产直接提交或发布。

Quality Food银、金、钻品质支持基础精确图标叠加星标。优先使用已有精确映射；缺项时只移除品质显示数据计算基础映射，保留其他NBT和能力，不改变物品身份。现有包包含基础物品和星标即可使用，无须为每种随机品质效果重新导出；其他未覆盖的自定义变体仍通过精确清单补齐。

## 配置和运维

详细说明见 [部署与数据维护](docs/OPERATIONS.md)，接口和资源格式见 [协议](docs/PROTOCOL.md)，完整需求与验收分别见 [实施计划](docs/IMPLEMENTATION_PLAN.md)、[清单](docs/ACCEPTANCE.md)。

新项目独立 Git 仓库；不依赖、不替换 AE2 Web Integration。派生代码与协议来源见 [provenance.json](docs/provenance.json)，上游代码许可证保留在 LICENSE；第三方说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
