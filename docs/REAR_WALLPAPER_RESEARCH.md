# 背屏壁纸加载研究

## 结论

背屏 Wallpaper 与 Smart Assistant 卡片是两套运行时。卡片使用
`subscreencenter/smart_assistant`、通知语义和卡片 manager；壁纸由背屏中心读取
`/data/system/theme_magic/users/<userId>/rearScreen/runtime.json`，将记录转换为壁纸 spec/widget，
再由 MainPanel 按索引选择。OuterView 必须提供独立 Wallpaper Host API，不能复用卡片 Post/Remove。

本研究以 REAREye 当前实现为只读参考，没有复制其 Hook。首阶段加入的代码仅为纯 Kotlin
数据与安全原型，尚未连接 Binder、宿主 Hook 或 UI；因此现有 OuterView 页面仍然只是 Smart Assistant
卡片管理页，不能被视为壁纸管理功能。

## 系统链路与数据

1. 系统默认资源来自 `/system/media/rearscreen/template/default/rearScreen.json`；下载或导入资源登记在
   `rearScreen/runtime.json`。
2. 每条 runtime 记录以 `resId + applyId` 标识，常用字段包括 `resLocalPath`、`metaPath`、
   `resPreviewPath`、`position`、`editable`、`isThirdParties` 与 `supportAon`。
3. 背屏中心内部读取默认配置和 runtime，生成混淆的 wallpaper spec，再经 widget factory 创建实例。
4. MainPanel 持有 widget 列表与选中索引；应用壁纸需要保存索引后在主线程调用原生选择方法。
5. 主题管理器维护自己的资源列表。注入该列表只影响主题管理器可见性，不是背屏显示的必要条件，
   因而不进入 OuterView 首版。

## 必要 Hook 与兼容策略

需要在 `com.xiaomi.subscreencenter` 解析四类锚点：读取 runtime/default JSON 的方法、spec 到 widget
的 factory、MainPanel 的 widget/索引字段及选择方法、刷新后可用于确认状态的运行列表。应使用字符串和
方法签名联合 DexKit 定位并按宿主版本缓存；任一关键锚点缺失时 Host API 返回“不支持”，不得写 runtime。

Android 16 的小米 17 Pro/Max 是当前唯一目标。系统更新可能改变混淆名、runtime schema、文件权限和
MainPanel 生命周期，因此必须在真机确认后才启用写操作。ThemeManager 同步留作后续可选模块。

## MVP 调用链

- 导入：管理端把 MRC/ZIP 通过只读 FD 交给宿主；宿主严格校验，写入
  `outerview_wallpaper_<id>_<applyId>` 专属目录，原子追加 runtime，刷新并确认列表出现。
- 列举：Host API 返回全部壁纸的只读摘要，但管理动作只对通过专属前缀和规范路径双重校验的记录开放。
- 应用：确认目标存在且不在编辑态，保存选择索引，主线程调用 MainPanel 原生方法，并等待当前索引确认。
- 删除：若目标正在使用，先切换到可用的非目标壁纸；原子移除 runtime 记录并确认宿主刷新，最后删除
  专属目录。确认失败时保留文件用于恢复。

## API 草案

独立 Binder 服务建议提供 `getCapabilities()`、`listWallpapers()`、`importWallpaper(fd, name)`、
`applyWallpaper(id)` 和 `deleteWallpaper(id)`。结果统一包含状态码、可读错误和宿主版本；导入返回稳定的
OuterView ID。接口不接受任意文件路径，也不暴露“删除系统/第三方壁纸”能力。

## 安全边界

- 压缩包上限 32 MB、展开上限 128 MB、最多 2048 项，descriptor 上限 2 MB。
- 拒绝绝对路径、盘符和 `..` 路径；XML 禁止 DOCTYPE、ENTITY 和外部实体。
- MAML 包必须有顶层 `manifest.xml` 或兼容 `config.xml`。真机已验证的背屏壁纸 MRC 使用
  `<Widget version="1" type="awesome">`，这与 Smart Assistant 卡片的 `<Widget version="2">` 不同；
  校验可接受壁纸 MAML 根节点，但绝不按 Smart Assistant 版本规则解析。
- runtime 更新必须保留未知字段与非 OuterView 记录，采用同目录临时文件和原子替换。
- 资源归属同时校验 `outerview_wallpaper_` 前缀与 canonical path；Smart Assistant、系统、REAREye
  和其他模块资源永远只读。

## 非首版范围与真机验收

首版不包含轮播、metadata/模板变量编辑、主题商店同步或第三方资源删除。真机验证需覆盖导入、列举、
应用、重启恢复、当前壁纸删除，以及损坏包、重复导入、宿主未启动、锚点失效、runtime 写入失败；每项
还需确认原有 Smart Assistant 卡片行为无回归。
