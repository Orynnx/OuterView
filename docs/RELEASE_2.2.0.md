# OuterView 2.2.0

首个独立开源版本。

## 功能

- 独立 LSPosed 模块，包名 `org.orynnx.outerview`。
- 导入并自动安装 Smart Assistant Widget v2 ZIP。
- Host API v3 直接控制宿主 runtime，不发布 Android 通知。
- 显示、隐藏、替换、诊断、单卡删除和全部删除。
- 严格 ZIP/XML 安全校验与 cleanup tombstone。
- 无 Compose 依赖的 `core` 高层 API，便于二次 UI 开发。
- 附带可直接导入和修改的 Dino Run 游戏 Demo。

## 安装

安装 `OuterView-2.2.0.apk`，在 LSPosed 中启用模块并勾选 `com.xiaomi.subscreencenter`，随后重启宿主或设备。

## Release 资产

- `OuterView-2.2.0.apk`：管理器与 LSPosed Hook。
- `dino-run.zip`：小恐龙背屏游戏 Demo，可在 OuterView 中直接导入。

## 已知限制

- 当前只针对小米 17 Pro / 17 Pro Max 的现有背屏中心版本做过真机验证。
- 系统升级可能改变混淆后的 Smart Assistant 反射点，需要重新适配。
- Dino Run 媒体素材具有单独来源声明，不自动适用项目 GPL-3.0。
