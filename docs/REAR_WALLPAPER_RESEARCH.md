# 背屏壁纸加载研究

## 结论

背屏 Wallpaper 与 Smart Assistant 卡片是两套运行时。卡片使用
`subscreencenter/smart_assistant`、通知语义和卡片 manager；壁纸由背屏中心读取
`/data/system/theme_magic/users/<userId>/rearScreen/runtime.json`，将记录转换为壁纸 spec/widget，
再由 MainPanel 按索引选择。OuterView 必须提供独立 Wallpaper Host API，不能复用卡片 Post/Remove。

本研究以固定版本的 REAREye 实现为只读参考，没有复制其 Hook。当前实现已连接独立 Binder、
宿主 Hook 和 MIUIX 管理页；写操作仍以动态解析出的宿主锚点、严格资源归属和操作后读回确认为边界。

## 系统链路与数据

1. 系统默认资源来自 `/system/media/rearscreen/template/default/rearScreen.json`；下载或导入资源登记在
   `rearScreen/runtime.json`。
2. 每条 runtime 记录以 `resId + applyId` 标识，常用字段包括 `resLocalPath`、`metaPath`、
   `resPreviewPath`、`position`、`editable`、`isThirdParties` 与 `supportAon`。
3. 背屏中心内部读取默认配置和 runtime，生成混淆的 wallpaper spec，再经 widget factory 创建实例。
4. MainPanel 持有 widget 列表与选中索引；应用壁纸需要保存索引后在主线程调用原生选择方法。
5. 主题管理器维护自己的资源列表。注入该列表只影响主题管理器可见性，不是背屏显示的必要条件，
   因而不进入 OuterView 当前版本。

## 必要 Hook 与兼容策略

需要在 `com.xiaomi.subscreencenter` 解析四类锚点：读取 runtime/default JSON 的方法、spec 到 widget
的 factory、MainPanel 的 widget/索引字段及选择方法、刷新后可用于确认状态的运行列表。应使用字符串和
方法签名和稳定字符串由 OuterView 自有 `HostDexResolver` 联合定位，并按宿主 APK SHA-256 与版本缓存；任一关键锚点缺失时 Host API 返回“不支持”，不得写 runtime。解析器只使用 BSD-3-Clause 的 `smali-dexlib2` 读取 DEX，不加载 native 库。

Android 16 的小米 17 Pro/Max 是当前唯一目标。系统更新可能改变混淆名、runtime schema、文件权限和
MainPanel 生命周期，因此必须在真机确认后才启用写操作。ThemeManager 同步留作后续可选模块。

## MVP 调用链

- 导入：管理端先把 MRC/ZIP 限量、限时复制到应用私有缓存，再把普通只读 FD 交给宿主；宿主二次严格校验，写入
  `outerview_wallpaper_<id>_<applyId>` 专属目录，原子追加 runtime，刷新并确认列表出现。
- 列举：Host API 返回全部壁纸的只读摘要，但管理动作只对通过专属前缀和规范路径双重校验的记录开放。
- 应用：确认目标存在且不在编辑态，保存选择索引，主线程调用 MainPanel 原生方法，并等待当前索引确认。
- 删除：只有宿主当前 widget 列表与选中索引能唯一确认目标并非当前项时才允许；当前项必须先由用户
  应用另一张壁纸。随后原子移除 runtime 记录，专属目录用持久化 tombstone 完成清理。

## API 草案

独立 Binder 服务建议提供 `getCapabilities()`、`listWallpapers()`、`importWallpaper(fd, name)`、
`applyWallpaper(id)` 和 `deleteWallpaper(id)`。结果统一包含状态码、可读错误和宿主版本；导入返回稳定的
OuterView ID。接口不接受任意文件路径，也不暴露“删除系统/第三方壁纸”能力。

## 安全边界

- 压缩包上限 32 MB、展开上限 128 MB、最多 2048 项，descriptor 上限 2 MB。
- 拒绝绝对路径、盘符和 `..` 路径；XML 禁止 DOCTYPE、ENTITY 和外部实体。
- 扫描包内全部 XML，拒绝 Intent/外部命令、反射、外部数据 Binder 以及蓝牙、移动数据、铃声和
  Wi-Fi 等系统控制命令；壁纸导入不提供“仍然继续”的绕过入口。
- MAML 包必须有顶层 `manifest.xml` 或兼容 `config.xml`。真机已验证的背屏壁纸 MRC 使用
  `<Widget version="1" type="awesome">`，这与 Smart Assistant 卡片的 `<Widget version="2">` 不同；
  校验可接受壁纸 MAML 根节点，但绝不按 Smart Assistant 版本规则解析。
- runtime 更新必须保留未知字段与非 OuterView 记录，采用同目录临时文件和原子替换。
- 资源归属同时校验 `outerview_wallpaper_` 前缀与 canonical path；Smart Assistant、系统、REAREye
  和其他模块资源永远只读。

## 当前限制与真机验收

当前版本不包含轮播、metadata/模板变量编辑、主题商店同步或第三方资源删除。真机验证需覆盖导入、列举、
应用、重启恢复、当前壁纸删除，以及损坏包、重复导入、宿主未启动、锚点失效、runtime 写入失败；每项
还需确认原有 Smart Assistant 卡片行为无回归。
