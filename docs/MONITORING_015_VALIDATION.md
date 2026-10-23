# 0.1.5 监测修复验证

日期：2026-10-23。基于已有0.1.4提交9b42499，用户批准 [实施约定](MONITORING_015_PLAN.md)。本文件记录实际执行的检查；生产新版本验收仍等待用户手动重启。

## 调查结论

重启前四个网络采样时刻一致，其中两个一小时359个样本/1段，另外两个295个样本/61段，后者重复出现每分钟缺一个样本。无对应拓扑事件或SQL错误。长tick后 `next=now+interval` 使网络永久同相；全局两个快照许可在单writer维护期间丢弃额外小快照。本地阻塞writer并提交五个小网络，旧代码当前库存五份但仅落库两份、丢样三份。分钟维护的具体最慢SQL尚未测明，未增加猜测索引；新增分阶段DEBUG耗时便于下次定位。

上午09:16:59（UTC+8）原生SIGBUS发生在writer的SQLite step，故障地址是SHM offset0x8000映射首字节；内核无同期OOM记录。生产SimpleBackups在同JVM普通打开/关闭在线库、WAL、SHM，可能释放该进程SQLite锁。随后外部只读SQLite连接可缩短仍被JVM映射的SHM。本轮调查曾执行此类外部读取，可能是触发链最后一步，已告知用户；此后不再打开在线生产库。

Linux A/B复现使用生产同款Forge JDBC3.36.0.3+20211227、native SQLite3.36.0、Java17.0.20.1。外部SQLite为3.46.1，生产为3.45.1，保留此差异：

| 实验 | 结果 |
|---|---|
| 同JVM原始读关SHM，再外部mode=ro/query_only SELECT，随后JDBC写入 | SHM98304→32768，同inode；SIGBUS，offset0x8000首字节 |
| 仅省略原始读关SHM的对照 | SHM保持98304；写成功、integrity_check=ok |
| 55秒普通混合读写，无原始读关 | 2293写批、10633 JDBC读、10849 Python读，无错误或崩溃 |

因此不把混版本本身当成根因，不更换驱动/JDK，不删WAL/SHM。采取完整在线目录排除、重启恢复锁及进程外一致性备份。原始证据和本地复现产物属于私有运维/忽略目录，不纳入产品仓库。

其他模组的交易转换、反射酿造注册、客户端类加载和缺失标签等启动错误，以及10月21日独立G1 SIGSEGV已记录；没有证据将其归为本次采样或SHM故障，本轮不推测性替换它们。

## 已完成检查

| 检查 | 实际结果 |
|---|---|
| Gradle9.4.0离线完整build，JDK25执行、17编译/测试、21处理Minecraft | 退出0；contracts8、server86、native9、client-exporter3，共106项，0失败/0跳过；两个verifyRelease通过 |
| Linux Java17相关回归 | Model、HistoryStore、采样、停服、SqliteWatchdog、BudgetStress，共45项，退出0、0失败/0跳过 |
| 网页typecheck、Vitest、format:check、生产build | 均退出0，21项单元测试；全量Gradle再次产出0.1.5网页资源 |
| Playwright完整套件 | 57项通过，退出0；独立本机端口4274、合成API，无真实库存请求 |
| Linux Python备份脚本 | 7项通过：WAL数据、互斥、空间不足、发布失败、超时、缺库、发布后目录同步错误；systemd模板语法验证退出0 |
| 隔离Linux Forge47.4.16 + SimpleBackups3.1.24 | 真实backup命令生成ZIP，在线/暂存及嵌套文件均排除，完成副本与相似名称普通目录保留；提取后quick_check及精确大整数恢复通过 |
| 新服务端安装包原生启动 | 0.1.5-dev + AE2 15.4.10，内嵌/外置SQLite驱动共存；Dashboard初始化、进程外备份、真实ZIP恢复、后续源库写入及正常关闭通过，退出0 |

初次Windows专项运行中的既有exportKeys测试遇到沙箱临时目录原子移动AccessDenied；同一测试不改代码，窄范围提权后通过，完整build也通过。Wrapper本机分发缓存缺失，取消下载后使用已安装同版本Gradle完成离线构建；没有升级构建依赖。Linux unit模板检查在Windows挂载目录提示文件权限，部署采用0644/私有env0600，并在实际目标再次校验。

## 回归覆盖

- 长tick跳过过期槽仍保持五个网络的相位；每网每tick最多一次。
- writer阻塞后五个小网络全部落库；64MiB边界、任务队列满、批次失败和停服丢弃准确释放预约；当前库存继续更新，只断受影响网络。
- 四个网络跨七轮实际维护及后续保留清理保持43样本、一个连续段、delta420/rate60。
- 反向字典顺序的旧z/新a段在分钟和十五分钟同桶内按观测时间排序，跨桶后新段起点不丢失。
- 普通关闭持有空闲读取连接，明确检查WAL归零，避免最后连接隐式checkpoint掩盖中断位回归。
- 同段历史首可见平滑60；突增样例三个窗口分别160/80/66.666667；窄/宽请求同点一致。回看不进入极值/可见样本/区间统计；单可见样本统计insufficient；新段、保留期、真实零、一天/七天精度边界不借未来、不跨段。
- 曲线checkbox默认开启，显式关闭跨刷新/切网保存，存储失败保留会话选择；主线与波动带同步，数值、tooltip、缺口、请求数量、zoom、legend和canvas不变。1440/1024/390px深浅截图及选项边界检查通过。
- 独立审查备份脚本的锁、连接关闭、暂存清理和原子发布；目录同步失败发生在发布后时准确报告已替换，避免假称保留旧副本。

## 交付与剩余边界

版本为0.1.5-dev，API1/schema2不变；客户端无需重新导出。源码`aeb37037789e245b8b1e7222f6d887bc5db47010`直接main提交并已推送origin/main，package.ps1在该提交的干净工作树生成安装包清单（workingTreeDirty=false）；后续部署记录提交不重新标注构建来源。安装记录见 [交接状态](HANDOFF.md)。

| 安装包 | SHA-256 |
|---|---|
| ae2-inventory-dashboard-0.1.5-dev-server.jar | b0f97edaae283370ddb5b3200dc688899ef67a60a368a1205f875fb38a3d9a60 |
| ae2-dashboard-assets-0.1.5-dev-client-only.jar | 011b93e9865b88ee70870379bde155776613a64d44000c8a808f2ebf88168fea |

2026-10-23 12:29（UTC+8）服务端暂存校验、备份旧包和配置、切换JAR及排除配置均完成；目标文件再次校验一致。脚本/units权限0644，私有env0600，systemd校验退出0。只执行daemon-reload，没有启动备份：service未有运行时间，timer disabled/inactive；原游戏进程保持未重启。数据库、WAL/SHM、Dashboard配置、显示包均未修改。

生产部署只上传/替换已验证JAR、修正SimpleBackups排除、安装备份脚本和systemd模板；不重启游戏、不运行首次在线备份、不启用timer。重启后应核查实际版本、采样/丢样、维护耗时、SimpleBackups排除和完成副本，首次备份成功再启用每小时timer。上传不代表运行验收通过。

仍未证明最初哪次长tick造成同步，也未完成生产大型网络的新版本负载验收。当前修复保证有界缓冲及真实缺口语义，不承诺任意持续写入负载下永不丢样；其他模组与客户端GPU组合仍属于既有未完成边界。
