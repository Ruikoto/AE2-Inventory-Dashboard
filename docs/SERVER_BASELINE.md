# 服务器只读核查

时间：2026-10-21 13:22 UTC+8。用户授权 SSH 只读检查；没有修改远程文件或重启。

Ubuntu 24.04.1 LTS / x86_64；运行进程使用 Zulu 17.0.20.1+1-LTS。
启动日志确认 Minecraft 1.20.1、Forge 47.4.16。整合包 v0.4.8.18 为用户提供值，未找到独立整合包清单。

| 组件 | JAR 元数据 |
|---|---|
| AE2 | 15.4.10 |
| ExtendedAE | 1.20-1.4.14-forge |
| ExtendedAE Plus | 1.5.3-fix |
| MEGA Cells | 2.4.6-1.20.1 |
| AE Omni Cells | 1.1.1 |
| KubeJS | 2001.6.5-build.16 |
| AE2 Web Integration | 1.2.0-pre-forge-1.20.1 |

AE2 SHA-256: `fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc`。
AE2WI SHA-256: `50b7157d6db3081359fa8fa3d482ecaca5dd10fbe155197bbe5a1863f8dcebc5`，与旧工具来源一致。

检查方法：读取运行进程 exe/cwd，实际 Java -version；zipfile 读取 mods.toml/manifest；hashlib.sha256；javap -public -classpath 已安装 JAR。

已确认 GridServices.register、IGridServiceProvider 节点/tick 回调、IGrid 服务读取、IStorageService.getCachedInventory、IPathingService 状态、IEnergyService.isNetworkPowered、AEKey 编解码/类型/单位、KeyCounter 遍历。

ExtendedAE Plus NetworkPatternControllerBlockEntity 不是 ME ControllerBlockEntity 子类，不能按名称包含 Controller 判定。
这些证据确认版本与接口存在性，不替代编译、运行和整合包兼容验证。不保存密钥、完整日志或玩家信息。
