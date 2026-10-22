# 来源和许可证

- AE2 Web Integration / 旧客户端名称工具：LGPL-3.0-or-later，kuba6000 等原作者，参考及派生来源见 docs/provenance.json。旧身份算法注释保留。
- 0.1.3 合成预览、提交和生命周期钩子参考同一上游的 crafting endpoints、CraftingPlanRegistry、AE2JobTracker 及发行版 Forge CraftingCPULogicMixin；适配为本项目独立服务和持久订单，不引入上游运行依赖。
- 0.1.4材料网格、CPU状态和工作量预计时间参考Applied Energistics 2 15.4.10（TeamAppliedEnergistics）的原生接口、界面信息和计算语义，来源见 [上游仓库](https://github.com/AppliedEnergistics/Applied-Energistics-2) 及docs/provenance.json；没有复制上游界面贴图或发行JAR。
- Quality Food 2.3.0：Cadentem，MIT。品质兼容依据实际模组的NBT与叠层机制，使用用户既有客户端导出的基础/星标图集，不随本项目分发模组代码或图像。
- Minecraft、Forge、AE2 作为开发/运行依赖，不复制其游戏或模组发行 JAR到 Git。各项目许可证和 EULA 由其原作者提供。
- SQLite JDBC（org.xerial）：Apache-2.0 / BSD，随服务端发行；SQLite 本体为 public domain。JAR 内原生驱动及许可证保留。
- SQLite Forge adapter：kosmolot-mods/minecraft-sqlite-jdbc，Apache-2.0。内嵌官方 `3.36.0.3+20211227-all` 发行文件，SHA-256 `9d13d1a0cab35216d3a0f7f1dc46f49e90f1f9d6421b8a872427e26b58336e40`。通过 Forge Jar-in-Jar 解析，与整合包现有相同驱动共用，不展开或重定位 JNI 类。
- Guava：Apache-2.0；Gson：Apache-2.0；SLF4J：MIT；这些由游戏/Forge 提供或用于测试，不将日志实现打入 Mod。
- Vue：MIT；Vite：MIT；TypeScript：Apache-2.0；ECharts：Apache-2.0；前端依赖由锁文件记录，发行构建保留工具生成的许可证声明。
- pinyin-pro 3.29.5：zh-lx，MIT，用于浏览器Worker中的拼音全拼与首字母搜索，版本及完整性由前端lockfile锁定。Worker依赖未进入Vite主包许可证汇总，因此完整原始许可另随`web/pinyin-pro-LICENSE.txt`分发。
- JUnit：EPL-2.0；Vitest、Playwright、Prettier：各自发行包许可证，仅用于开发/测试。

客户端导出的整合包名称/图片属于相应作者和资源包的资产，不纳入本项目 Git 或公共发行。
