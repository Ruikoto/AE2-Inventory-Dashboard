# 品质NBT变体名称与图标调查

日期：2026-10-22。用户反馈未合并NBT时，小麦、种子、面粉及面团的品质变体显示英文名称和占位图。本次只读核查现有服务、SQLite资源身份、已安装显示包及实际模组字节码；没有修改游戏数据、显示包、客户端导出器或身份协议。

## 已确认的直接原因

已安装显示包为zh_cn，生成于2026-10-21T08:54:43Z，包含37880条映射，声明quality_food@2.3.0。包本身加载正常，missingIcons=0、skipped=0；这些计数仅覆盖包内已有条目，不代表网络所有NBT变体都已导出。

逐项对照服务端精确身份与manifest：普通种子、面粉、面团有中文名称和图标映射；所查银、金、钻品质变体没有对应精确条目，基础合并组映射仍存在。用户截图中的三种品质种子与普通种子均能和现场精确条目对应。实时API也证实品质种子返回英文名且没有icon，普通种子返回中文名和icon。没有发现“已有精确映射但registryId/type校验不通过”的情况。

资源映射在 `DashboardHttp.resource()` 中仅按精确ID查找。缺失时使用服务端名称，不生成图标；网页 `ResourceIcon.vue` 在icon缺省时显示菱形。合并模式使用独立group ID，其基础名称和图标来自dropSecondary，故合并正常不能证明品质变体已覆盖。该逻辑在0.1.3之前已经存在，统计Worker也不会改写名称或图标。

## 为什么完整导出没有覆盖这些变体

`ClientCommands.Session` 遍历客户端背包副本、注册表默认物品、创造栏及导入的resource-keys.json；不会枚举任意可能的NBT。实际Quality Food 2.3.0没有提供各品质的创造栏变体。服务端核查时也没有现成的管理员resource-keys.json；这不能证明历史上从未导出过清单，但现有显示包确实缺少这些精确条目。

实际模组将品质保存在物品NBT `quality_food.quality`：1=IRON（银色）、2=GOLD、3=DIAMOND。AEItemKey保留tag/caps，NativeItemIdentity使用完整规范化原生身份；没有发现哈希或NBT丢失证据。

## 名称与星标的具体机制

- Quality Food的GuiGraphicsMixin把星标绘制注入GuiGraphics的renderItem路径。现有IconRenderer调用会经过这条路径。因此不能用“导出器未调用renderItemDecorations”解释此模组的当前问题，也没有证据支持先修改渲染器。
- 模组不改变物品hoverName。现有clientName读取它后得到中文基础名，不会自动拼接银/金/钻前缀。tooltip中的效果描述也不是物品标题。
- 上述是实际模组字节码、Forge源代码与调用链证据；匹配客户端、配置、资源包和GPU下的最终星标图像尚未实测。

## 建议的最小处理流程（尚未执行）

1. 管理员执行 `/ae2dashboard export-keys`，生成当前服务端历史目录中的精确身份清单。
2. 将清单复制到匹配整合包客户端的 `ae2-dashboard-assets/resource-keys.json`。在中文语言环境进入世界，执行 `/ae2dashboardassets export`；核查导出完成及跳过信息。
3. 对普通、银、金、钻四种真实物品检查中文基础名及星标图像，确认对应精确ID均出现在新manifest中。
4. 另行授权后替换服务端显示包并重启加载；目前显示包不支持热加载。不要因此改动资源身份或合并历史。

如果产品目标是文本名称明确显示“小麦种子（银星）”等，需要单独确定名称规则并适配；重新导出本身不会创造模组没有提供的品质前缀。精确项缺失时简单回退合并组可以改善基础名称，却不能还原品质区别，不应当成完整修复。

真实库存、精确身份清单、原始NBT和模组JAR不纳入仓库。调查工具副本仅保存在忽略目录.local/quality-investigation中。
