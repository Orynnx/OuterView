# Fun Card Core

`fun-card-core` 是背屏卡片管理器的无 UI Android Library。它封装了已经在真机验证通过的完整链路：

```text
ZIP 安全校验 -> App 私有存储 -> Host Hook 部署
-> 宿主原生 Post Runnable -> Smart Assistant manager list -> 原生 MAML loader
```

库本身不依赖 Compose。页面层只需要调用 `RearCardManagementEndpoints`，不应直接使用 `FunCardRepository`、`FunCardHostClient` 或 `FunCardNotificationController`。

## 引入

仓库内模块：

```kotlin
dependencies {
    implementation(project(":core"))
}
```

Release AAR：

```text
core/build/outputs/aar/core-release.aar
```

当前核心与 Hook 共同打包在 `org.orynnx.outerview` 独立 LSPosed 应用中。二次 UI 可以依赖本模块并替换页面层，但应保持运行时 applicationId、签名权限和 LSPosed 作用域一致。

## 创建端点

```kotlin
val cards: RearCardManagementEndpoints = RearCardManager.create(context)
```

`RearCardManager` 会持有 Application Context，不会持有 Activity。公开 suspend 端点内部完成 IO 调度、Host API 连接和异常收敛，不会把 Binder 或文件异常直接抛给 UI。

## 查询状态

```kotlin
val snapshot = cards.refresh()

snapshot.capabilities.compatible
snapshot.cards
snapshot.error
```

`ManagedRearCard.state` 只有四种值：

- `NOT_INSTALLED`：ZIP 已导入，仅存在于 App 私有目录。
- `INSTALLED_DISABLED`：宿主模板已部署，runtime 未激活。
- `INSTALLED_ENABLED`：宿主 runtime、manager list 和 MAML 加载证据一致。
- `ERROR`：期望状态与宿主证据不一致。

## 导入

文件选择器属于 UI 层。拿到 `Uri` 后先预检，再由用户确认：

```kotlin
val preview = cards.inspectImport(uri, displayName)
if (preview.success) {
    val value = requireNotNull(preview.value)
    // 展示 value.findings，用户确认后：
    val result = cards.importAndInstall(value.token)
}
```

取消确认时必须释放暂存文件：

```kotlin
cards.discardImport(previewToken)
```

模板替换使用同一预检流程，随后调用 `replaceAndInstall(cardId, token)`。核心会先隐藏正在显示的卡片，再替换并重新安装。

## 生命周期端点

```kotlin
cards.setVisible(cardId, visible)
cards.retryInstall(cardId)
cards.deleteCard(cardId)
cards.deleteAllCards()
```

约束：

- 导入和替换成功后会自动安装，但默认不显示。
- 显示由 Host API v3 直接调用小米 Smart Assistant 原生运行管线，不发布 Android 通知。
- 宿主模板被系统清理时，显示操作会用本地 ZIP 自动重新部署。
- 隐藏只移除宿主 runtime，不删除模板。
- 删除会自动隐藏，随后清理宿主模板、宿主 registry、本地 ZIP 和本地 registry。
- 全部删除只清理 OuterView 托管卡片，不修改系统模板。
- Host 不可用时删除会保留 cleanup tombstone，连接恢复后自动清理宿主残留。

每个动作返回 `RearCardActionResult`，UI 只根据 `success`、`message`、`state` 和可选 `card` 更新界面。

## Payload

普通模式只传 `mamlConfigJson`：

```kotlin
cards.updatePayload(
    cardId = cardId,
    advanced = false,
    mamlConfigJson = """{"title":"Hello"}""",
)
```

高级模式可传完整 rear/focus JSON。最终 business 始终由核心覆盖，模板不能冒充其他系统业务。

## 诊断

```kotlin
val result = cards.diagnostics(cardId)
val evidence = result.value
```

诊断包含模板可读性、宿主直接激活、manager list、live widget、MAML load、宿主 registry、系统持久化、最后命令和遗留冲突证据。

## Compose 状态范式

```kotlin
val manager = remember { RearCardManager.create(context) }
var snapshot by remember { mutableStateOf(RearCardManagerSnapshot()) }

LaunchedEffect(Unit) {
    snapshot = manager.refresh()
}

fun setEnabled(cardId: String, enabled: Boolean) = scope.launch {
    val result = manager.setVisible(cardId, enabled)
    snackbar.showSnackbar(result.message)
    snapshot = manager.refresh()
}
```

文件选择器、确认对话框、Snackbar 和视觉状态全部由 UI 层负责；核心只提供事实和动作结果。
