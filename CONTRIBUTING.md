# 贡献指南

1. 从 `main` 创建短分支，保持修改集中。
2. 不提交签名密钥、设备私有数据、无法确认再分发权利的媒体或第三方反编译源码。
3. 新代码不得复制 REAREye 或其他非宽松许可项目的实现；只提交可独立说明来源的代码。
4. 新运行时依赖必须与 GPL-3.0 兼容；优先选用 MIT、BSD、Apache-2.0 等宽松许可证，
   并更新 `LICENSES/NOTICE.md` 和对应完整通知。
5. 行为变化同步更新 README、Changelog 或对应文档。

## 提交前验证

以下命令从仓库根目录运行，要求 JDK 17、Android SDK 37（SDK 包名
`platforms;android-37.0`）、Python 3 和 Node.js，与当前 3.0 开发入口一致：

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest
./gradlew :core:lintDebug :app:lintDebug :app:assembleDebug
python3 demo/tap-counter/build_example.py
node --test demo/credex-account/test.js
```

Windows 使用 `.\gradlew.bat`，Python 命令改用 `py -3`。轻触计数器脚本没有
`--check` 参数；生成的包用于本地验证，不应作为本次文档或代码修改的附件提交。

来源分离与运行时许可证检查仍需保留：

```powershell
py -3 tools/audit_reareye_similarity.py --reareye C:\path\to\REAREye
.\tools\verify-runtime-licenses.ps1
```

来源审计需要本地 REAREye Git 仓库及历史 refs；CI 的固定基线和获取步骤见
[Android CI](.github/workflows/android.yml)。Linux/macOS 可用 `python3`，许可证脚本
需要 PowerShell，并传入 `-GradleExecutable ./gradlew`。这些是验证要求，提交说明应
写明实际运行结果及未执行原因，不能把命令存在当作检查通过。

## Hook 与设备取证

当前 Hook 宿主和 LSPosed 作用域为主题壁纸 **`com.android.thememanager`**。
兼容改动需记录设备型号、HyperOS/Android 版本、主题壁纸 versionName/versionCode、
OuterView 提交或版本、LSPosed 版本和作用域，以及经过脱敏的运行证据。
若问题涉及背屏服务，再补充 `com.xiaomi.subscreencenter` 版本；它不是当前 Hook 宿主。
当前实现仅支持主用户 0，第二空间应记录为不支持。

可用以下日志入口，结合必要的 LSPosed 日志取证：

```bash
adb logcat -s OuterView-AiApp ThemeFileUtils SubScreenCenter_Service
```

按改动范围逐项记录宿主连接与原生接口解析、ZIP/MRC 预检与宿主复检、导入、恢复、
移除，以及 Room、`runtimeAiApp.json`、背屏持久化登记和服务 widget 的读回结果。
同时检查其他条目的字段和顺序是否保持、资源是否按对应分支清理或保留；
背屏渲染、触摸、存储和休眠恢复需单独实机验证。超时或 `pending=true` 后先刷新
核对实际状态，不立即重复导入；原生调用失败不能当作已自动回滚。

当前 README 仅记录 Xiaomi 17 Pro 管理入口获用户确认；恢复功能尚未完成真机验收。
Credex 的逻辑测试、浏览器预览和包解析验证不能证明手机原生渲染、读取按钮或
Provider 通知已通过，这些仍待实测。提交说明须区分本地检查与设备结果。

## 历史边界

`demo/hello-card/build_card.py --check` 仍存在，可用于检查保留的旧示例，
但不是 3.0 JsCanvas 智能应用的验证入口。Assistant/Wallpaper Host API、
`notification_widget.json` 和旧 registry 的说明属于 2.x 研究与历史约束，
不能作为当前导入、恢复或移除流程的验收依据；旧资源不自动迁移或删除。
Credex 中的 Assistant 展示源及 `/quota/assistant` 是上游数据接口名称，
不表示 OuterView 重新启用了旧助手管理路线。

贡献代码默认按 GNU GPL-3.0 授权。当前实现与边界见
[开发文档](docs/DEVELOPMENT.md)、[架构](docs/ARCHITECTURE.md) 和 [安全策略](SECURITY.md)。
