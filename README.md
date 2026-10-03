<p align="center">
  <img src="branding/outerview-icon.png" width="128" alt="OuterView icon" />
</p>

# OuterView

OuterView 是小米背屏智能应用管理器，通过主题壁纸原生流程导入、恢复和移除本地 JsCanvas 应用包，并与系统 AI 应用管理同步。

当前版本 **3.0.0**。需要 Root、LSPosed，以及带有原生背屏智能应用功能的主题壁纸版本。当前没有 Shizuku 后端；不同 HyperOS 版本的兼容性需要单独验证。

[下载 APK](https://github.com/Orynnx/OuterView/releases/download/v3.0.0/OuterView-3.0.0.apk) · [下载 Credex 背屏应用](https://github.com/Orynnx/OuterView/releases/download/v3.0.0/Credex-account-1.1.0.zip) · [English](README_EN.md)

## 使用

1. 安装 APK，在 LSPosed 中启用 OuterView，作用域选择 **主题壁纸 `com.android.thememanager`**。
2. 重新启动主题壁纸应用，从系统设置的背屏页面，在“应用卡”分组的“AI 生成应用卡”下方点击 **OuterView 智能应用**。系统页面先启动主题宿主，随后打开独立管理 Activity；桌面图标仅显示关于、版本和更新。
3. 选择可信的 ZIP/MRC，查看预检提示、填写名称并确认导入。
4. 对“未在背屏登记”的应用，可确认“恢复显示”以保留原 ID 和资源恢复登记；也可确认移除，或打开“系统智能应用”继续管理。原生应用与本地导入应用均通过系统管理流程处理。

“已在背屏登记”表示持久化登记存在，不等于当前正在背屏上渲染。操作结果未确认或导入失败时，请先刷新列表或到系统智能应用中核对结果，暂勿重复导入；恢复、移除失败后，可从提示框刷新列表，再重新选择应用。

可从原创 [轻触计数器](demo/tap-counter/README.md) 或 [Credex 账户速览](demo/credex-account/README.md) 开始学习。支持 `ContentProviderBinder`、数据绑定和原生命令，不对 MAML 能力做黑名单过滤。

## Credex 背屏应用

在 Credex「设置 → 背屏配置」选择 Assistant 展示源，导入本次 Release 的 `Credex-account-1.1.0.zip`。

- Codex 显示 5 小时与每周额度，支持已用／剩余切换及窗口详情。
- 其他服务显示自己的余额或额度、状态和完整分页详情，保留 Credex 的数值含义。
- 左右滑动翻页、长按隐藏数值与详情、点击读取最新展示数据。

卡片读取和交互本身无需 Root；通过当前 OuterView 导入系统宿主仍需 LSPosed。卡片不自动更新，新版需要重新导入。

![Credex 账户速览演示数据](demo/credex-account/preview.png)

## 应用更新

About 页面读取本仓库最新正式 GitHub Release，支持检查、下载并打开系统安装器。本地 `-dev` 版本可以识别同版本号的正式版；发布附件使用 `OuterView-X.Y.Z.apk`，升级保持相同签名。

此前的 `3.0.0-dev` APK 尚未包含此修复，需要手动安装本次 3.0.0，之后恢复正常检查。旧版 2.x 管理范围与 3.0 不同，升级前请阅读下方说明。

3.0 已移除旧版助手卡片、壁纸管理及其 Host API。**已有旧卡片与壁纸不会被自动删除或迁移**；旧版安装包和历史文档不能视为当前使用说明。

## 构建

使用 JDK 17 和 Android SDK 37：

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
python3 demo/tap-counter/build_example.py
node --test demo/credex-account/test.js
```

Windows 使用 `.\gradlew.bat` 和 `py -3`。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`assembleRelease` 生成经过 R8 和资源压缩的未签名 APK，需要用既有签名密钥签名后发布。

管理入口已在 Xiaomi 17 Pro 获用户确认；Credex 卡片完成逻辑、浏览器和包解析验证，原生运行待实测。构建和单元测试不能代替对应系统版本的设备验证。

## 文档

- [应用包与示例开发](docs/CARD_DEVELOPMENT.md)
- [Core API 与构建](docs/DEVELOPMENT.md)
- [原生接入架构](docs/ARCHITECTURE.md)
- [安全边界](SECURITY.md) · [变更记录](CHANGELOG.md)

源码按 [GNU GPL v3.0](LICENSE) 分发；历史许可证转换说明见 [LICENSE_TRANSITION.md](docs/LICENSE_TRANSITION.md)，第三方通知见 [LICENSES/NOTICE.md](LICENSES/NOTICE.md)。

OuterView 是独立社区项目，与小米公司无隶属或背书关系。小米、HyperOS、MAML 等相关权利归各自权利人所有。
