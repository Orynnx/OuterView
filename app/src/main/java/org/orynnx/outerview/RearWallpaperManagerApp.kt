package org.orynnx.outerview

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostClient
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostContract
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RearWallpaperManagerApp(active: Boolean = true) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val client = remember { RearWallpaperHostClient() }
    val snackbar = remember { SnackbarHostState() }

    var entries by remember { mutableStateOf<List<RearWallpaperHostInfo>>(emptyList()) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var activeActionId by remember { mutableStateOf<Int?>(null) }
    var activeActionName by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<RearWallpaperHostInfo?>(null) }
    var renameTarget by remember { mutableStateOf<RearWallpaperHostInfo?>(null) }
    var renameName by remember { mutableStateOf("") }

    fun showMessage(message: String) {
        if (message.isBlank()) return
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun refresh(userInitiated: Boolean = false) {
        if (refreshing) return
        refreshing = true
        try {
            val refreshedEntries = withContext(Dispatchers.IO) {
                check(client.connect(appContext)) { "未连接到背屏服务，请确认模块已启用并重启背屏中心" }
                client.list()
                    .asSequence()
                    .filter { it.managed }
                    .sortedWith(
                        compareByDescending<RearWallpaperHostInfo> { it.current }
                            .thenBy { it.name.lowercase() },
                    )
                    .toList()
            }
            entries = refreshedEntries
            loadError = null
            if (userInitiated) showMessage("壁纸列表已刷新")
            initialLoading = false
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = error.message?.takeIf(String::isNotBlank) ?: "无法读取背屏壁纸列表"
            loadError = message
            if (userInitiated) showMessage(message)
            initialLoading = false
        } finally {
            refreshing = false
        }
    }

    fun runAction(
        actionName: String,
        targetId: Int? = null,
        action: () -> Bundle,
    ) {
        if (busy || refreshing) return
        busy = true
        activeActionId = targetId
        activeActionName = actionName
        scope.launch {
            try {
                val response = try {
                    withContext(Dispatchers.IO) {
                        check(client.connect(appContext)) { "未连接到背屏服务，请确认模块已启用并重启背屏中心" }
                        action()
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    val reason = error.message?.takeIf(String::isNotBlank) ?: "未知错误"
                    showMessage("$actionName 失败：$reason")
                    return@launch
                }
                val success = response.getBoolean(RearWallpaperHostContract.Keys.SUCCESS, false)
                val message = response
                    .getString(RearWallpaperHostContract.Keys.MESSAGE)
                    .orEmpty()
                    .ifBlank { if (success) "$actionName 完成" else "$actionName 失败" }
                showMessage(message)
                refresh()
            } finally {
                activeActionId = null
                activeActionName = null
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runAction(actionName = "导入壁纸") {
            val resolver = appContext.contentResolver
            val displayName = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }.orEmpty().ifBlank { "wallpaper.mrc" }
            val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) {
                "无法读取所选壁纸文件"
            }
            descriptor.use { client.import(it, displayName) }
        }
    }
    val openPicker = {
        if (!busy && !refreshing && !initialLoading) {
            picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }

    LaunchedEffect(active) {
        if (active) {
            // If the previous page activation is still leaving a blocking host
            // call, wait for its finally block and then perform this refresh.
            while (refreshing) delay(50L)
            refresh()
        }
    }

    val controlsEnabled = !busy && !refreshing && !initialLoading
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("背屏壁纸", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = { scope.launch { refresh(userInitiated = true) } },
                        enabled = controlsEnabled,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .semantics { contentDescription = "正在刷新壁纸" },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新壁纸列表")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = openPicker,
                modifier = Modifier.semantics {
                    if (!controlsEnabled) disabled()
                },
                icon = {
                    if (busy && activeActionId == null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = "正在导入壁纸" },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    }
                },
                text = { Text(if (busy && activeActionId == null) "正在导入…" else "导入壁纸") },
                containerColor = if (controlsEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (controlsEnabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    ) { padding ->
        if (initialLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "正在加载背屏壁纸"
                        },
                    )
                    Text(
                        text = "正在读取壁纸…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "只显示并管理由 OuterView 导入的壁纸；系统和其他应用的壁纸不会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                loadError?.let { error ->
                    item {
                        WallpaperLoadError(
                            message = error,
                            retryEnabled = !busy && !refreshing,
                            onRetry = { scope.launch { refresh(userInitiated = true) } },
                        )
                    }
                }

                if (entries.isEmpty() && loadError == null) {
                    item {
                        WallpaperEmptyState(
                            enabled = controlsEnabled,
                            onImport = openPicker,
                        )
                    }
                }

                items(entries, key = { it.wallpaperId }) { item ->
                    WallpaperCard(
                        item = item,
                        enabled = controlsEnabled,
                        pendingAction = activeActionName.takeIf {
                            busy && activeActionId == item.wallpaperId
                        },
                        onApply = {
                            runAction("应用壁纸", item.wallpaperId) {
                                client.apply(item.wallpaperId)
                            }
                        },
                        onRename = {
                            renameTarget = item
                            renameName = item.name
                        },
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        }
    }

    if (active) {
        renameTarget?.let { item ->
            val normalizedName = renameName.trim()
            val canSave = normalizedName.isNotEmpty() && normalizedName != item.name && !busy
            AlertDialog(
            onDismissRequest = { if (!busy) renameTarget = null },
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            title = { Text("重命名壁纸") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名称") },
                    supportingText = {
                        Text(if (renameName.isBlank()) "名称不能为空" else "${renameName.length}/48")
                    },
                    isError = renameName.isBlank(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        runAction("重命名壁纸", item.wallpaperId) {
                            client.rename(item.wallpaperId, normalizedName)
                        }
                    },
                    enabled = canSave,
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }, enabled = !busy) {
                    Text("取消")
                }
            },
            )
        }

        deleteTarget?.let { item ->
            AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            icon = {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("删除“${item.name}”？") },
            text = {
                Text(
                    if (item.current) {
                        "这是当前正在使用的壁纸。OuterView 会先让背屏切离它，再从运行时和管理目录中移除；此操作无法撤销。"
                    } else {
                        "将从背屏运行时和 OuterView 管理目录中移除这张壁纸；此操作无法撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        runAction("删除壁纸", item.wallpaperId) {
                            client.delete(item.wallpaperId)
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !busy) {
                    Text("取消")
                }
            },
            )
        }
    }
}

@Composable
private fun WallpaperCard(
    item: RearWallpaperHostInfo,
    enabled: Boolean,
    pendingAction: String?,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(item.wallpaperId) { mutableStateOf(false) }
    val cardColor = if (item.current) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val cardContentColor = if (item.current) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardColor,
            contentColor = cardContentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (item.current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (item.current) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Wallpaper, contentDescription = null)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "由 OuterView 管理",
                        style = MaterialTheme.typography.bodySmall,
                        color = cardContentColor.copy(alpha = 0.72f),
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = enabled,
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "管理 ${item.name}",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                            enabled = enabled,
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (item.current) "正在使用，无法删除" else "删除",
                                    color = if (item.current) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            enabled = enabled && !item.current,
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = cardContentColor.copy(alpha = 0.12f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pendingAction != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "$pendingAction：${item.name}"
                        },
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("$pendingAction…", style = MaterialTheme.typography.labelLarge)
                    }
                } else if (item.current) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("正在使用", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    Text(
                        text = "未应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = cardContentColor.copy(alpha = 0.72f),
                    )
                }

                Spacer(Modifier.weight(1f))

                if (!item.current) {
                    FilledTonalButton(onClick = onApply, enabled = enabled) {
                        Text("应用到背屏")
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperLoadError(
    message: String,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("无法载入壁纸", fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = onRetry,
                enabled = retryEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun WallpaperEmptyState(
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Wallpaper,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Text(
            text = "还没有 OuterView 壁纸",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "导入可信的 MRC 或 ZIP 壁纸包后，即可在这里应用和管理。",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onImport, enabled = enabled) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("导入第一张壁纸", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
