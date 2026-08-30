# Changelog

## Unreleased

- 2.4.1：放宽 Smart Assistant 卡片兼容性判据；不再强制 `Widget version="2"`，
  可选元数据或附属 XML 异常不再误拦正常卡片，同时保留 ZIP 路径、解压大小和 XML
  外部实体等安全防护。

- 全面迁移管理页到 MIUIX `Scaffold`、TopAppBar、底部导航和标准对话框，补齐返回手势、
  加载/错误态、48 dp 触控目标以及窄屏布局。
- Host API 增加签名权限与调用方身份校验；卡片和壁纸的导入、替换、删除改用严格归属、
  流式大小限制、原子写入和可恢复事务。
- 扫描包内全部 XML；壁纸拒绝 Intent、外部命令、反射、外部数据和系统控制能力，卡片在
  导入前展示完整风险确认。
- 壁纸的当前项只采用宿主 widget 列表和索引的权威读回，未知或切换中的状态一律禁止删除。
- 更新器改为规范 GitHub Release、语义版本、包名与签名谱系校验，并限制下载与重定向。
- 升级 Gradle 9.6.1、compileSdk 37，固定 CI action 提交，关闭应用数据备份并扩展安全测试。

## 2.4.0 - 2026-08-25

- 修复用户从系统背屏中心直接移除卡片后，OuterView 刷新再次强制恢复卡片的问题。
- 修复系统侧切换到非 OuterView 壁纸后，旧的当前标记覆盖外部选择和阻止删除的问题。
- 修复壁纸应用失败时提前写入当前标记造成的状态不一致。
- 移除关于页面中不必要的项目身份说明文本。
- 实现来源声明：自本版本起，代码库作为 LLM 辅助重写版本维护，由版权所有者
  Orynnx 按 GNU GPL-3.0 发布；与 REAREye 全部历史源码的表达独立性由 CI 相似度
  门禁（精确文件、20 连续行、120 token、300 结构 token、函数级）持续校验。
- 修正根 NOTICE.md 的许可证表述，与 LICENSE（GPL-3.0）保持一致。
- `.gitattributes` 锁定 `*.xml`、`*.json` 为 LF 行尾，Hello Card 构建在 Windows
  与 Linux 上字节级可复现。
- CHANGELOG 移除过期的 3.0.0 条目，许可证迁移以 2.3.2 为准；`docs/RELEASE_2.x`
  两份发布公告转为历史公告存档。

## 2.3.2 - 2026-08-24

- 许可证迁移版本：自该版本起的新分发由版权所有者 Orynnx 按 GNU GPL-3.0 发布；
  此前已按其他许可证发布或取得的副本仍按其取得时的条款处理，详见
  `docs/LICENSE_TRANSITION.md`。
- 移除 DexKit、MMKV 和复制自 REAREye 的四个工具文件，以项目自有 DEX 查询器和
  BSD-3-Clause 的 `smali-dexlib2` 重新实现 HyperOS 4 Hook 入口发现与缓存。
- 将 Core/AIDL 命名空间迁移至 `org.orynnx.outerview.core`，新卡片改用
  `outerview_custom_` 标识；旧标识只保留受控数据迁移兼容。
- 删除许可证无法随包核验的 Dino Run 媒体，加入只含原创 MAML 图形和文本的 Hello Card。
- 对 REAREye 全部历史提交建立相似代码门禁，并加入实际解析 runtime classpath 的
  依赖许可证审计工具。
- 兼容 HyperOS 4 的 Smart Assistant 原生 Post Runnable：运行时解析 MainPanel 宿主对象，恢复自定义卡片启用和加载。
- 修复 HyperOS 4 背屏壁纸 Widget 工厂的定位规则：按运行时规格类型和 `snapshotPath_` 解析，恢复自定义壁纸注入、原生切换与选择持久化。
- 在已 Root 的 HyperOS 4 真机上验证卡片和壁纸均可由背屏中心原生加载。

## 2.3.1 - 2026-07-14

- 新增基于 GitHub Release 的应用内更新检查与下载，下载文件使用专属 FileProvider 安全交付系统安装器。
- 补齐联网权限声明，并在 About 页面提供版本检查入口和当前版本信息。
- 修复 Assistant 与 Wallpaper 页面导航、宿主回调和状态刷新之间的生命周期竞态。
- 改进卡片隐藏、删除和壁纸应用失败时的错误提示与恢复路径，避免误报成功或状态回退。
- 继续保持系统壁纸、REAREye 壁纸和其他第三方资源只读，只管理 OuterView 自有资源。

## 2.3.0 - 2026-07-12

- 新增独立背屏壁纸管理、友好名称、重命名与安全导入/删除。
- OuterView 壁纸可进入系统背屏列表并由系统应用，同时保持资源所有权边界。
- Assistant、Wallpaper、About 改为底部导航，并恢复 Assistant Hook 与 Wallpaper Hook 共存。
- 深空时钟采用小米 17 Pro/Max 摄像头安全区布局。
- 加入现代页面转场和卡片尺寸动画。

## 2.2.1 - 2026-07-11

- 修复应用启动和手动刷新时，宿主卡片状态未自动加载的问题。
- 刷新前主动同步已启用卡片，避免状态对账将尚未恢复的卡片误判为隐藏或未安装。
- 自动修复“模板已部署到宿主”但仍显示“重试”的历史卡片记录。
- Host API 升级到 v4，新增卡片状态同步接口。

## 2.2.0 - 2026-07-10

- 显示和隐藏改由宿主 Hook 直接调用小米 Smart Assistant 原生运行管线，不再发布 Android 通知。
- Host API 升级为 v3，新增 `activateCard` 和 `deactivateCard`，并持久化启用状态与 payload。
- 宿主 manager 重建后自动恢复已启用卡片；升级时自动迁移并清理 2.1.x 遗留通知。
- 移除通知权限和通知栏生命周期依赖，划除通知不再影响背屏卡片。
- 删除卡片时等待宿主 runtime 确认移除后再删除模板，避免背屏残留。
- 新增“删除全部卡片”，同时清理 OuterView 的 runtime、宿主模板、本地 ZIP 与遗留记录。
- 卡片生命周期操作全局串行执行，操作提示在完成前保持显示，避免相邻卡片操作互相覆盖。

## 2.1.1 - 2026-07-10

- 修复导入并安装成功后，列表要等 Snackbar 消失才刷新的问题。

## 2.1.0 - 2026-07-10

- 独立应用包名迁移为 `org.orynnx.outerview`，Binder provider、Hook 白名单和 Xposed 入口同步更新。

- 主界面收敛为单屏自定义卡片列表，不再展示系统模板和独立诊断页。
- 导入或替换 ZIP 后自动安装，无风险模板不再要求额外确认。
- 显示与隐藏统一为一个开关，永久删除会自动隐藏并清理宿主与本地数据。
- 安装失败时保留本地模板，并提供明确的重试入口。
- Payload、模板替换和诊断移入卡片更多菜单。
- 核心高层 API 升级到 2.0.0，移除系统探针和手动安装、卸载端点。

## 2.0.0 - 2026-07-10

- 独立为可在 LSPosed 管理器中启用的背屏卡片管理模块。
- 使用真实静默通知接入小米 Smart Assistant 原生生命周期。
- 支持严格 Widget v2 ZIP 校验、导入、安装、显示、隐藏、卸载和永久删除。
- 宿主进程负责部署无扩展名模板并维护专属 registry，不修改系统 `notification_widget.json`。
- 新增系统模板只读探针、Host API v2 校验和完整运行诊断。
- 抽离无 Compose 的 `fun-card-core` 1.0.0 AAR，公开 `RearCardManagementEndpoints`。
- 示例 Compose UI 全面迁移到核心端点，便于独立进行视觉与交互重构。
- 移除无效的旧 FunCards 和旧 Root/目录部署链路。
