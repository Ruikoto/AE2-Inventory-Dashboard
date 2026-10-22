# 0.1.4-dev 实施约定

用户于2026-10-22批准。实施仓库为AE2-Inventory-Dashboard，沿用Vue MVVM、游戏线程原生访问、SQLite单写线程和既有身份/统计契约。本轮交付源码、验证记录和本地开发版JAR，不提交、推送、上传或重启生产服务器。

## 显示与品质

- NativeItemIdentity抽出共用标签散列入口，保持黄金向量不变。QualityFoodDisplay解析线程独占的持久化SNBT，只承认quality_food.quality整数1/2/3；移除整个quality_food，保留其他NBT/caps，空tag移除，计算基础精确ID。不创建AEKey/ItemStack，不访问世界或注册表，不修改原身份。
- 显示优先级为有效精确映射、基础精确映射加品质overlay、原占位。不得对任意NBT回退group。名称沿用游戏基础名，tooltip标品质；精确星标图不重复叠加。
- 服务端共享显示解析器覆盖库存、详情、预览、CPU材料、订单，解析缓存最多16384项，只缓存普通结果。
- HistoryStore.resources/networkResources/lastInventory只为品质候选返回内部SNBT，标签解析确认语义；不升级schema、不回填身份。HTTP不得输出SNBT。
- Crafting.Item携带可选基础显示ID和品质。旧订单按页批量查询资源增强，缺失旧资源保留已存名称；不持久化图集坐标。非法显示身份有简短诊断但不影响统计。
- ResourceIcon增加size默认28；容器、缩放、偏移、overlay统一尺寸。库存网格传32，删除独立CSS覆盖。客户端渲染器与现有显示包不变。

## 登录与目录

- 密码登录固定30天，取消1小时闲置/12小时期限，不滚动续期；POST session仍为password。随机HttpOnly Cookie，Max-Age=2592000，保留SameSite、Secure配置和同源限制；Web Storage不存凭据。
- config/ae2-dashboard-sessions.json保存version、密码指纹、最多256项tokenHash/expiresAt，上限64KiB。普通授权不写盘。登录/退出先原子发布再更新内存/Cookie，密码改变撤销旧凭据，public不读写文件。缺文件允许首次使用；坏文件保留且HTTP初始化明确失败、采样继续；写入失败SESSION_STORAGE_UNAVAILABLE，不伪装成功。路径显式注入，测试/演示隔离，无新写线程。
- 登录防重复提交；连接/限流/存储失败保留输入，成功或密码错误清除；不自动重放合成写入。
- Preferences.availability=all|stock|craftable默认all；stock数量>0，craftable包含零库存。与其他筛选取交集，在Worker处理，不重置统计/选择。
- 全部/物品/流体与全部/库存/可合成采用图标分段开关，中文hover/focus tooltip、方向键与清晰焦点，移动端整组换行。other旧偏好转all，全部继续含其他类型。
- 空结果增加清除筛选，仅清搜索与目录筛选，保留排序/NBT/收藏/统计。
- 用户实施中追加：目录搜索支持拼音全拼与首字母的子串匹配，例如小麦种子可用xiaomai或xmzz查询。保留中文/英文/注册ID搜索；使用本地打包的pinyin-pro3.29.5，在Worker建立索引并缓存转换，不向外部服务发送名称，不枚举所有多音字组合。补充中文、大小写、混合名称、全拼/首字母及100k目录响应验证。

## 原生合成与接口

- 活预览每网络3、全局32，原生命令队列32、每tick4条不变。calculating/ready/missing租期30秒，GET续期；计算绝对60秒，完成后绝对15分钟。额度检查前清理到期项。
- failed/expired/cancelled立即释放活名额；终态结果全局32、保留60秒，只淘汰终态。持久意图进入原生提交后finally消费预览，覆盖CPU检查、成功/失败/未知；未知订单不重下，requestId幂等不变。
- 重新预览先等待DELETE，未确认则说明租期释放，不不断新建。关闭尽力DELETE，丢失POST响应孤儿最多30秒。计算每秒GET，ready/missing每2秒并刷新CPU；隐藏停止、过期保留输入要求重新预览。
- 分开网络/全局预览满、队列满、提交容量错误，429和Retry-After，按原因限频日志，无凭据/NBT。
- CPU摘要增加自动选择资格、原生任务UUID、输出、工作量/耗时、Dashboard关联。自动/手动均遵循AE原生资格与容量，提交时重验。
- 新增GET /networks/{id}/crafting/cpus/status?cpuId=...&taskId=...&offset=0&limit=120&version=...，返回采样时间/版本/状态/工作量/耗时/cantStoreItems及材料stored/active/pending。
- 每次核验当前网络和任务UUID，替换返回TASK_CHANGED。全部CPU可读，外部任务不导入、不跟踪、不入历史、不可取消。跨网可读当前状态，历史仍归原网且禁止跨网取消。
- getAllItems一次遍历，getStored/getWaitingFor读取，差值得pending，不逐项扫描配方，不增状态Mixin。
- 仅选中CPU读明细；2秒复用不可变快照，LRU最多16份/32MiB，10秒不使用过期。每份最多100k项/8MiB，每页120；版本不符STATUS_CHANGED，不混页。超限保留摘要并明确明细不可完整展示。空闲CPU存料也显示。明细/ETA不写SQLite。
- ETA=elapsedNanos*(totalWork-completedWork)/completedWork，BigInt安全，仅样本更新，无浏览器倒计时；零进度估算中，离线/恢复/过期/cantStoreItems不显示有效ETA。连续30秒无进展显示暂无法估算，不猜缺料；恢复进展重新估算，切任务重置观察窗口。

## 网页合成

- 沿用主题/字体，AE信息布局、紧凑格、轻边框。共用虚拟材料网格最多120格。
- 预览图标/两行名/库存使用/需要合成/缺少，缺料红色且优先，搜索和仅缺少；CPU区显示空间和协处理器。无CPU、全忙、容量不足、读取失败标红禁提交。canSubmit要求ready、无缺料、新鲜CPU和有效目标。
- 进行中左侧全部CPU，右侧材料stored/active/pending、工作量、ETA、真实异常。外部任务标只读，Dashboard取消确认；未绑定CPU的提交中/恢复订单仍有入口。
- 历史仍分页Dashboard订单、统一图标；不伪造历史实时材料。CPU选择稳定，taskId变化立即清旧明细；读取失败保留过期内容并隐藏ETA。只加载可见窗口，移动端紧凑CPU导航保留两栏关系。
- hover/focus tooltip、完整数量、焦点返回、加载状态；刷新不重置滚动/选择/取消目标。

## 验证与交付

顺序：显示/登录→预览/CPU→网页→整体验证；版本0.1.4-dev。

- 品质四级/不同effects/其他NBT和caps/精确优先/旧目录和订单/缺包，原身份统计不变；非零atlas坐标四色邻格验证28/32/材料尺寸裁剪及overlay。
- 登录重开/服务重建/30天/退出/改密/原子写失败/坏文件/限流/重复点击，无Web Storage凭据。
- 筛选零/正库存/可合成/大整数/合并/收藏/无限，偏好恢复，隐藏选中条目。
- 三次CPU拒绝后新预览、丢响应、DELETE失败、租期、两人并发、各限额；缺料/无CPU/自动禁用/不足/抢占。
- 隔离Forge47.4.16/AE2 15.4.10核对原生材料、流体、UUID替换/跨网/恢复/外部不可取消、ETA正常/停滞/恢复/完成，不操作生产世界。
- 网页1440/1024/390深浅主题、键盘、tooltip、切网/隐藏/退出、刷新、100k目录及大材料挂载界限。
- 运行相关Java/native、前端typecheck/单测/浏览器/格式/生产构建，最终Gradle build；不重复源码未变的已通过检查。完整diff与敏感数据审查，更新协议/运维/来源/验证/交接，交付本地JAR，区分隔离验证与生产未验收。

## 并行实现协调（内部数据约定）

- Crafting.Item保留原5字段，追加String baseDisplayId、int quality；旧载荷缺省null/0。HTTP使用统一显示解析器，不输出内部baseDisplayId或原始身份。
- Crafting.Cpu保留原6字段，追加boolean autoSelectable、String taskId、Item output、String outputQuantity、String totalWork、String completedWork、String elapsedNanos、String dashboardOrderId、boolean canCancel。
- Crafting.CpuMaterial(Item item,String stored,String active,String pending)。
- Crafting.CpuStatus(String cpuId,String taskId,long version,long sampledAt,String state,boolean cantStoreItems,String totalWork,String completedWork,String elapsedNanos,int offset,int total,List<CpuMaterial> entries,boolean detailsAvailable,String error)。state为running/idle/unavailable；明细超限时detailsAvailable=false。
- Backend/CraftingService.cpuStatus(network,cpuId,taskId,offset,limit,Long version)返回CompletableFuture<CpuStatus>。version省略为null。HTTP limit最大120，版本冲突409。
- 新增容量代码：NETWORK_PREVIEW_LIMIT、GLOBAL_PREVIEW_LIMIT、CRAFTING_QUEUE_BUSY、SUBMISSION_LIMIT、ACTIVE_ORDER_LIMIT；保留旧CRAFTING_BUSY文案兼容。
- 核心负责display/auth/HistoryStore/HTTP/启动注入；原生负责Crafting模型/CraftingService/AeCrafting及独立原生夹具；网页负责web源代码与网页测试。共享接口变化先沟通，不覆盖他人编辑。
