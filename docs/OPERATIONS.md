# 部署、备份和恢复

先在新目录验证，再考虑真实服务器安装。备份世界及 Mod 列表。修改线上 Mod、配置、网络或重启均单独执行。

## 服务端

需要 Forge 47.4.16、AE2 15.4.10、Java 17。仅安装 server 发行 JAR；客户端无须安装它。原 AE2 Web Integration 可以保留，本项目使用独立 Mod ID 和默认端口。

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

合成订单先持久化意图再提交给 AE；浏览器超时应查询既有订单，避免手动重复下单。服务器重启按原生任务 UUID 恢复，无法确认时显示“结果未知”，不会自动补单。卸载期间显示等待恢复；网络重组后订单历史仍归原网络，跨网络取消被拒绝，可在游戏中处理。已结束订单保留30天，活动订单不按时间删除；目录空间预算仍包含订单表。

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

- 正常停服并完成检查点后复制整个 ae2-dashboard 目录。
- 在线备份可由管理员使用 SQLite backup API 或命令行 `.backup`，不依赖网页接口。
- 配置中的密码文件与数据库备份分别保管。
- 恢复前停服，先保留当前完整目录，再恢复同一份备份，不混搭不同时间点的主库和 WAL。
- 坏库和未知更高 schema 不会自动重置。需要保留文件后人工诊断，不能以删除原库作为默认修复。

WAL/NORMAL 可在进程崩溃后恢复已提交事务；突然断电或 OS 崩溃可能丢失最后事务。需要更强断电持久性时应另行评估 FULL 及其写入代价。

保留：约十秒一天、一分钟七天、十五分钟三十天。空间达 85% 提前清理到 75%，30 天是上限而非保证。清理后的 SQLite 空闲页面会重用，文件不一定立即变小。预算包含 WAL，不运行全量 VACUUM。

空间不足、写入或队列故障会造成明确缺口；当前库存继续发布，恢复后使用新连续段。端口被占仅关闭网页入口，采集可以继续。资源包无效只影响显示。

采样与写入都保留完整性边界：网络最多十万精确资源，单快照脱离对象元数据估算最多 32 MiB，历史单事务估算最多 64 MiB 且受预算预留限制；超限整份失败并显示状态，不截断库存。原生单次库存扫描不可强制中断。

## 停用和卸载

停服，移除服务端 JAR即可停用；保留 ae2-dashboard 历史目录和配置可供后续恢复。客户端导出器可独立移除，不影响服务器。浏览器设置仅存本机，清除网站数据会删除本机收藏和别名。
