# Core API 与开发

本文针对正式版本 `3.0.0`。新 API 位于 `org.orynnx.outerview.core.ai`；旧助手、壁纸管理 API 不再提供。

## 模块与环境

- `core/`：无 Compose 的 Android Library，包含公共模型、导入校验、预览会话、Binder 客户端及安全辅助代码。
- `app/`：Compose 管理页、应用更新功能，以及运行在 `com.android.thememanager` 的 LSPosed Hook。
- `demo/tap-counter/`：原创 JsCanvas 学习样例与 Python 标准库打包脚本。

构建要求 JDK 17、Android SDK 37。当前 minSdk/targetSdk 为 36，compileSdk 为 37。Gradle Wrapper 由仓库提供：

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest
./gradlew :core:lintDebug :app:lintDebug :app:assembleDebug
python3 demo/tap-counter/build_example.py
```

Windows 对应使用 `.\gradlew.bat` 和 `py -3`，将 `JAVA_HOME` 指向 JDK 17。Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`。这些命令是验证入口，不表示本文已经执行并通过。

## 公共端点

```kotlin
val manager = AiAppManager.create(context)

val snapshot: AiAppSnapshot = manager.snapshot()
val preview: AiImportPreview = manager.inspect(uri) // 无效包会抛异常
val imported: AiActionResult = manager.importCard(preview, displayName = preview.name)
val restored: AiActionResult = manager.restore(cardId)
val removed: AiActionResult = manager.remove(cardId)
val opened: Boolean = manager.openSystemManager(context)
manager.discardPreview(preview.token) // 普通函数，可用于取消预览
```

除 `create` 与 `discardPreview` 外，上述端点都是 suspend；内部把文件和 Binder 工作切到 IO。UI 仍应串行动作、禁用重复提交，并在操作后刷新。

模型：

```kotlin
AiCard(id: String, name: String, resourcePath: String, managed: Boolean, registered: Boolean = true)
AiAppSnapshot(connected: Boolean, cards: List<AiCard>, message: String)
AiImportPreview(token: String, name: String, warnings: List<String>)
AiActionResult(success: Boolean, message: String, pending: Boolean = false)
```

`managed` 标记本地导入来源，不限制用户对原生 AI 应用的移除。`registered` 表示该应用存在于背屏持久化登记中；仅保留在系统管理库、缺少持久化登记的条目为 false。UI 分别显示“已在背屏登记”或“未在背屏登记”，不能据此断言应用当前正在屏幕上渲染。读取列表不触发清理。用户确认后可调用 `restore(cardId)` 使用原 ID、记录与资源恢复登记，或调用 `remove(cardId)` 移除。`snapshot` 的连接失败不能解释成“系统没有应用”，UI 应呈现其 `message`，并禁用对旧列表直接操作。

## 预览会话与失败处理

`inspect` 只建立私有临时副本，不修改主题宿主。最多同时保存 4 个预览，30 分钟失效，token 与文件指纹绑定。

`importCard` 开始时消费 token，无论成功或失败都不允许重用。UI 失败后应关闭旧预览并展示原因，先引导刷新列表或在系统管理页确认结果；回滚未确认也不能视为“没有导入”。确认可再次尝试后，必须重新选择并检查文件。若返回 `pending=true`，应明确标记操作结果尚未确认；先刷新，不能把等待超时解释成明确失败并立即重复导入。

恢复和移除失败时保留对话框中的原因，提供“刷新列表”入口关闭旧目标并重新读取状态。刷新后应重新选择应用，不能直接重试陈旧目标；回前台刷新发现目标已变化时，同样需要重新选择。

取消预览或页面退出时调用 `discardPreview`；不使用 token 拼接文件路径，不读取或改写宿主数据库。

## 共享 Parser

```kotlin
val parsed = AiPackageParser.parse(file)
// name, mamlZipBytes, appIconBytes?, previewBytes?, warnings
```

Parser 是可进行 JVM 单元测试的共享逻辑，校验失败抛 `IllegalArgumentException`。支持格式及限额见 [应用包开发](CARD_DEVELOPMENT.md)。宿主接收原始 PFD、限时限量复制后必须再调用 parser，不能信任客户端已检查的声明。

返回的 `mamlZipBytes` 是实际内层资源，缺省图标/预览为 null。临时嵌套校验文件由 parser 生成并清理；不会按 ZIP 内文件名向目标目录解压。

## Host API v1

契约：

```text
providerPackage: org.orynnx.outerview
hostPackage: com.android.thememanager
permission: org.orynnx.outerview.permission.ACCESS_HOST_API
action: org.orynnx.outerview.action.REQUEST_AI_APP_HOST_SERVICE
bundle extra: hostApiBundle
callback key: callback
```

`IAiAppHostConnection.onServiceConnected` 返回 `IAiAppHostService`：

```text
Bundle getCapabilities()
Bundle listCards()
Bundle importCard(ParcelFileDescriptor packageFd, String displayName)
Bundle removeCard(String cardId)
Bundle restoreCard(String cardId)
Bundle openSystemManager()
```

公共 Bundle keys 为 `apiVersion/providerPackage/ready/success/message/items/id/name/resourcePath/managed/registered/pending`。列表与动作必须返回明确的 success/message；空 Bundle 不是成功。主题原生管理页有内部权限，由宿主调用自身 Activity。

外部 UI 不能只替换 provider 包名：客户端、签名权限、Hook调用方检查和契约必须成套调整。建议先在仓库内通过 `implementation(project(":core"))` 接入。

本地 AAR 可用 `./gradlew :core:publishReleasePublicationToMavenLocal` 生成，当前坐标为 `org.orynnx.outerview:ai-app-core:3.0.0`。这是本地发布任务，不表示远程仓库存在该版本。

## 调试与验收

```bash
adb logcat -s OuterView-AiApp ThemeFileUtils SubScreenCenter_Service
```

分别验证宿主连接、资源校验、原生反射接口、导入后 Room/JSON/widget 读回、背屏显示和操作、移除后完整状态。协议单测与构建不能证明目标主题版本的私有接口兼容；当前实现遇到不明确的结构会失败关闭。

本轮不自动迁移或删除旧卡片、旧壁纸和旧 registry。研究记录、历史发布说明及旧 API 文档内容不能充当新接口的运行证据。
