# 部署、备份和恢复

先在新目录验证，再考虑真实服务器安装。备份世界及 Mod 列表。修改线上 Mod、配置、网络或重启均单独执行。

## 服务端

需要 Forge 47.4.16、AE2 15.4.10、Java 17。仅安装 server 发行 JAR；客户端无须安装它。原 AE2 Web Integration 已退役，当前运维和升级只针对本项目。

配置在 config/ae2-dashboard.properties，首次启动写入默认值与随机主密码，改动重启生效：

| 字段 | 默认 | 含义 |
|---|---|---|
| bindAddress | 0.0.0.0 | 监听地址 |
| port | 18766 | HTTP 端口 |
| authMode | password | public 才是公开访问，空密码不会关闭认证 |
| password | 自动随机 | 不复制到日志、截图或仓库 |
| sampleIntervalSeconds | 10 | 允许 2～3600；不补跑丢失采样 |
| historyBudgetMiB | 2048 | SQLite 目录总预算 |
| secureCookie | false | 反向代理 HTTPS 部署时设 true |
| publicOrigin | 空 | 直连使用请求 Host；代理后设为外部 https://域名 |
| assetPack | ae2-dashboard-assets.zip | 相对 config 或管理员指定绝对路径 |
| craftingEnabled | true | 是否允许创建预览和新合成订单；关闭后保留已有订单查看、跟踪和取消 |

同主密码可访问所有已发现网络，没有逐玩家权限。同网络所有访问者共享 Dashboard 创建的订单及取消权限；公开模式也允许访客下单和取消。网页仅开放明确的合成写接口，不提供配置写入或文件上传。名称包和精确身份清单仅通过管理员文件操作安装。

密码登录固定保留30天，不因一小时闲置或服务器重启而失效。`config/ae2-dashboard-sessions.json`保存令牌哈希和到期时间，不含明文令牌或密码，但仍属于私有认证数据，应与配置一起保管，不提交仓库。主密码改变会撤销已有登录，主动退出会持久撤销当前凭证。损坏或未知版本的会话文件不会被静默覆盖：HTTP初始化报告失败，库存采样继续；先保留文件并查看日志。文件写入失败会明确报告登录/退出失败，不能把失败的退出当作已撤销。

合成订单先持久化意图再提交给 AE；浏览器超时应查询既有订单，避免手动重复下单。服务器重启按原生任务 UUID 恢复，无法确认时显示“结果未知”，不会自动补单。卸载期间显示等待恢复；网络重组后订单历史仍归原网络，跨网络取消被拒绝，可在游戏中处理。已结束订单保留30天，活动订单不按时间删除；目录空间预算仍包含订单表。

CPU面板可只读查看当前网络的游戏内任务，但这些任务不会导入Dashboard订单历史，网页也不能取消它们。预览需要可见页面定期读取，30秒无人读取会释放名额；关闭页面不影响已提交订单。计算上限60秒，完成预览绝对有效期15分钟。页面恢复后若预览过期，保留输入并重新预览。网络预览满、全局预览满、请求队列忙等提示分别说明原因，不应通过反复点击下单绕过。

剩余时间是AE原生耗时与工作量的估计，不是成品计数。外部加工可能长时间没有进展；“暂无法估算”并不代表任务失败。无法存回网络使用原生状态告警，不能仅凭待合成材料推断缺料。

## HTTPS 示例

已有 Nginx 时可采用以下片段；域名和证书由管理员配置。建议后端 bindAddress=127.0.0.1，并设置 secureCookie=true、publicOrigin=https://ae.example.com。证书终止不依赖浏览器提供的 X-Forwarded-Proto。

```nginx
server {
    listen 443 ssl;
    server_name ae.example.com;
    ssl_certificate /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:18766;
        proxy_set_header Host $http_host;
        proxy_connect_timeout 5s;
        proxy_read_timeout 15s;
        proxy_send_timeout 15s;
        client_max_body_size 4k;
    }
}
```

第一版按站点根路径部署。代理可提供请求连接和慢客户端时间限制；JDK HTTP 线程与队列本身有界。

## 数据文件

存档下 ae2-dashboard/history.sqlite，以及 -wal/-shm。不要在运行中只复制主数据库，也不要单独删除 WAL。

0.1.3-dev 首次打开 schema1 数据库会事务迁移至 schema2，新增能力标记和订单表，保留原统计。旧版本不能读取 schema2；回退旧 JAR 时需同时恢复升级前完整数据库备份，不能把旧 JAR直接指向已升级的数据库。迁移失败不会删除或重建原库。

0.1.4-dev和0.1.5-dev继续使用schema2。品质显示从既有身份派生，不重写身份/历史；CPU材料与预计剩余时间只在内存中保存，不增加逐次数据库写入。

- 正常停服并完成检查点后复制整个 ae2-dashboard 目录。
- 在线备份使用下述进程外 SQLite backup API；必须先确认游戏内文件备份不再打开在线数据库及 WAL/SHM，并在初次排除后重启恢复锁。
- 配置中的密码文件与数据库备份分别保管。
- 恢复前停服，先保留当前完整目录，再恢复同一份备份，不混搭不同时间点的主库和 WAL。
- 坏库和未知更高 schema 不会自动重置。需要保留文件后人工诊断，不能以删除原库作为默认修复。

WAL/NORMAL 可在进程崩溃后恢复已提交事务；突然断电或 OS 崩溃可能丢失最后事务。需要更强断电持久性时应另行评估 FULL 及其写入代价。

保留：约十秒一天、一分钟七天、十五分钟三十天。空间达 85% 提前清理到 75%，30 天是上限而非保证。清理后的 SQLite 空闲页面会重用，文件不一定立即变小。预算包含 WAL，不运行全量 VACUUM。

空间不足、写入或队列故障会造成明确缺口；当前库存继续发布，恢复后使用新连续段。端口被占仅关闭网页入口，采集可以继续。资源包无效只影响显示。

采样与写入都保留完整性边界：网络最多十万精确资源，单快照脱离对象元数据估算最多 32 MiB；活动和排队采样合计估算最多 64 MiB，共享写任务队列上限32；历史单事务估算最多 64 MiB 且受预算预留限制。超限整份失败并显示状态，不截断库存。原生单次库存扫描不可强制中断。长 tick 后跳过过期槽并保留各网络相位，不补跑；下一次采样间隔可能短于完整周期。

## 每小时一致性备份

同 JVM 普通打开并关闭 SQLite 文件会释放该进程持有的 POSIX 文件锁，即使 SQLite 自己的连接仍然开启。文件备份和后来的外部 SQLite 读取组合可缩短仍被 JVM 映射的 SHM，触发 SIGBUS；`mode=ro`、`query_only` 都不能解决这个锁问题。不要把 `mmap_size=0` 当成关闭 WAL SHM，也不要删除 WAL/SHM。

SimpleBackups 1.20.1-3.1.24 的 `ignored_paths` 只检查直接父目录；排除整个目录时同时设置前缀正则，保留其他已配置的排除项：

```toml
[to_ignore]
ignored_paths = ["ae2-dashboard", ".ae2-dashboard-backup-staging"]
ignored_files_regex = '^(?:ae2-dashboard|\.ae2-dashboard-backup-staging)(?:/.*)?$'
```

完成副本目录 `ae2-dashboard-backup` 不在排除范围。脚本在独立 Linux 进程运行，使用标准库 SQLite backup API，写专用暂存目录、关闭后 quick_check、原子发布 `ae2-dashboard-backup/history.sqlite`。互斥避免重叠，默认240秒超时，预检数据库页大小加64MiB空余；发布前失败返回非零退出码并保留上一份完整副本。如果替换成功后目录同步失败，明确报告“新副本已完整发布”，需检查磁盘持久性，不能误以为旧副本仍在。强制终止可能留下专用暂存文件；核实没有备份在运行后只清理确认的遗留文件，不删除在线数据库。世界 ZIP 可读到发布前或发布后的完整副本，不会读到半成品。

安装步骤（系统服务按本机权限运行；生产 world 路径只写私有 env 文件）：

1. 将 `tools/backup/history_backup.py` 安装到 `/usr/local/lib/ae2-dashboard/history_backup.py`，两个 unit 模板安装到 `/etc/systemd/system/`。
2. 按 `ae2-dashboard-backup.env.example` 创建权限0600的 `/etc/ae2-dashboard-backup.env`，设置 `DASHBOARD_WORLD`。脚本默认由 systemd 系统服务运行；使用独立游戏账户时设置对应 `User=` 并授予世界目录读写权限。
3. 修正 SimpleBackups 排除配置，`systemctl daemon-reload`；此时不启动备份，也不启用 timer。
4. 用户手动重启游戏，确认配置和排除规则生效。`systemctl start ae2-dashboard-backup.service`，检查 `journalctl -u ae2-dashboard-backup.service` 与完成副本；首次成功后 `systemctl enable --now ae2-dashboard-backup.timer`。
5. 核对下次世界 ZIP 不含在线/暂存目录，包含 `ae2-dashboard-backup/history.sqlite`；从 ZIP 提取到隔离目录，quick_check 通过后确认现有保留/NAS链仍覆盖它。

定时器每小时运行（最多两分钟随机偏移）；世界备份采用最近一次完成副本，时间可能早于世界 ZIP。现有 ZIP 保留与 NAS 快照继续负责多版本保留，脚本本身只维护一个完成副本。恢复时停服保留原目录，从完成副本恢复至在线目录，不能混入旧 WAL/SHM。

## 排障日志

0.1.5 增加 `ae2dashboard` DEBUG 汇总：采样 lag/跳槽/耗时、队列等待/深度/估算字节、写入与维护、检查点和查询耗时及失败类别。Forge 的 `logs/debug.log` 是核查入口；INFO/WARN 继续报告启停及真实故障，丢样 WARN 最多每分钟一次，累计值仍保留。日志不输出库存条目、原始 NBT 或认证信息。日志时间按服务器时区对齐用户报告时间，不能只凭当前状态反推事故原因。

## 停用和卸载

停服，移除服务端 JAR即可停用；保留 ae2-dashboard 历史目录和配置可供后续恢复。客户端导出器可独立移除，不影响服务器。浏览器设置仅存本机，清除网站数据会删除本机收藏和别名。
