# 本地原生验收复现

使用独立 Forge 1.20.1-47.4.16 服务器、Java 17、正式 AE2 15.4.10 和 GuideME 20.1.14，安装本项目 `-server.jar`。开发 Maven AE2 JAR 不能替代正式运行 JAR。相关版本及指纹见 SERVER_BASELINE.md。

只使用空白测试世界。设置游戏 bind 为 127.0.0.1，网页配置同样使用 bindAddress=127.0.0.1；如启用 RCON，也只允许本机且使用测试密码。测试中可以显式使用 authMode=public。不要将以下操作指向真实服务器。

## 基础库存和外部箱子

通过测试控制台执行以下命令。坐标均属于合成测试世界：

```text
forceload add 0 0
setblock 0 64 0 ae2:creative_energy_cell
setblock 1 64 0 ae2:controller
setblock 2 64 0 ae2:drive
data modify block 2 64 0 inv.item0 set value {id:"ae2:item_storage_cell_1k",Count:1b,tag:{ic:9007199254741314L,keys:[{"#c":"ae2:i",id:"minecraft:iron_ingot"},{"#c":"ae2:i",id:"minecraft:iron_ingot",tag:{Damage:1}}],amts:[L;9007199254740993L,321L]}}
data modify block 2 64 0 inv.item1 set value {id:"ae2:fluid_storage_cell_1k",Count:1b,tag:{ic:123456L,keys:[{"#c":"ae2:f",id:"minecraft:water"}],amts:[L;123456L]}}
setblock 2 64 2 minecraft:chest{Items:[{Slot:0b,id:"minecraft:copper_ingot",Count:64b}]}
setblock 2 64 1 ae2:cable_bus{cable:{id:"ae2:fluix_glass_cable"},south:{id:"ae2:storage_bus"}}
```

等待至少一个采样周期。GET /api/v1/networks 获得网络 UUID，再读取 inventory 和 merged=true。应有两个铁锭变体、精确合计 9007199254741314、123456 mB 水和 64 铜锭。无客户端名称包时仍能查询并绘图。上述超大库存是序列化数值边界测试，超过普通存储单元的正常容量。

断电使用 `setblock 0 64 0 air`，检查状态为 unpowered，历史没有伪造归零；恢复电源后开启新连续段。清空 drive 的 inv.item0/inv.item1 后检查实际归零。正常重启检查 worldId 与网络 UUID 不变，历史速率不跨重启。

## 拓扑和附属 Mod

另建一个控制器和电源网络，以 drive 连接两个相隔的控制器。冲突时状态为 conflict；断开连接后拆分，确认旧 UUID 最多分配给一个分支，合并来源有事件，重启不复活 merged 身份。单独给 drive 提供电源但没有 ME 控制器时，不应新增监测目录项。

加入现场对应版本的 ExtendedAE、ExtendedAE Plus、MEGA Cells、Omni Cells、旧 AE2 Web Integration、Glodium、Cloth Config；不要省略必需依赖。MEGA Cells 可将同类测试 keys/amts 写入 item_storage_cell_1m。Omni Cell 使用独立 SavedData，不能假定沿用 AE2 的 keys/amts；本次以独立 UUID 的合成 SavedData 验证 888 钻石，未复制真实数据。

单独供电的 extendedae_plus:network_pattern_controller 不应纳入 ME 控制器发现。分别保留/移除测试服外置 SQLite Forge Mod 重启，验证内嵌依赖在两种条件下正常加载。

## Linux JDBC

在具有 JDK 17 的本地 Linux 环境，将已验证驱动和编译输出放入临时工作目录：

```bash
javac -cp sqlite-jdbc-3.36.0.3+20211227-all.jar -d out tools/SqliteSmoke.java
java -cp out:sqlite-jdbc-3.36.0.3+20211227-all.jar SqliteSmoke out
```

成功时输出 WAL/integer/cancellation/integrity=passed。该测试不启动 Minecraft，不需要客户端资源或外部数据库服务。
