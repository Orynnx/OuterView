package org.orynnx.outerview

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.orynnx.outerview.core.ai.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AiAppManagerApp(resumeTick: Int, onAbout: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { AiAppManager.create(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val serial = remember { Mutex() }
    val scrollBehavior = MiuixScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    var snapshot by remember { mutableStateOf<AiAppSnapshot?>(null) }
    var refreshing by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<AiImportPreview?>(null) }
    var displayName by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<AiCard?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    var restoreTarget by remember { mutableStateOf<AiCard?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    fun message(text: String) {
        scope.launch {
            snackbar.newestSnackbarData()?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    suspend fun refresh(notify: Boolean = false) {
        serial.withLock {
            refreshing = true
            try {
                snapshot = withContext(Dispatchers.IO) { manager.snapshot() }
                loadError = null
                // A confirmation must not silently carry stale state across a foreground refresh.
                removeTarget?.let { selected ->
                    if (removeError == null && (snapshot?.connected != true || snapshot?.cards?.find { it.id == selected.id } != selected)) {
                        removeError = "应用状态已变化或暂时无法确认。请刷新列表后重新选择应用。"
                    }
                }
                restoreTarget?.let { selected ->
                    if (restoreError == null && (snapshot?.connected != true || snapshot?.cards?.find { it.id == selected.id } != selected)) {
                        restoreError = "应用状态已变化或暂时无法确认。请刷新列表后重新选择应用。"
                    }
                }
                if (notify) message(actionMessage(snapshot?.message, if (snapshot?.connected == true) "应用列表已刷新" else "暂时无法读取应用列表"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                loadError = actionMessage(error.message, "暂时无法读取应用列表，请稍后刷新")
                if (removeTarget != null && removeError == null) removeError = "暂时无法确认应用状态。请刷新列表后重新选择应用。"
                if (restoreTarget != null && restoreError == null) restoreError = "暂时无法确认应用状态。请刷新列表后重新选择应用。"
                if (notify) message(loadError!!)
            } finally {
                refreshing = false
            }
        }
    }

    val connected = snapshot?.connected == true && loadError == null
    val available = connected && busy == null && !refreshing

    fun openSystem() {
        if (busy != null) return
        busy = "正在打开系统智能应用…"
        scope.launch {
            try {
                if (!manager.openSystemManager(context)) message("未能打开系统智能应用，请确认主题壁纸已连接")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message("未能打开系统智能应用，请重新连接主题壁纸")
            } finally { busy = null }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && busy == null) {
            busy = "正在检查应用包…"
            scope.launch {
                try {
                    val inspected = serial.withLock { withContext(Dispatchers.IO) { manager.inspect(uri) } }
                    preview = inspected
                    displayName = inspected.name
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message(actionMessage(error.message, "无法识别应用包，请重新选择文件"))
                } finally { busy = null }
            }
        }
    }

    LaunchedEffect(resumeTick) { refresh() }
    BackHandler(enabled = busy != null) { message("正在处理，请稍候") }
    // The staged source belongs to this confirmation session, including cancel/back/navigation.
    val token = preview?.token
    DisposableEffect(manager, token) {
        onDispose { if (token != null) manager.discardPreview(token) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "智能应用",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = busy == null) {
                        Icon(MiuixIcons.Back, contentDescription = "返回系统背屏设置")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh(true) } }, enabled = busy == null && !refreshing) {
                        Icon(MiuixIcons.Refresh, contentDescription = "刷新应用列表")
                    }
                    IconButton(onClick = onAbout, enabled = busy == null) {
                        Icon(MiuixIcons.Info, contentDescription = "关于 OuterView")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (refreshing || busy != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = busy ?: when {
                                        refreshing && snapshot == null -> "正在连接主题服务…"
                                        connected -> "主题服务已连接"
                                        else -> "暂时无法读取应用"
                                    },
                                    style = MiuixTheme.textStyles.title2,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = actionMessage(loadError ?: snapshot?.message,
                                    if (refreshing) "正在读取智能应用列表…" else "请稍后刷新，或打开系统智能应用确认状态。"),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (!connected && !refreshing) {
                                Text(
                                    "若尚未配置连接，请确认已安装主题壁纸，并在 LSPosed 中为 OuterView 勾选主题壁纸（com.android.thememanager）。配置后重新打开主题壁纸并返回刷新。",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                enabled = available,
                                onClick = { picker.launch(arrayOf("*/*")) },
                            ) {
                                Icon(MiuixIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("导入智能应用")
                            }
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                        BasicComponent(
                            title = "系统智能应用",
                            summary = "浏览和管理系统中的背屏应用",
                            enabled = busy == null,
                            onClick = ::openSystem,
                            endActions = { Text("›", style = MiuixTheme.textStyles.title2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        )
                    }
                }

                val imported = snapshot?.cards.orEmpty().filter { it.managed }
                val system = snapshot?.cards.orEmpty().filterNot { it.managed }
                item { SectionLabel(if (imported.isEmpty()) "我的应用" else "我的应用 · ${imported.size}") }
                if (snapshot == null && refreshing) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                            BasicComponent(title = "正在读取应用", summary = "请稍候")
                        }
                    }
                } else if (!connected) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                            BasicComponent(title = "连接后查看应用", summary = "需要主题壁纸提供智能应用服务。连接后可导入和移除应用。")
                        }
                    }
                } else if (imported.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("从一个小功能开始", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold)
                                Text("选择智能应用资源包，确认名称后即可导入。", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                } else {
                    items(imported, key = { it.id }) { card ->
                        AiCardRow(
                            card, enabled = available,
                            onRestore = { restoreError = null; restoreTarget = card },
                            onRemove = { removeError = null; removeTarget = card },
                        )
                    }
                }
                if (connected && system.isNotEmpty()) {
                    item { SectionLabel("来自系统 · ${system.size}") }
                    items(system, key = { "system:${it.id}" }) { card ->
                        AiCardRow(
                            card, enabled = available,
                            onRestore = { restoreError = null; restoreTarget = card },
                            onRemove = { removeError = null; removeTarget = card },
                        )
                    }
                    item {
                        Text("更多应用可前往「系统智能应用」查看。", modifier = Modifier.padding(horizontal = 12.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }

            preview?.let { pending ->
                OverlayDialog(
                    show = true,
                    title = "导入智能应用",
                    onDismissRequest = { if (busy == null) preview = null },
                ) {
                    Column(
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TextField(
                            value = displayName,
                            onValueChange = { displayName = it.take(80) },
                            label = "应用名称",
                            singleLine = true,
                            enabled = busy == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("应用包中的脚本会在系统背屏中运行，请确认文件来自你信任的来源。", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        pending.warnings.distinct().forEach { warning ->
                            Text(warning, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(text = "取消", modifier = Modifier.weight(1f), enabled = busy == null, onClick = { preview = null })
                            TextButton(
                                text = if (busy != null) "正在导入…" else "确认导入",
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                enabled = busy == null && displayName.isNotBlank(),
                                onClick = {
                                    busy = "正在导入应用…"
                                    scope.launch {
                                        try {
                                            val result = serial.withLock { withContext(Dispatchers.IO) { manager.importCard(pending, displayName.trim()) } }
                                            if (result.pending) {
                                                preview = null
                                                refresh()
                                                message(unconfirmedActionMessage("导入", result.message))
                                            } else if (result.success) {
                                                preview = null
                                                message(actionMessage(result.message, "应用已导入"))
                                                refresh()
                                            } else {
                                                preview = null
                                                refresh()
                                                message(importFailureMessage(result.message))
                                            }
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            preview = null
                                            refresh()
                                            message(unconfirmedActionMessage("导入", error.message))
                                        } finally { busy = null }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            restoreTarget?.let { card ->
                OverlayDialog(
                    show = true,
                    title = "恢复“${card.name}”？",
                    summary = "使用原来的应用 ID、记录和资源恢复背屏登记。",
                    onDismissRequest = { if (busy == null) restoreTarget = null },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        restoreError?.let {
                            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                text = "取消", modifier = Modifier.weight(1f), enabled = busy == null,
                                onClick = { restoreTarget = null },
                            )
                            TextButton(
                                text = when {
                                    busy != null -> "正在恢复…"
                                    restoreError != null -> "刷新列表"
                                    else -> "恢复显示"
                                },
                                modifier = Modifier.weight(1f),
                                enabled = busy == null && !refreshing,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    if (restoreError != null) {
                                        restoreTarget = null
                                        scope.launch { refresh(true) }
                                        return@TextButton
                                    }
                                    busy = "正在恢复背屏显示…"
                                    restoreError = null
                                    scope.launch {
                                        try {
                                            val result = serial.withLock {
                                                withContext(Dispatchers.IO) { manager.restore(card.id) }
                                            }
                                            if (result.pending) {
                                                restoreTarget = null
                                                refresh()
                                                message(unconfirmedActionMessage("恢复", result.message))
                                            } else if (result.success) {
                                                restoreTarget = null
                                                refresh()
                                                message(actionMessage(result.message, "已恢复背屏登记"))
                                            } else restoreError = actionMessage(result.message, "恢复未完成") + "。请刷新列表确认状态后，再选择应用。"
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            restoreError = unconfirmedActionMessage("恢复", error.message)
                                        } finally { busy = null }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            removeTarget?.let { card ->
                OverlayDialog(
                    show = true,
                    title = "移除“${card.name}”？",
                    summary = removalSummary(card),
                    onDismissRequest = { if (busy == null) removeTarget = null },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        removeError?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(text = "取消", modifier = Modifier.weight(1f), enabled = busy == null, onClick = { removeTarget = null })
                            TextButton(
                                text = when {
                                    busy != null -> "正在移除…"
                                    removeError != null -> "刷新列表"
                                    else -> "移除应用"
                                },
                                modifier = Modifier.weight(1f),
                                enabled = busy == null && !refreshing,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    if (removeError != null) {
                                        removeTarget = null
                                        scope.launch { refresh(true) }
                                        return@TextButton
                                    }
                                    busy = "正在移除应用…"
                                    scope.launch {
                                        try {
                                            val result = serial.withLock { withContext(Dispatchers.IO) { manager.remove(card.id) } }
                                            if (result.pending) {
                                                removeTarget = null
                                                refresh()
                                                message(unconfirmedActionMessage("移除", result.message))
                                            } else if (result.success) {
                                                removeTarget = null
                                                message(actionMessage(result.message, "应用已移除"))
                                                refresh()
                                            } else removeError = actionMessage(result.message, "移除未完成") + "。请刷新列表确认状态后，再选择应用。"
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            removeError = unconfirmedActionMessage("移除", error.message)
                                        } finally { busy = null }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun actionMessage(message: String?, fallback: String): String =
    message?.trim()?.takeIf { it.isNotBlank() && it.length <= 240 &&
        !it.contains(Regex("/(data|system|storage|sdcard|mnt)/|Exception|\\bat [a-z]+\\."))
    } ?: fallback

private fun importFailureMessage(message: String?): String {
    val reason = actionMessage(message, "导入失败")
    return "$reason。请先刷新列表或在系统智能应用中确认结果，暂勿重复导入。"
}

private fun unconfirmedActionMessage(action: String, message: String?): String =
    "${action}结果尚未确认。" + actionMessage(message, "系统操作可能仍在处理中") +
        "。请稍后刷新列表或在系统智能应用中确认，暂勿重复操作。"

private fun removalSummary(card: AiCard): String {
    val action = if (card.registered) "此应用会同步从系统背屏中移除。"
        else "此应用目前未在背屏登记，移除后也会从系统智能应用管理中移除。"
    val restore = if (card.managed) "若要再次使用，需要重新导入资源包。"
        else "若要再次使用，可前往系统智能应用重新添加。"
    return action + restore
}

@Composable
private fun AiCardRow(card: AiCard, enabled: Boolean, onRestore: () -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        BasicComponent(
            title = card.name,
            summary = if (card.registered) "已在背屏登记" else "未在背屏登记",
            startAction = { Box(Modifier.padding(end = 14.dp)) { AppInitial(card.name) } },
            endActions = {
                if (!card.registered) {
                    TextButton(
                        text = "恢复显示",
                        onClick = onRestore,
                        enabled = enabled,
                        minWidth = 0.dp,
                        minHeight = 48.dp,
                        textStyle = MiuixTheme.textStyles.body2,
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
                IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(MiuixIcons.Delete, contentDescription = "移除${card.name}", modifier = Modifier.size(22.dp))
                }
            },
            insideMargin = PaddingValues(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
        )
    }
}
