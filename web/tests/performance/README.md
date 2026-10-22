# 浏览器性能复现

从 `web/` 运行。使用现有锁文件及项目 Chromium；所有库存、网络及历史都是本地合成数据，测试不会连接游戏服务器。

```powershell
node node_modules/vite/bin/vite.js build --outDir ../.local/perf-dist
node tools/browser.mjs test --config tests/performance/playwright.config.ts
```

测试在独立的 `127.0.0.1:4182` 提供构建快照，不受开发服务器 HMR 影响。先确认该端口未被其他任务占用；配置允许复用已启动的专用预览服务。如果当前环境的 Playwright 托管服务退出较慢，可以先在另一个终端运行以下命令，测试完成后手动结束该专用进程：

```powershell
node node_modules/vite/bin/vite.js preview --outDir ../.local/perf-dist --host 127.0.0.1 --port 4182 --strictPort
```

默认分别对网格与列表执行 10,000 项与 100,000 项库存，六条资源历史各 1,200 桶。各进行 20 次不同搜索并测量输入事件至结果完成渲染的时间；随后保持滚动位置并观察自动刷新。100,000 项场景持续真实十分钟，10,000 项场景持续十秒。可用 `--grep grid` 仅运行网格；端口冲突时设置 `AE2_UI_PERF_PORT`，默认仍为 4182。

短验证可显式设置观察时间与独立输出目录，避免覆盖长测试证据：

```powershell
$env:PERF_SECONDS = '10'
$env:PERF_OUTPUT = '../../../.local/performance-short'
node tools/browser.mjs test --config tests/performance/playwright.config.ts
Remove-Item Env:PERF_SECONDS, Env:PERF_OUTPUT
```

每个用例输出 `measurements.json`，包含加载耗时、搜索样本及 P95、rAF 帧间隔、主线程长任务、定期堆与 DOM 采样、API 页数及页面错误。默认输出位于仓库 `.local/performance-browser/`，不得提交这些运行文件。

统计边界：本地 API 路由返回可复现的固定库存和移动时间窗口；浏览器仍完整执行分页、JSON 解析、索引、统计合并、渲染及轮询。主线程 `JSHeapUsedSize` 不包含 Worker 堆，rAF 指标也不能证明服务器采样成本或真实低配置设备表现。此工具不宣称所有库存持续变化；性能比较必须记录两端版本、机器、浏览器和相同输入。没有基线时不计算提速百分比。

2026-10-21 的基线是 Git `2546ba7` 中的 `web/`，用 `git archive` 提取到 `.local/` 后使用相同已安装依赖构建，并用本测试的短模式访问其独立预览。基线显示六条数量线，新版默认同时显示六条数量线和六条速率线；两端功能负载的差异须随结果说明。
