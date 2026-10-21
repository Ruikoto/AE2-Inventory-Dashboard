# 来源和许可证

- AE2 Web Integration / 旧客户端名称工具：LGPL-3.0-or-later，kuba6000 等原作者，参考及派生来源见 docs/provenance.json。旧身份算法注释保留。
- Minecraft、Forge、AE2 作为开发/运行依赖，不复制其游戏或模组发行 JAR到 Git。各项目许可证和 EULA 由其原作者提供。
- SQLite JDBC（org.xerial）：Apache-2.0 / BSD，随服务端发行；SQLite 本体为 public domain。JAR 内原生驱动及许可证保留。
- SQLite Forge adapter：kosmolot-mods/minecraft-sqlite-jdbc，Apache-2.0。内嵌官方 `3.36.0.3+20211227-all` 发行文件，SHA-256 `9d13d1a0cab35216d3a0f7f1dc46f49e90f1f9d6421b8a872427e26b58336e40`。通过 Forge Jar-in-Jar 解析，与整合包现有相同驱动共用，不展开或重定位 JNI 类。
- Guava：Apache-2.0；Gson：Apache-2.0；SLF4J：MIT；这些由游戏/Forge 提供或用于测试，不将日志实现打入 Mod。
- Vue：MIT；Vite：MIT；TypeScript：Apache-2.0；ECharts：Apache-2.0；前端依赖由锁文件记录，发行构建保留工具生成的许可证声明。
- JUnit：EPL-2.0；Vitest、Playwright、Prettier：各自发行包许可证，仅用于开发/测试。

客户端导出的整合包名称/图片属于相应作者和资源包的资产，不纳入本项目 Git 或公共发行。
