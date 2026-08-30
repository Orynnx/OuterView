package org.orynnx.outerview

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.orynnx.outerview.core.internal.BoundedDeadlineCopy
import org.orynnx.outerview.core.internal.RearWallpaperPackageValidator
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostClient
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostContract
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val WallpaperImportTimeoutSeconds = 15L
private const val WallpaperImportStaleAgeHours = 24L
private const val WallpaperImportCacheDirectory = "wallpaper-import"
private const val WallpaperImportTempPrefix = "pending_wallpaper_"
private const val WallpaperImportLogTag = "OuterView-Wallpaper"

@Composable
fun RearWallpaperManagerApp(active: Boolean = true, resumeTick: Int = 1) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val refreshMutex = remember { Mutex() }
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
        scope.launch {
            snackbar.newestSnackbarData()?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    suspend fun refresh(userInitiated: Boolean = false) {
        refreshMutex.withLock {
            refreshing = true
            try {
                val refreshedEntries = withContext(Dispatchers.IO) {
                    check(client.connect(appContext)) { "未连接到背屏服务，请确认模块已启用并重启背屏中心" }
                    client.list()
                        .asSequence()
                        .filter { it.managed }
                        .sortedWith(
                            compareByDescending<RearWallpaperHostInfo> { it.current }
                                .thenBy { it.name.lowercase(Locale.ROOT) },
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
            // A display name is not worth another unbounded provider IPC. The
            // decoded URI segment is only advisory and is sanitized locally.
            val displayName = wallpaperImportDisplayName(uri)
            val stagedFile = stageWallpaperImport(appContext, uri)
            try {
                val descriptor = ParcelFileDescriptor.open(stagedFile, ParcelFileDescriptor.MODE_READ_ONLY)
                var importFailure: Throwable? = null
                try {
                    client.import(descriptor, displayName)
                } catch (error: Throwable) {
                    importFailure = error
                    throw error
                } finally {
                    runCatching { descriptor.close() }.onFailure { closeFailure ->
                        val primary = importFailure
                        if (primary == null) {
                            Log.w(WallpaperImportLogTag, "Unable to close staged wallpaper descriptor", closeFailure)
                        } else {
                            primary.addSuppressed(closeFailure)
                        }
                    }
                }
            } finally {
                // A cache cleanup failure must not turn a committed Host import
                // into a false failure. The next import retries stale cleanup.
                deleteWallpaperImportTemp(stagedFile)
            }
        }
    }
    val openPicker = {
        if (!busy && !refreshing && !initialLoading) {
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
    }

    LaunchedEffect(active, resumeTick) {
        if (active && resumeTick > 0) {
            refresh()
        }
    }

    val controlsEnabled = !busy && !refreshing && !initialLoading
    Scaffold(
        topBar = {
            TopAppBar(
                title = "背屏壁纸",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { scope.launch { refresh(userInitiated = true) } },
                        enabled = controlsEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .semantics { contentDescription = "正在刷新壁纸" },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(MiuixIcons.Refresh, contentDescription = "刷新壁纸列表")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = openPicker,
                enabled = controlsEnabled,
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
                        Icon(MiuixIcons.Add, contentDescription = null)
                    }
                },
                text = { Text(if (busy && activeActionId == null) "正在导入…" else "导入壁纸") },
                containerColor = MiuixTheme.colorScheme.primaryContainer,
                contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { padding ->
        if (initialLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "只显示并管理由 OuterView 导入的壁纸；系统和其他应用的壁纸不会出现在这里。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
            val nameCodePoints = normalizedName.codePointCount(0, normalizedName.length)
            val nameHasInvalidCharacters = normalizedName.any(::isUnsafeWallpaperNameCharacter)
            val canSave = nameCodePoints in 1..48 && !nameHasInvalidCharacters &&
                normalizedName != item.name && !busy
            AlertDialog(
            onDismissRequest = { if (!busy) renameTarget = null },
            icon = { Icon(MiuixIcons.Edit, contentDescription = null) },
            title = { Text("重命名壁纸") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it.takeWallpaperNameCodePoints(48) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = "名称",
                    supportingText = {
                        Text(
                            when {
                                renameName.isBlank() -> "名称不能为空"
                                nameHasInvalidCharacters -> "名称包含不可见控制字符"
                                else -> "$nameCodePoints/48"
                            },
                        )
                    },
                    isError = nameCodePoints !in 1..48 || nameHasInvalidCharacters,
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
                    MiuixIcons.Report,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.error,
                )
            },
            title = { Text("删除“${item.name}”？") },
            text = {
                Text(
                    if (item.current) {
                        "这是当前正在使用的壁纸。请先应用另一张壁纸，再删除它。"
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MiuixTheme.colorScheme.error),
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
    val cardColor = if (item.current) {
        MiuixTheme.colorScheme.primaryContainer
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val cardContentColor = if (item.current) {
        MiuixTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onSurfaceContainer
    }
    val cardSummaryColor = if (item.current) {
        cardContentColor.copy(alpha = 0.72f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
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
                    shape = RoundedCornerShape(16.dp),
                    color = if (item.current) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (item.current) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSecondaryContainer
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(MiuixIcons.Background, contentDescription = null)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "由 OuterView 管理",
                        style = MiuixTheme.textStyles.footnote2,
                        color = cardSummaryColor,
                    )
                }

                OverlayIconDropdownMenu(
                    entries = listOf(
                        DropdownEntry(
                            items = listOf(
                                DropdownItem(
                                    text = "重命名",
                                    enabled = enabled,
                                    onClick = onRename,
                                    icon = { modifier -> Icon(MiuixIcons.Edit, null, modifier) },
                                ),
                            ),
                        ),
                        DropdownEntry(
                            items = listOf(
                                DropdownItem(
                                    text = if (item.current) "正在使用，无法删除" else "删除",
                                    enabled = enabled && !item.current,
                                    onClick = onDelete,
                                    icon = { modifier -> Icon(MiuixIcons.Delete, null, modifier) },
                                ),
                            ),
                        ),
                    ),
                    enabled = enabled,
                    collapseOnSelection = true,
                    minWidth = 48.dp,
                    minHeight = 48.dp,
                ) {
                    Icon(
                        MiuixIcons.More,
                        contentDescription = "管理 ${item.name}",
                    )
                }
            }

            HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)

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
                        Text("$pendingAction…", style = MiuixTheme.textStyles.footnote1)
                    }
                } else if (item.current) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                MiuixIcons.Ok,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("正在使用", style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                } else {
                    Text(
                        text = "未应用",
                        style = MiuixTheme.textStyles.footnote2,
                        color = cardSummaryColor,
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
            containerColor = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(MiuixIcons.Report, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("无法载入壁纸", fontWeight = FontWeight.SemiBold)
                Text(message, style = MiuixTheme.textStyles.footnote2)
            }
            TextButton(
                onClick = onRetry,
                enabled = retryEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MiuixTheme.colorScheme.onErrorContainer,
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
            shape = RoundedCornerShape(28.dp),
            color = MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    MiuixIcons.Background,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Text(
            text = "还没有 OuterView 壁纸",
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "导入可信的 MRC 或 ZIP 壁纸包后，即可在这里应用和管理。",
            modifier = Modifier.fillMaxWidth(),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onImport, enabled = enabled) {
            Icon(MiuixIcons.Add, contentDescription = null)
            Text("导入第一张壁纸", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun String.takeWallpaperNameCodePoints(maxCodePoints: Int): String {
    val count = codePointCount(0, length).coerceAtMost(maxCodePoints)
    return substring(0, offsetByCodePoints(0, count))
}

private fun isUnsafeWallpaperNameCharacter(character: Char): Boolean =
    character.isISOControl() || character in '\u202a'..'\u202e' ||
        character in '\u2066'..'\u2069' || character == '\u200e' ||
        character == '\u200f' || character == '\u061c'

private fun stageWallpaperImport(context: Context, uri: Uri): File {
    val importCache = wallpaperImportCache(context)
    val stagedFile = File.createTempFile(WallpaperImportTempPrefix, ".mrc", importCache)
    try {
        require(
            stagedFile.absoluteFile.parentFile == importCache.absoluteFile &&
                Files.isRegularFile(stagedFile.toPath(), LinkOption.NOFOLLOW_LINKS),
        ) { "壁纸缓存临时文件无效" }
        val timeoutNanos = TimeUnit.SECONDS.toNanos(WallpaperImportTimeoutSeconds)
        val cancellationSignal = CancellationSignal()
        val abortRequested = AtomicBoolean(false)
        val descriptorRef = AtomicReference<ParcelFileDescriptor?>()
        FileOutputStream(stagedFile, false).use { output ->
            val copied = try {
                BoundedDeadlineCopy.runWithSupervisor(
                    timeoutNanos = timeoutNanos,
                    // This callback runs on the supervising Dispatchers.IO thread,
                    // not the worker blocked in provider open/read. The flag closes
                    // the race where timeout happens just before the PFD is published.
                    closeSource = {
                        abortRequested.set(true)
                        val closeFailure = runCatching { descriptorRef.getAndSet(null)?.close() }
                            .exceptionOrNull()
                        runCatching { cancellationSignal.cancel() }
                            .onFailure { cancelFailure ->
                                if (closeFailure == null) throw cancelFailure
                                closeFailure.addSuppressed(cancelFailure)
                            }
                        if (closeFailure != null) throw closeFailure
                    },
                ) {
                    val descriptor = requireNotNull(
                        context.contentResolver.openFileDescriptor(uri, "r", cancellationSignal),
                    ) { "无法读取所选壁纸文件" }
                    descriptorRef.set(descriptor)
                    if (abortRequested.get()) {
                        descriptorRef.compareAndSet(descriptor, null)
                        runCatching { descriptor.close() }
                        throw InterruptedIOException("wallpaper import was cancelled")
                    }
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            BoundedDeadlineCopy.copy(
                                input = input,
                                output = output,
                                maxBytes = RearWallpaperPackageValidator.MaxCompressedBytes,
                                timeoutNanos = timeoutNanos,
                            )
                        }
                    } finally {
                        descriptorRef.compareAndSet(descriptor, null)
                        runCatching { descriptor.close() }
                    }
                }
            } catch (error: BoundedDeadlineCopy.DeadlineExceededException) {
                throw IOException("读取壁纸超时（${WallpaperImportTimeoutSeconds} 秒）", error)
            } catch (error: BoundedDeadlineCopy.LimitExceededException) {
                throw IOException("壁纸文件超过 32 MB", error)
            }
            require(copied > 0L) { "壁纸文件不能为空" }
            output.fd.sync()
        }

        val stagedPath = stagedFile.toPath()
        require(Files.isRegularFile(stagedPath, LinkOption.NOFOLLOW_LINKS)) {
            "壁纸缓存不是常规文件"
        }
        require(stagedFile.length() in 1L..RearWallpaperPackageValidator.MaxCompressedBytes) {
            "壁纸缓存大小无效"
        }
        // Reject malformed/expansion-heavy packages in the app process before
        // spending the privileged Host's Binder thread CPU. Host validates again.
        RearWallpaperPackageValidator.inspect(stagedFile)
        return stagedFile
    } catch (error: Throwable) {
        runCatching { deleteWallpaperImportTempStrict(stagedFile) }.onFailure(error::addSuppressed)
        throw error
    }
}

private fun wallpaperImportCache(context: Context): File {
    val cacheRoot = context.cacheDir.absoluteFile
    require(cacheRoot.isDirectory && !Files.isSymbolicLink(cacheRoot.toPath())) {
        "应用缓存目录无效"
    }
    val importCache = File(cacheRoot, WallpaperImportCacheDirectory).absoluteFile
    require(importCache.parentFile == cacheRoot) { "壁纸缓存路径无效" }
    if (!importCache.exists()) check(importCache.mkdir()) { "无法创建壁纸缓存目录" }
    require(importCache.isDirectory && !Files.isSymbolicLink(importCache.toPath())) {
        "壁纸缓存目录无效"
    }
    require(importCache.canonicalFile.parentFile == cacheRoot.canonicalFile) {
        "壁纸缓存目录越界"
    }

    val staleBefore = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(WallpaperImportStaleAgeHours)
    importCache.listFiles().orEmpty()
        .filter {
            it.name.startsWith(WallpaperImportTempPrefix) &&
                (Files.isSymbolicLink(it.toPath()) ||
                    Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)) &&
                runCatching {
                    Files.getLastModifiedTime(it.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis() <= staleBefore
                }.getOrDefault(false)
        }
        .forEach(::deleteWallpaperImportTemp)
    return importCache
}

private fun deleteWallpaperImportTemp(file: File) {
    runCatching { deleteWallpaperImportTempStrict(file) }
        .onFailure { Log.w(WallpaperImportLogTag, "Unable to delete staged wallpaper ${file.name}", it) }
}

private fun deleteWallpaperImportTempStrict(file: File) {
    val parent = requireNotNull(file.absoluteFile.parentFile) { "壁纸缓存父目录缺失" }
    require(parent.name == WallpaperImportCacheDirectory) { "拒绝清理非壁纸缓存文件" }
    require(file.name.startsWith(WallpaperImportTempPrefix)) { "拒绝清理非壁纸临时文件" }
    require(!Files.isSymbolicLink(parent.toPath()) && parent.isDirectory) { "壁纸缓存目录无效" }
    require(file.absoluteFile.parentFile == parent.absoluteFile) { "壁纸临时文件越界" }
    if (Files.isSymbolicLink(file.toPath()) || file.isFile) {
        Files.deleteIfExists(file.toPath())
    } else {
        require(!file.exists()) { "拒绝递归清理壁纸缓存目录" }
    }
}

private fun wallpaperImportDisplayName(uri: Uri): String {
    val candidate = uri.lastPathSegment
        .orEmpty()
        .take(512)
        .substringAfterLast('/')
        .substringAfterLast(':')
        .filterNot(::isUnsafeWallpaperNameCharacter)
        .trim()
        .takeWallpaperNameCodePoints(256)
    return candidate.takeIf {
        it.endsWith(".mrc", ignoreCase = true) || it.endsWith(".zip", ignoreCase = true)
    } ?: "wallpaper.mrc"
}
