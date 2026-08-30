package org.orynnx.outerview

import android.app.Activity
import android.app.DownloadManager
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Redo
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.orynnx.outerview.core.CardImportPreview
import org.orynnx.outerview.core.ManagedCardDiagnostics
import org.orynnx.outerview.core.ManagedRearCard
import org.orynnx.outerview.core.RearCardActionResult
import org.orynnx.outerview.core.RearCardManager
import org.orynnx.outerview.core.RearCardManagerCapabilities
import org.orynnx.outerview.core.RearCardState
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostClient
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostContract
import org.orynnx.outerview.core.hostapi.FunCardHostContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FunCardManagerTheme {
                OuterViewApp(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

}

private enum class MainDestination(val label: String) {
    ASSISTANT("助手卡片"),
    WALLPAPER("背屏壁纸"),
    ABOUT("关于"),
}

@Composable
private fun OuterViewApp(resumeTick: Int) {
    var destination by rememberSaveable { mutableStateOf(MainDestination.ASSISTANT) }
    BackHandler(enabled = destination != MainDestination.ASSISTANT) {
        destination = MainDestination.ASSISTANT
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEach { item ->
                    val icon = when (item) {
                        MainDestination.ASSISTANT -> MiuixIcons.Home
                        MainDestination.WALLPAPER -> MiuixIcons.Background
                        MainDestination.ABOUT -> MiuixIcons.Info
                    }
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = icon,
                        label = item.label,
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                val enter = slideInHorizontally(tween(260)) { width -> direction * width } +
                    fadeIn(tween(200))
                val exit = slideOutHorizontally(tween(260)) { width -> -direction * width } +
                    fadeOut(tween(200))
                enter togetherWith exit
            },
            contentAlignment = Alignment.TopStart,
            label = "main-destination",
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    MainDestination.ASSISTANT -> FunCardManagerApp(page == destination, resumeTick)
                    MainDestination.WALLPAPER -> RearWallpaperManagerApp(page == destination, resumeTick)
                    MainDestination.ABOUT -> AboutApp(page == destination, resumeTick)
                }
            }
        }
    }
}

@Composable
private fun AboutApp(active: Boolean, resumeTick: Int) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var checkingUpdate by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateMessageIsError by remember { mutableStateOf(false) }
    var checkedOnce by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf(AppUpdateDownloadState()) }
    var rememberedDownloadVersion by remember(context) {
        mutableStateOf(
            AppUpdateManager.rememberedDownloadVersion(context)?.takeIf { version ->
                runCatching {
                    AppUpdateManager.compareVersions(version, BuildConfig.VERSION_NAME) > 0
                }.getOrDefault(false)
            },
        )
    }
    var updateActionBusy by remember { mutableStateOf(false) }
    fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateMessage = null
        updateMessageIsError = false
        scope.launch {
            AppUpdateManager.checkLatest(BuildConfig.VERSION_NAME)
                .onSuccess {
                    update = it
                    updateMessage = if (it == null) "已是最新版本" else null
                    updateMessageIsError = false
                }
                .onFailure {
                    update = null
                    updateMessage = it.message ?: "无法检查更新"
                    updateMessageIsError = true
                }
            checkingUpdate = false
        }
    }
    LaunchedEffect(active) {
        if (active && !checkedOnce) {
            checkedOnce = true
            checkUpdate()
        }
    }
    LaunchedEffect(update?.version, resumeTick) {
        val storedVersion = withContext(Dispatchers.IO) {
            AppUpdateManager.rememberedDownloadVersion(context)
        }?.takeIf { version ->
            runCatching {
                AppUpdateManager.compareVersions(version, BuildConfig.VERSION_NAME) > 0
            }.getOrDefault(false)
        }
        rememberedDownloadVersion = storedVersion
        val version = update?.version ?: storedVersion
        downloadState = if (version == null) {
            AppUpdateDownloadState()
        } else {
            withContext(Dispatchers.IO) { AppUpdateManager.downloadState(context, version) }
        }
    }
    val observedDownloadVersion by rememberUpdatedState(update?.version ?: rememberedDownloadVersion)
    val observedDownloadState by rememberUpdatedState(downloadState)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context?, intent: Intent?) {
                val version = observedDownloadVersion ?: return
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (observedDownloadState.id != completedId) return
                scope.launch {
                    val refreshed = withContext(Dispatchers.IO) {
                        AppUpdateManager.downloadState(context, version)
                    }
                    downloadState = refreshed
                    updateMessageIsError = refreshed.status == AppUpdateDownloadStatus.FAILED
                    updateMessage = when (refreshed.status) {
                        AppUpdateDownloadStatus.SUCCESSFUL -> "更新安装包已下载，可验证并安装"
                        AppUpdateDownloadStatus.FAILED -> "更新下载失败，请重试"
                        else -> updateMessage
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    fun downloadUpdate(release: AppUpdateInfo) {
        if (updateActionBusy) return
        updateActionBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdateManager.enqueueDownloadResult(context, release)
            }
            if (result.isSuccess) {
                rememberedDownloadVersion = release.version
                downloadState = withContext(Dispatchers.IO) {
                    AppUpdateManager.downloadState(context, release.version)
                }
                updateMessage = if (downloadState.status == AppUpdateDownloadStatus.SUCCESSFUL) {
                    "安装包已经下载完成"
                } else {
                    "已加入系统下载队列"
                }
                updateMessageIsError = false
            } else {
                updateMessage = result.exceptionOrNull()?.message ?: "无法开始下载"
                updateMessageIsError = true
            }
            updateActionBusy = false
        }
    }
    fun installDownloaded(version: String) {
        if (updateActionBusy) return
        updateActionBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdateManager.openDownloadedApkResult(context, version)
            }
            if (result.isSuccess) {
                updateMessage = "已打开系统安装程序"
                updateMessageIsError = false
            } else {
                updateMessage = result.exceptionOrNull()?.message ?: "无法打开安装包"
                updateMessageIsError = true
            }
            rememberedDownloadVersion = withContext(Dispatchers.IO) {
                AppUpdateManager.rememberedDownloadVersion(context)
            }?.takeIf { candidate ->
                runCatching {
                    AppUpdateManager.compareVersions(candidate, BuildConfig.VERSION_NAME) > 0
                }.getOrDefault(false)
            }
            downloadState = rememberedDownloadVersion?.let { candidate ->
                withContext(Dispatchers.IO) { AppUpdateManager.downloadState(context, candidate) }
            } ?: AppUpdateDownloadState()
            updateActionBusy = false
        }
    }
    Scaffold(topBar = { TopAppBar(title = "关于", scrollBehavior = scrollBehavior) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MiuixTheme.colorScheme.primaryContainer,
                    contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OuterView", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold)
                        Text("面向小米背屏的管理工具", style = MiuixTheme.textStyles.title2)
                    }
                }
            }
            item {
                Card(
                    onClick = { uriHandler.openUri("https://github.com/Orynnx/OuterView") },
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("开源项目主页", fontWeight = FontWeight.SemiBold)
                        Text("github.com/Orynnx/OuterView", color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("版本 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Medium)
                    Text(
                        "统一管理助手卡片、背屏壁纸及其显示设置。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item { HorizontalDivider() }
            item { Text("应用更新", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold) }
            if (checkingUpdate) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在检查新版本…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            update?.let { release ->
                item {
                    Card(cornerRadius = 20.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("发现 OuterView ${release.version}", fontWeight = FontWeight.SemiBold)
                            if (release.notes.isNotBlank()) {
                                Text(release.notes, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            PrimaryButton(
                                onClick = { downloadUpdate(release) },
                                enabled = !updateActionBusy && !downloadState.inProgress &&
                                    downloadState.status != AppUpdateDownloadStatus.SUCCESSFUL,
                            ) {
                                Text(
                                    if (updateActionBusy) "正在处理…" else when (downloadState.status) {
                                        AppUpdateDownloadStatus.PENDING -> "等待下载"
                                        AppUpdateDownloadStatus.RUNNING -> "正在下载"
                                        AppUpdateDownloadStatus.PAUSED -> "下载已暂停"
                                        AppUpdateDownloadStatus.SUCCESSFUL -> "已下载"
                                        AppUpdateDownloadStatus.FAILED -> "重新下载"
                                        AppUpdateDownloadStatus.NONE -> "下载更新"
                                    },
                                )
                            }
                            TextButton(
                                onClick = { installDownloaded(release.version) },
                                enabled = !updateActionBusy &&
                                    downloadState.status == AppUpdateDownloadStatus.SUCCESSFUL,
                            ) { Text("验证并安装") }
                            TextButton(onClick = { uriHandler.openUri(release.releaseUrl) }) {
                                Text("查看完整发行说明")
                            }
                        }
                    }
                }
            }
            if (update == null && rememberedDownloadVersion != null &&
                downloadState.status != AppUpdateDownloadStatus.NONE
            ) {
                val version = rememberedDownloadVersion!!
                item {
                    Card(cornerRadius = 20.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("已恢复 OuterView $version 下载", fontWeight = FontWeight.SemiBold)
                            Text(
                                when (downloadState.status) {
                                    AppUpdateDownloadStatus.PENDING -> "正在等待系统开始下载"
                                    AppUpdateDownloadStatus.RUNNING -> "系统正在下载更新"
                                    AppUpdateDownloadStatus.PAUSED -> "系统已暂停下载"
                                    AppUpdateDownloadStatus.SUCCESSFUL -> "安装包已下载，可离线验证并安装"
                                    AppUpdateDownloadStatus.FAILED -> "下载失败，联网检查更新后可重试"
                                    AppUpdateDownloadStatus.NONE -> "没有可恢复的下载"
                                },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            PrimaryButton(
                                onClick = { installDownloaded(version) },
                                enabled = !updateActionBusy &&
                                    downloadState.status == AppUpdateDownloadStatus.SUCCESSFUL,
                            ) {
                                Text(if (updateActionBusy) "正在验证…" else "验证并安装")
                            }
                        }
                    }
                }
            }
            if (!checkingUpdate && update == null) {
                item { OutlinedButton(onClick = ::checkUpdate) { Text("重新检查更新") } }
            }
            updateMessage?.let { message ->
                item {
                    Text(
                        message,
                        color = if (updateMessageIsError) {
                            MiuixTheme.colorScheme.error
                        } else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun FunCardManagerApp(active: Boolean, resumeTick: Int) {
    val context = LocalContext.current
    val manager = remember(context) { RearCardManager.create(context) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val refreshMutex = remember { Mutex() }
    val snackbar = remember { SnackbarHostState() }
    var cards by remember { mutableStateOf<List<ManagedRearCard>>(emptyList()) }
    var capabilities by remember { mutableStateOf(RearCardManagerCapabilities()) }
    var workingKey by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<CardImportPreview?>(null) }
    var replacementTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var payloadTarget by remember { mutableStateOf<ManagedRearCard?>(null) }
    var diagnostics by remember { mutableStateOf<ManagedCardDiagnostics?>(null) }
    var deleteTarget by remember { mutableStateOf<ManagedRearCard?>(null) }
    var deleteAllRequested by remember { mutableStateOf(false) }
    var workingMessage by remember { mutableStateOf<String?>(null) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val currentWorkingKey by rememberUpdatedState(workingKey)
    val currentProviderInstanceId by rememberUpdatedState(capabilities.providerInstanceId)
    val serviceReady = capabilities.compatible && capabilities.hookReady && capabilities.managerCaptured
    val replacementTarget = cards.firstOrNull { it.cardId == replacementTargetId }

    suspend fun refresh(manual: Boolean = false) {
        refreshMutex.withLock {
            refreshing = true
            var completed = false
            try {
                // RearCardManager has a synchronous migration prefix (preferences
                // plus legacy NotificationManager cleanup) before repository code
                // reaches its own IO dispatcher. Dispatch the whole refresh so a
                // cold start can draw its loading state immediately.
                val snapshot = withContext(Dispatchers.IO) { manager.refresh() }
                loadError = snapshot.error
                capabilities = snapshot.capabilities
                if (snapshot.error == null || cards.isEmpty()) {
                    cards = snapshot.cards
                }
                if (manual) snapshot.error?.let { snackbar.showSnackbar(it) }
                completed = true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                loadError = error.message ?: "无法刷新助手卡片"
                capabilities = capabilities.copy(
                    connected = false,
                    hookReady = false,
                    managerCaptured = false,
                    error = loadError,
                )
                if (manual) snackbar.showSnackbar(loadError.orEmpty())
                completed = true
            } finally {
                if (completed) initialLoading = false
                refreshing = false
            }
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
            val resultMessage = try {
                val result = action()
                refresh()
                result.message
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                error.message ?: "操作失败，请重试"
            } finally {
                workingKey = null
                workingMessage = null
            }
            // Snackbar is feedback, not part of the operation lifecycle.  Controls
            // become available immediately instead of waiting for it to disappear.
            snackbar.showSnackbar(resultMessage)
        }
    }

    fun commitImport(preview: CardImportPreview, target: ManagedRearCard?) {
        pendingImport = null
        replacementTargetId = null
        runAction(target?.cardId ?: "import") {
            if (target == null) manager.importAndInstall(preview.token)
            else manager.replaceAndInstall(target.cardId, preview.token)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            replacementTargetId = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val requestedTargetId = replacementTargetId
            val target = requestedTargetId?.let { cardId ->
                cards.firstOrNull { it.cardId == cardId }
                    ?: withContext(Dispatchers.IO) { manager.refresh() }
                        .cards
                        .firstOrNull { it.cardId == cardId }
                    ?: run {
                        replacementTargetId = null
                        snackbar.showSnackbar("原卡片已不存在，已取消替换")
                        return@launch
                    }
            }
            workingKey = target?.cardId ?: "import"
            workingMessage = if (target == null) "正在检查卡片包…" else "正在检查替换模板…"
            var handedOff = false
            try {
                // DISPLAY_NAME is optional metadata, so do not let an untrusted
                // DocumentsProvider block the UI with another synchronous query.
                // Repository-side text validation handles opaque/hostile URI ids.
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringAfterLast(':')
                val result = manager.inspectImport(uri, name)
                val preview = result.value
                when {
                    !result.success || preview == null -> {
                        replacementTargetId = null
                        snackbar.showSnackbar(result.message.ifBlank { "导入检查失败" })
                    }
                    preview.findings.isEmpty() -> {
                        workingKey = null
                        workingMessage = null
                        handedOff = true
                        commitImport(preview, target)
                        return@launch
                    }
                    else -> pendingImport = preview
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                replacementTargetId = null
                snackbar.showSnackbar(error.message ?: "无法读取所选文件")
            } finally {
                if (!handedOff) {
                    workingKey = null
                    workingMessage = null
                }
            }
        }
    }

    LaunchedEffect(active, resumeTick) {
        if (active && resumeTick > 0) {
            refresh()
        }
    }
    DisposableEffect(context) {
        var refreshJob: Job? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context?, intent: Intent?) {
                if (
                    intent?.action == FunCardHostContract.ACTION_CARD_RUNTIME_EVENT &&
                    currentProviderInstanceId.isNotBlank() &&
                    intent.getStringExtra(FunCardHostContract.Keys.PROVIDER_INSTANCE_ID) == currentProviderInstanceId
                ) {
                    // The host posts this as soon as a card enters/leaves its runtime;
                    // it is independent of any notification popup lifecycle.
                    // An in-flight command performs its own refresh after persisting the
                    // user's intent.  Skipping this intermediate refresh prevents a late
                    // runtime callback from writing an older desiredEnabled value back.
                    if (currentWorkingKey == null) {
                        refreshJob?.cancel()
                        refreshJob = scope.launch {
                            delay(250L)
                            refresh()
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(FunCardHostContract.ACTION_CARD_RUNTIME_EVENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            refreshJob?.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "助手卡片",
                scrollBehavior = scrollBehavior,
                subtitle = when {
                    initialLoading -> "正在连接背屏服务…"
                    serviceReady -> "背屏服务已连接"
                    capabilities.compatible -> "正在等待背屏服务…"
                    else -> "背屏服务未就绪"
                },
                actions = {
                    IconButton(
                        onClick = { deleteAllRequested = true },
                        enabled = cards.isNotEmpty() && workingKey == null && !refreshing,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(MiuixIcons.Delete, contentDescription = "删除全部卡片")
                    }
                    IconButton(
                        onClick = { scope.launch { refresh(manual = true) } },
                        enabled = workingKey == null && !refreshing,
                        modifier = Modifier.size(48.dp),
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = "正在刷新助手卡片" },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(MiuixIcons.Refresh, contentDescription = "刷新助手卡片")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (workingKey != null || refreshing) return@ExtendedFloatingActionButton
                    replacementTargetId = null
                    fileLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                enabled = workingKey == null && !refreshing,
                modifier = Modifier.semantics {
                    contentDescription = "导入助手卡片"
                    if (workingKey != null || refreshing) disabled()
                },
                icon = { Icon(MiuixIcons.Add, contentDescription = null) },
                text = { Text("导入卡片") },
                expanded = workingKey == null && !refreshing,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            workingMessage?.let { message ->
                item {
                    Surface(
                        color = MiuixTheme.colorScheme.secondaryContainer,
                        contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(message, style = MiuixTheme.textStyles.body2)
                        }
                    }
                }
            }
            if (!initialLoading && !serviceReady) {
                item {
                    HookWarningBanner(
                        message = loadError,
                        retrying = refreshing,
                        onRetry = { scope.launch { refresh(manual = true) } },
                    )
                }
            }
            if (initialLoading && cards.isEmpty()) {
                item { LoadingCards() }
            } else if (cards.isEmpty() && serviceReady) {
                item {
                    EmptyCards(
                        enabled = workingKey == null && !refreshing,
                        onImport = {
                            if (workingKey != null || refreshing) return@EmptyCards
                            replacementTargetId = null
                            fileLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                    )
                }
            }
            items(cards, key = { it.cardId }) { card ->
                CompactCardRow(
                    card = card,
                    working = workingKey == card.cardId,
                    enabled = workingKey == null && !refreshing,
                    onVisibleChange = { visible ->
                        runAction(card.cardId) { manager.setVisible(card.cardId, visible) }
                    },
                    onRetry = {
                        runAction(card.cardId) {
                            if (card.hostTemplatePath == null) manager.retryInstall(card.cardId)
                            else manager.setVisible(card.cardId, card.desiredEnabled)
                        }
                    },
                    onReplace = {
                        replacementTargetId = card.cardId
                        fileLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onPayload = { payloadTarget = card },
                    onDiagnostics = {
                        scope.launch {
                            workingKey = card.cardId
                            try {
                                val result = manager.diagnostics(card.cardId)
                                diagnostics = result.value
                                if (!result.success) snackbar.showSnackbar(result.message)
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                snackbar.showSnackbar(error.message ?: "诊断失败")
                            } finally {
                                workingKey = null
                            }
                        }
                    },
                    onDelete = { if (workingKey == null) deleteTarget = card },
                )
            }
        }
    }

    if (active) {
        pendingImport?.let { preview ->
            RiskConfirmDialog(
                preview = preview,
                replacing = replacementTarget != null,
                onDismiss = {
                    manager.discardImport(preview.token)
                    pendingImport = null
                    replacementTargetId = null
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
                    }) { Text("删除", color = MiuixTheme.colorScheme.error) }
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
                    }) { Text("全部删除", color = MiuixTheme.colorScheme.error) }
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
}

@Composable
private fun HookWarningBanner(
    message: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MiuixTheme.colorScheme.errorContainer,
        contentColor = MiuixTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MiuixIcons.Report, contentDescription = null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("背屏服务未就绪", fontWeight = FontWeight.SemiBold)
            }
            Text(
                message ?: "请确认 LSPosed 中已启用 OuterView，并重启小米背屏中心后重试。",
                style = MiuixTheme.textStyles.footnote2,
            )
            OutlinedButton(onClick = onRetry, enabled = !retrying) {
                if (retrying) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("重新连接")
            }
        }
    }
}

@Composable
private fun LoadingCards() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text("正在读取助手卡片…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun EmptyCards(enabled: Boolean, onImport: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
        contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(MiuixIcons.Add, contentDescription = null, Modifier.size(40.dp), tint = MiuixTheme.colorScheme.primary)
            Text("还没有助手卡片", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.SemiBold)
            Text(
                "导入可信的 MAML ZIP 卡片，即可在小米背屏上显示。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            PrimaryButton(onClick = onImport, enabled = enabled) { Text("选择卡片文件") }
        }
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
    val desiredVisible = card.desiredEnabled
    val installed = card.hostTemplatePath != null ||
        card.state == RearCardState.INSTALLED_DISABLED ||
        card.state == RearCardState.INSTALLED_ENABLED
    val hasError = card.state == RearCardState.ERROR
    Card(
        cornerRadius = 20.dp,
        colors = CardDefaults.cardColors(containerColor = MiuixTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        card.displayName,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CardStatusPill(card = card, working = working)
                }
                if (working) {
                    CircularProgressIndicator(
                        Modifier
                            .size(24.dp)
                            .semantics { contentDescription = "正在处理 ${card.displayName}" },
                        strokeWidth = 2.dp,
                    )
                } else {
                    OverlayIconDropdownMenu(
                        entries = listOf(
                            DropdownEntry(
                                items = listOf(
                                    DropdownItem(
                                        text = "替换模板",
                                        enabled = enabled,
                                        onClick = onReplace,
                                        icon = { modifier -> Icon(MiuixIcons.Edit, null, modifier) },
                                    ),
                                    DropdownItem(
                                        text = "参数设置",
                                        enabled = enabled && !desiredVisible,
                                        onClick = onPayload,
                                        icon = { modifier -> Icon(MiuixIcons.Edit, null, modifier) },
                                    ),
                                    DropdownItem(
                                        text = "诊断",
                                        enabled = enabled,
                                        onClick = onDiagnostics,
                                        icon = { modifier -> Icon(MiuixIcons.Info, null, modifier) },
                                    ),
                                ),
                            ),
                            DropdownEntry(
                                items = listOf(
                                    DropdownItem(
                                        text = "删除",
                                        enabled = enabled,
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
                        Icon(MiuixIcons.More, contentDescription = "管理 ${card.displayName}")
                    }
                }
            }

            if (installed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = desiredVisible,
                            enabled = enabled,
                            role = Role.Switch,
                            onValueChange = onVisibleChange,
                        )
                        .semantics {
                            contentDescription = "${card.displayName}，显示到背屏"
                            stateDescription = when {
                                working -> "正在处理"
                                desiredVisible -> "已开启"
                                else -> "已关闭"
                            }
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("显示到背屏", style = MiuixTheme.textStyles.body1)
                        Text(
                            if (desiredVisible) "卡片将在背屏可用时显示" else "保留卡片，但不在背屏显示",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(
                        checked = desiredVisible,
                        onCheckedChange = null,
                        enabled = enabled,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }

            if (hasError) {
                Surface(
                    color = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            card.lastMessage.orEmpty().ifBlank { "上次操作未完成，请重试。" },
                            style = MiuixTheme.textStyles.footnote2,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRetry, enabled = enabled) {
                                Icon(MiuixIcons.Redo, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when {
                                        card.hostTemplatePath == null -> "重新安装"
                                        card.desiredEnabled -> "重试显示"
                                        else -> "重试隐藏"
                                    },
                                )
                            }
                            TextButton(onClick = onDiagnostics, enabled = enabled) {
                                Text("查看诊断")
                            }
                        }
                    }
                }
            } else if (!installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.lastMessage.orEmpty().ifBlank { "卡片尚未安装" },
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onRetry, enabled = enabled) {
                        Icon(MiuixIcons.Redo, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun CardStatusPill(card: ManagedRearCard, working: Boolean) {
    val colors = statusPillColors(card, working)
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            if (working) "正在处理…" else userStatus(card),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
        )
    }
}

private data class StatusPillColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun statusPillColors(card: ManagedRearCard, working: Boolean): StatusPillColors = when {
    working -> StatusPillColors(
        container = MiuixTheme.colorScheme.secondaryContainer,
        content = MiuixTheme.colorScheme.onSecondaryContainer,
    )
    card.cleanupPending -> StatusPillColors(
        container = MiuixTheme.colorScheme.tertiaryContainer,
        content = MiuixTheme.colorScheme.onTertiaryContainer,
    )
    card.desiredEnabled && card.state == RearCardState.INSTALLED_ENABLED -> StatusPillColors(
        container = MiuixTheme.colorScheme.primaryContainer,
        content = MiuixTheme.colorScheme.onPrimaryContainer,
    )
    card.state == RearCardState.ERROR -> StatusPillColors(
        container = MiuixTheme.colorScheme.errorContainer,
        content = MiuixTheme.colorScheme.onErrorContainer,
    )
    else -> StatusPillColors(
        container = MiuixTheme.colorScheme.surfaceContainerHigh,
        content = MiuixTheme.colorScheme.onSurfaceContainerHigh,
    )
}

private fun userStatus(card: ManagedRearCard): String = when {
    card.cleanupPending -> "正在清理"
    card.desiredEnabled && card.state == RearCardState.INSTALLED_ENABLED -> "已显示"
    card.state == RearCardState.ERROR && card.hostTemplatePath == null -> "安装失败"
    card.desiredEnabled && card.state == RearCardState.ERROR -> "显示失败"
    !card.desiredEnabled && card.state == RearCardState.ERROR -> "隐藏失败"
    card.state == RearCardState.INSTALLED_DISABLED -> "已隐藏"
    card.state == RearCardState.ERROR -> "需要处理"
    else -> "尚未安装"
}

@Composable
private fun RiskConfirmDialog(
    preview: CardImportPreview,
    replacing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledged by remember(preview.token) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replacing) "确认替换" else "确认导入") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(preview.suggestedName, style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        preview.author?.takeIf { it.isNotBlank() }?.let { "作者 $it" },
                        preview.templateVersion?.takeIf { it.isNotBlank() }?.let { "版本 $it" },
                        "${preview.entryCount} 个文件",
                        formatFileSize(preview.compressedBytes),
                    ).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    "SHA-256  ${preview.sha256}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Surface(
                    color = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        "此卡片包含外部功能调用，或存在未能完整扫描的附属文件。请仅导入来源可信、内容已确认的文件。",
                        modifier = Modifier.padding(12.dp),
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
                preview.findings.forEach { finding ->
                    Text(
                        "${safeExternalText(finding.type, 48)}: ${safeExternalText(finding.detail, 240)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("我已核对来源与上述全部风险")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = acknowledged) {
                Text(if (replacing) "仍要替换" else "仍要导入")
            }
        },
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
    val configError = remember(config) { jsonObjectError(config) }
    val rearError = remember(rear) { jsonObjectError(rear) }
    val focusError = remember(focus) { jsonObjectError(focus) }
    val payloadBytes = remember(advanced, config, rear, focus) {
        if (advanced) rear.toByteArray().size + focus.toByteArray().size else config.toByteArray().size
    }
    val canSave = payloadBytes <= 128 * 1024 &&
        if (advanced) rearError == null && focusError == null else configError == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卡片参数") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = advanced,
                            role = Role.Checkbox,
                            onValueChange = { advanced = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = advanced,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Column {
                        Text("高级模式")
                        Text(
                            "仅供调试或兼容特殊模板",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (advanced) {
                    OutlinedTextField(
                        rear,
                        { rear = it },
                        label = "背屏参数（miui.rear.param）",
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = rearError?.let { message -> { Text(message) } },
                        isError = rearError != null,
                    )
                    OutlinedTextField(
                        focus,
                        { focus = it },
                        label = "焦点参数（miui.focus.param）",
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = focusError?.let { message -> { Text(message) } },
                        isError = focusError != null,
                    )
                } else {
                    OutlinedTextField(
                        config,
                        { config = it },
                        label = "卡片配置（maml_config）",
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = configError?.let { message -> { Text(message) } },
                        isError = configError != null,
                    )
                }
                if (payloadBytes > 128 * 1024) {
                    Text(
                        "Payload 超过 128 KB，请缩短内容",
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(advanced, config, rear, focus) },
                enabled = canSave,
            ) { Text("保存") }
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
                item { DiagnosticLine("Hook 状态", diagnosticState(value.hookReady)) }
                item { DiagnosticLine("背屏管理器", diagnosticState(value.managerCaptured)) }
                item { DiagnosticLine("模板文件", diagnosticState(value.templateReadable)) }
                item { DiagnosticLine("宿主记录", diagnosticState(value.hostRegistryContains)) }
                item { DiagnosticLine("背屏事件", diagnosticState(value.notificationSeen)) }
                item { DiagnosticLine("Runtime 激活", diagnosticState(value.runtimeActivated)) }
                item { DiagnosticLine("背屏列表", diagnosticState(value.managerListContains)) }
                item { DiagnosticLine("MAML 实例", diagnosticState(value.liveWidgetContains)) }
                item { DiagnosticLine("加载尝试", if (value.loadAttempted) "已执行" else "尚未执行") }
                item { DiagnosticLine("加载结果", diagnosticState(value.loadSucceeded)) }
                item { DiagnosticLine("系统持久化", diagnosticState(value.systemPersistenceContains)) }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { DiagnosticLine("卡片 ID", value.cardId) }
                item { DiagnosticLine("业务标识", value.business) }
                item { DiagnosticLine("模板路径", value.actualTemplatePath.orEmpty().ifBlank { "无" }) }
                item { DiagnosticLine("最后命令", value.lastCommandId.orEmpty().ifBlank { "无" }) }
                item { DiagnosticLine("最后错误", value.lastError.orEmpty().ifBlank { "无" }) }
                if (value.legacyConflicts.isNotEmpty()) {
                    item { DiagnosticLine("旧版冲突", value.legacyConflicts.joinToString()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(104.dp), style = MiuixTheme.textStyles.footnote2)
        Text(
            value,
            Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun diagnosticState(ready: Boolean): String = if (ready) "正常" else "未就绪"

private fun jsonObjectError(value: String): String? = runCatching {
    JSONObject(value.ifBlank { "{}" })
}.exceptionOrNull()?.let { "请输入有效的 JSON 对象" }

private fun safeExternalText(value: String, maxCodePoints: Int): String {
    val bidiControls = setOf(
        '\u061c', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
    val normalized = value
        .filterNot { it.isISOControl() || it in bidiControls }
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isEmpty()) return "未声明"
    val count = normalized.codePointCount(0, normalized.length).coerceAtMost(maxCodePoints)
    return normalized.substring(0, normalized.offsetByCodePoints(0, count))
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

@Composable
private fun FunCardManagerTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MiuixTheme(controller = controller, content = content)
}
