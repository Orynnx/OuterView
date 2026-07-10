# 二次开发指南

## 模块边界

`core` 是无 Compose 依赖的 Android Library，负责导入、registry、状态机、Host Binder 客户端和诊断模型。`app` 提供参考 Compose UI，并以 LSPosed Hook 的形式在 `com.xiaomi.subscreencenter` 中实现 Host API。

第三方 UI 应调用 `RearCardManagementEndpoints`，不要直接依赖 `FunCardRepository` 或 Hook 内部类。

## 接入 Core

当前仓库内可直接依赖：

```kotlin
dependencies {
    implementation(project(":core"))
}
```

发布本地 AAR：

```bash
./gradlew :core:publishReleasePublicationToMavenLocal
```

Maven 坐标为：

```kotlin
implementation("org.orynnx.outerview:fun-card-core:3.0.0")
```

## 高层端点

```kotlin
val manager: RearCardManagementEndpoints = RearCardManager.create(context)

val snapshot = manager.refresh()
val preview = manager.inspectImport(uri, displayName)
val installed = manager.importAndInstall(preview.value!!.token)
val shown = manager.setVisible(cardId, true)
val hidden = manager.setVisible(cardId, false)
val deleted = manager.deleteCard(cardId)
val cleared = manager.deleteAllCards()
```

还提供：

- `discardImport(token)`：放弃预检产生的临时文件。
- `retryInstall(cardId)`：自动安装失败后重试，不要求重新选择 ZIP。
- `replaceAndInstall(cardId, token)`：必要时先隐藏，替换模板后保持隐藏。
- `updatePayload(...)`：更新普通 `maml_config` 或高级 rear/focus JSON。
- `diagnostics(cardId)`：返回 Host、模板、manager list 和 MAML 加载证据。

## 推荐状态管理

每次生命周期动作都应串行执行，并在动作结束后 `refresh()`：

```kotlin
scope.launch {
    if (working) return@launch
    working = true
    try {
        val result = manager.setVisible(cardId, visible)
        state = manager.refresh()
        messages.emit(result.message)
    } finally {
        working = false
    }
}
```

状态含义：

- `NOT_INSTALLED`：App 私有目录有 ZIP，宿主尚未部署。
- `INSTALLED_DISABLED`：宿主模板存在，runtime 未激活。
- `INSTALLED_ENABLED`：runtime、manager list 与加载证据一致。
- `ERROR`：期望状态与宿主证据不一致，UI 应展示 `lastMessage` 和重试入口。

删除必须保持操作提示，直到 Host 确认 manager list 中 business 消失。不要在同一时刻对两张卡执行显示、隐藏、替换或删除。

## Host API

Host API 当前版本为 3，由签名权限保护，并校验 provider 包名和实例 ID。主要 Binder 方法包括模板安装/卸载、runtime 激活/移除、全部清理和诊断。客户端会把空 Bundle、版本不匹配和连接失败转换为结果对象，不向 UI 抛 Binder 异常。

二次开发时可以替换整个 UI，但 APK 包名、签名权限、Hook provider 与客户端契约必须成套调整，否则客户端会拒绝连接。

## 日志

```bash
adb logcat -s FunCardManager OuterView-Hook FunCardManager-Hook
```

关键诊断字段包括 `runtimeActivated`、`managerListContains`、`liveWidgetContains`、`loadSucceeded`、`actualTemplatePath` 和 `lastCommandId`。
