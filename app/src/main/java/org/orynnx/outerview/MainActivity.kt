package org.orynnx.outerview

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.funcardcore.CardImportPreview
import hk.uwu.reareye.funcardcore.ManagedCardDiagnostics
import hk.uwu.reareye.funcardcore.ManagedRearCard
import hk.uwu.reareye.funcardcore.RearCardActionResult
import hk.uwu.reareye.funcardcore.RearCardManager
import hk.uwu.reareye.funcardcore.RearCardManagerCapabilities
import hk.uwu.reareye.funcardcore.RearCardState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FunCardManagerTheme { FunCardManagerApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunCardManagerApp() {
    val context = LocalContext.current
    val manager = remember(context) { RearCardManager.create(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var cards by remember { mutableStateOf<List<ManagedRearCard>>(emptyList()) }
    var capabilities by remember { mutableStateOf(RearCardManagerCapabilities()) }
    var workingKey by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<CardImportPreview?>(null) }
    var replacementTarget by remember { mutableStateOf<ManagedRearCard?>(null) }
    var payloadTarget by remember { mutableStateOf<ManagedRearCard?>(null) }
    var diagnostics by remember { mutableStateOf<ManagedCardDiagnostics?>(null) }
    var deleteTarget by remember { mutableStateOf<ManagedRearCard?>(null) }
    var deleteAllRequested by remember { mutableStateOf(false) }
    var workingMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val snapshot = manager.refresh()
        cards = snapshot.cards
        capabilities = snapshot.capabilities
        snapshot.error?.let { message ->
            scope.launch { snackbar.showSnackbar(message) }
        }
    }

    fun runAction(
        key: String,
        message: String = "正在处理卡片，请稍候",
        action: suspend () -> RearCardActionResult,
    ) {
        if (workingKey != null) return
        workingKey = key
        workingMessage = message
        scope.launch {
            try {
                val result = action()
                refresh()
                snackbar.showSnackbar(result.message)
            } finally {
                workingKey = null
                workingMessage = null
            }
        }
    }

    fun commitImport(preview: CardImportPreview, target: ManagedRearCard?) {
        pendingImport = null
        replacementTarget = null
        runAction(target?.cardId ?: "import") {
            if (target == null) manager.importAndInstall(preview.token)
            else manager.replaceAndInstall(target.cardId, preview.token)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            replacementTarget = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val target = replacementTarget
            workingKey = target?.cardId ?: "import"
            val name = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val result = manager.inspectImport(uri, name)
            val preview = result.value
            when {
                !result.success || preview == null -> {
                    replacementTarget = null
                    snackbar.showSnackbar(result.message.ifBlank { "导入检查失败" })
                }
                preview.findings.isEmpty() -> {
                    workingKey = null
                    commitImport(preview, target)
                    return@launch
                }
                else -> pendingImport = preview
            }
            workingKey = null
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("背屏卡片", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (capabilities.compatible) "已连接" else "Hook 未就绪",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (capabilities.compatible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { deleteAllRequested = true },
                        enabled = workingKey == null,
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "删除全部卡片")
                    }
                    IconButton(
                        onClick = { scope.launch { refresh() } },
                        enabled = workingKey == null,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (workingKey != null) return@FloatingActionButton
                replacementTarget = null
                fileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }) {
                Icon(Icons.Rounded.Add, contentDescription = "导入卡片")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            workingMessage?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!capabilities.compatible) {
                item { HookWarningBanner() }
            }
            if (cards.isEmpty()) {
                item {
                    EmptyCards(onImport = {
                        replacementTarget = null
                        fileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    })
                }
            }
            items(cards, key = { it.cardId }) { card ->
                CompactCardRow(
                    card = card,
                    working = workingKey == card.cardId,
                    enabled = workingKey == null,
                    onVisibleChange = { visible ->
                        runAction(card.cardId) { manager.setVisible(card.cardId, visible) }
                    },
                    onRetry = { runAction(card.cardId) { manager.retryInstall(card.cardId) } },
                    onReplace = {
                        replacementTarget = card
                        fileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onPayload = { payloadTarget = card },
                    onDiagnostics = {
                        scope.launch {
                            workingKey = card.cardId
                            val result = manager.diagnostics(card.cardId)
                            diagnostics = result.value
                            if (!result.success) snackbar.showSnackbar(result.message)
                            workingKey = null
                        }
                    },
                    onDelete = { if (workingKey == null) deleteTarget = card },
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    pendingImport?.let { preview ->
        RiskConfirmDialog(
            preview = preview,
            replacing = replacementTarget != null,
            onDismiss = {
                manager.discardImport(preview.token)
                pendingImport = null
                replacementTarget = null
            },
            onConfirm = { commitImport(preview, replacementTarget) },
        )
    }

    deleteTarget?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 ${card.displayName}？") },
            text = { Text("卡片会先从背屏隐藏，再删除宿主模板和本地文件。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    runAction(card.cardId, "正在删除 ${card.displayName}，请等待背屏清理完成") {
                        manager.deleteCard(card.cardId)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (deleteAllRequested) {
        AlertDialog(
            onDismissRequest = { deleteAllRequested = false },
            title = { Text("删除全部 OuterView 卡片？") },
            text = { Text("将从背屏移除所有 OuterView 卡片，并删除宿主模板、本地 ZIP 和记录。系统卡片不会受到影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteAllRequested = false
                    runAction("delete_all", "正在删除全部卡片，请等待宿主和背屏清理完成") {
                        manager.deleteAllCards()
                    }
                }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllRequested = false }) { Text("取消") }
            },
        )
    }

    payloadTarget?.let { card ->
        PayloadEditorDialog(
            card = card,
            onDismiss = { payloadTarget = null },
            onSave = { advanced, config, rear, focus ->
                payloadTarget = null
                runAction(card.cardId) {
                    manager.updatePayload(card.cardId, advanced, config, rear, focus)
                }
            },
        )
    }

    diagnostics?.let { value ->
        DiagnosticsDialog(value, onDismiss = { diagnostics = null })
    }
}

@Composable
private fun HookWarningBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Host Hook 未就绪，安装和显示暂不可用", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyCards(onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Add, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text("还没有卡片", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onImport) { Text("导入卡片") }
    }
}

@Composable
private fun CompactCardRow(
    card: ManagedRearCard,
    working: Boolean,
    enabled: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onReplace: () -> Unit,
    onPayload: () -> Unit,
    onDiagnostics: () -> Unit,
    onDelete: () -> Unit,
) {
    val visible = card.desiredEnabled && card.state == RearCardState.INSTALLED_ENABLED
    val installed = card.state == RearCardState.INSTALLED_DISABLED || visible
    var menuExpanded by remember(card.cardId) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        card.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        userStatus(card),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(card),
                    )
                }
                if (working) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("替换模板") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                enabled = enabled,
                                onClick = { menuExpanded = false; onReplace() },
                            )
                            DropdownMenuItem(
                                text = { Text("Payload") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                enabled = enabled && !visible,
                                onClick = { menuExpanded = false; onPayload() },
                            )
                            DropdownMenuItem(
                                text = { Text("诊断") },
                                leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                enabled = enabled,
                                onClick = { menuExpanded = false; onDiagnostics() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                                },
                                enabled = enabled,
                                onClick = { menuExpanded = false; onDelete() },
                            )
                        }
                    }
                }
            }

            if (installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示到背屏", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = visible,
                        onCheckedChange = onVisibleChange,
                        enabled = enabled,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.lastMessage.orEmpty().ifBlank { "卡片尚未安装" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onRetry, enabled = enabled) {
                        Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}

private fun userStatus(card: ManagedRearCard): String = when {
    card.desiredEnabled && card.state == RearCardState.INSTALLED_ENABLED -> "正在显示"
    card.state == RearCardState.INSTALLED_DISABLED -> "已隐藏"
    card.state == RearCardState.ERROR -> "需要处理"
    else -> "等待安装"
}

@Composable
private fun statusColor(card: ManagedRearCard): Color = when {
    card.desiredEnabled && card.state == RearCardState.INSTALLED_ENABLED -> MaterialTheme.colorScheme.primary
    card.state == RearCardState.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun RiskConfirmDialog(
    preview: CardImportPreview,
    replacing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replacing) "确认替换" else "确认导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.suggestedName} 包含可调用外部功能的命令：")
                preview.findings.take(8).forEach { finding ->
                    Text(
                        "${finding.type}: ${finding.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("继续") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PayloadEditorDialog(
    card: ManagedRearCard,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, String, String) -> Unit,
) {
    var advanced by remember(card.cardId) { mutableStateOf(card.advancedPayload) }
    var config by remember(card.cardId) { mutableStateOf(card.mamlConfigJson) }
    var rear by remember(card.cardId) { mutableStateOf(card.advancedRearParamJson.orEmpty()) }
    var focus by remember(card.cardId) { mutableStateOf(card.advancedFocusParamJson.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payload") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = advanced, onCheckedChange = { advanced = it })
                    Text("高级模式")
                }
                if (advanced) {
                    OutlinedTextField(rear, { rear = it }, label = { Text("miui.rear.param") }, minLines = 4)
                    OutlinedTextField(focus, { focus = it }, label = { Text("miui.focus.param") }, minLines = 4)
                } else {
                    OutlinedTextField(config, { config = it }, label = { Text("maml_config") }, minLines = 8)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(advanced, config, rear, focus) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DiagnosticsDialog(value: ManagedCardDiagnostics, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卡片诊断") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                item { DiagnosticLine("cardId", value.cardId) }
                item { DiagnosticLine("business", value.business) }
                item { DiagnosticLine("hookReady", value.hookReady.toString()) }
                item { DiagnosticLine("managerCaptured", value.managerCaptured.toString()) }
                item { DiagnosticLine("templateReadable", value.templateReadable.toString()) }
                item { DiagnosticLine("hostRegistryContains", value.hostRegistryContains.toString()) }
                item { DiagnosticLine("legacyNotificationSeen", value.notificationSeen.toString()) }
                item { DiagnosticLine("runtimeActivated", value.runtimeActivated.toString()) }
                item { DiagnosticLine("managerListContains", value.managerListContains.toString()) }
                item { DiagnosticLine("liveWidgetContains", value.liveWidgetContains.toString()) }
                item { DiagnosticLine("loadAttempted", value.loadAttempted.toString()) }
                item { DiagnosticLine("loadSucceeded", value.loadSucceeded.toString()) }
                item { DiagnosticLine("actualTemplatePath", value.actualTemplatePath.orEmpty()) }
                item { DiagnosticLine("lastCommandId", value.lastCommandId.orEmpty()) }
                item { DiagnosticLine("lastError", value.lastError.orEmpty()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FunCardManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF176B5C),
            secondary = Color(0xFF52645F),
            tertiary = Color(0xFF765A1B),
            surface = Color(0xFFFFF9FD),
            surfaceContainer = Color(0xFFF3EDF1),
        ),
        content = content,
    )
}
