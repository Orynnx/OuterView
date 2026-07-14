package org.orynnx.outerview

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.provider.OpenableColumns
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import hk.uwu.reareye.funcardcore.CardImportPreview
import hk.uwu.reareye.funcardcore.ManagedCardDiagnostics
import hk.uwu.reareye.funcardcore.ManagedRearCard
import hk.uwu.reareye.funcardcore.RearCardActionResult
import hk.uwu.reareye.funcardcore.RearCardManager
import hk.uwu.reareye.funcardcore.RearCardManagerCapabilities
import hk.uwu.reareye.funcardcore.RearCardState
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostClient
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostContract
import hk.uwu.reareye.funcardcore.hostapi.FunCardHostContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runDebugWallpaperAction()
        runDebugAssistantAction()
        setContent {
            FunCardManagerTheme {
                OuterViewApp()
            }
        }
    }

    private fun runDebugWallpaperAction() {
        if (!BuildConfig.DEBUG) return
        val action = intent?.action ?: return
        if (action !in setOf("org.orynnx.outerview.DEBUG_APPLY_WALLPAPER", "org.orynnx.outerview.DEBUG_IMPORT_WALLPAPER", "org.orynnx.outerview.DEBUG_RENAME_WALLPAPER")) return
        Thread {
            val client = RearWallpaperHostClient()
            val result = runCatching {
                check(client.connect(applicationContext)) { "wallpaper host not connected" }
                when (action) {
                    "org.orynnx.outerview.DEBUG_APPLY_WALLPAPER" -> {
                        val wallpaperId = intent.getIntExtra("wallpaperId", Int.MIN_VALUE)
                        check(wallpaperId != Int.MIN_VALUE) { "missing wallpaperId" }
                        client.apply(wallpaperId)
                    }
                    "org.orynnx.outerview.DEBUG_RENAME_WALLPAPER" -> {
                        val wallpaperId = intent.getIntExtra("wallpaperId", Int.MIN_VALUE)
                        check(wallpaperId != Int.MIN_VALUE) { "missing wallpaperId" }
                        client.rename(wallpaperId, intent.getStringExtra("name").orEmpty())
                    }
                    else -> {
                        val path = intent.getStringExtra("path") ?: error("missing path")
                        ParcelFileDescriptor.open(java.io.File(path), ParcelFileDescriptor.MODE_READ_ONLY).use {
                            client.import(it, java.io.File(path).name)
                        }
                    }
                }
            }.getOrElse { Bundle().apply { putBoolean(RearWallpaperHostContract.Keys.SUCCESS, false); putString(RearWallpaperHostContract.Keys.MESSAGE, it.message) } }
            Log.i("OuterView-Wallpaper-Test", "action=$action id=${result.getInt(RearWallpaperHostContract.Keys.WALLPAPER_ID, Int.MIN_VALUE)} success=${result.getBoolean(RearWallpaperHostContract.Keys.SUCCESS)} message=${result.getString(RearWallpaperHostContract.Keys.MESSAGE)}")
        }.start()
    }

    private fun runDebugAssistantAction() {
        if (!BuildConfig.DEBUG) return
        val action = intent?.action ?: return
        if (action !in setOf(
                "org.orynnx.outerview.DEBUG_SHOW_ASSISTANT",
                "org.orynnx.outerview.DEBUG_HIDE_ASSISTANT",
                "org.orynnx.outerview.DEBUG_DELETE_ASSISTANT",
            )
        ) return
        Thread {
            runCatching {
                runBlocking {
                    val manager = RearCardManager.create(applicationContext)
                    val cardId = intent.getStringExtra("cardId")
                        ?: manager.refresh().cards.firstOrNull()?.cardId
                        ?: error("no managed Assistant card")
                    when (action) {
                        "org.orynnx.outerview.DEBUG_SHOW_ASSISTANT" -> manager.setVisible(cardId, true)
                        "org.orynnx.outerview.DEBUG_HIDE_ASSISTANT" -> manager.setVisible(cardId, false)
                        else -> manager.deleteCard(cardId)
                    }
                }
            }.onSuccess { result ->
                Log.i(
                    "OuterView-Assistant-Test",
                    "action=$action success=${result.success} state=${result.state} message=${result.message}",
                )
            }.onFailure { error ->
                Log.e("OuterView-Assistant-Test", "action=$action failed", error)
            }
        }.start()
    }
}

private enum class MainDestination(val label: String) {
    ASSISTANT("助手卡片"),
    WALLPAPER("背屏壁纸"),
    ABOUT("关于"),
}

@Composable
private fun OuterViewApp() {
    var destination by rememberSaveable { mutableStateOf(MainDestination.ASSISTANT) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEach { item ->
                    val icon = when (item) {
                        MainDestination.ASSISTANT -> Icons.Rounded.Home
                        MainDestination.WALLPAPER -> Icons.Rounded.Wallpaper
                        MainDestination.ABOUT -> Icons.Rounded.Info
                    }
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .clipToBounds(),
        ) {
            MainDestination.entries.forEach { page ->
                val active = destination == page
                val targetOffset = when {
                    active -> 0.dp
                    page.ordinal < destination.ordinal -> -maxWidth
                    else -> maxWidth
                }
                val offsetX by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = tween(260),
                    label = "${page.name}-offset",
                )
                val pageAlpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = tween(200),
                    label = "${page.name}-alpha",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset(x = offsetX)
                        .alpha(pageAlpha)
                        .zIndex(if (active) 1f else 0f)
                        .then(
                            if (active) {
                                Modifier
                            } else {
                                Modifier
                                    .clearAndSetSemantics { }
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(PointerEventPass.Initial)
                                                    .changes
                                                    .forEach { it.consume() }
                                            }
                                        }
                                    }
                            },
                        ),
                ) {
                    when (page) {
                        MainDestination.ASSISTANT -> FunCardManagerApp(active)
                        MainDestination.WALLPAPER -> RearWallpaperManagerApp(active)
                        MainDestination.ABOUT -> AboutApp(active)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutApp(active: Boolean) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var checkedOnce by remember { mutableStateOf(false) }
    var queuedDownloadVersion by remember { mutableStateOf<String?>(null) }
    var downloadButtonCoolingDown by remember { mutableStateOf(false) }
    fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateMessage = null
        scope.launch {
            AppUpdateManager.checkLatest(BuildConfig.VERSION_NAME)
                .onSuccess { update = it; updateMessage = if (it == null) "已是最新版本" else null }
                .onFailure { update = null; updateMessage = it.message ?: "无法检查更新" }
            checkingUpdate = false
        }
    }
    LaunchedEffect(active) {
        if (active && !checkedOnce) {
            checkedOnce = true
            checkUpdate()
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("关于", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OuterView", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("面向小米背屏的管理工具", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "基于 REAREye 的探索成果构建 · 开发者凛野",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            item {
                Card(
                    onClick = { uriHandler.openUri("https://github.com/Orynnx/OuterView") },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("开源项目主页", fontWeight = FontWeight.SemiBold)
                        Text("github.com/Orynnx/OuterView", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("版本 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Medium)
                    Text(
                        "统一管理助手卡片、背屏壁纸及其显示设置。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { HorizontalDivider() }
            item { Text("应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (checkingUpdate) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在检查新版本…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else update?.let { release ->
                item {
                    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("发现 OuterView ${release.version}", fontWeight = FontWeight.SemiBold)
                            if (release.notes.isNotBlank()) {
                                Text(release.notes, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                onClick = {
                                    downloadButtonCoolingDown = true
                                    runCatching { AppUpdateManager.enqueueDownload(context, release) }
                                        .onSuccess {
                                            queuedDownloadVersion = release.version
                                            updateMessage = "已加入下载；完成后可点击系统通知安装"
                                            scope.launch {
                                                delay(1_500L)
                                                downloadButtonCoolingDown = false
                                            }
                                        }
                                        .onFailure {
                                            updateMessage = it.message ?: "无法开始下载"
                                            downloadButtonCoolingDown = false
                                        }
                                },
                                enabled = !downloadButtonCoolingDown,
                            ) {
                                Text(
                                    when {
                                        downloadButtonCoolingDown -> "正在加入…"
                                        queuedDownloadVersion == release.version -> "重新下载"
                                        else -> "下载更新"
                                    },
                                )
                            }
                            TextButton(onClick = {
                                if (!AppUpdateManager.openDownloadedApk(context, release.version)) {
                                    updateMessage = "安装包尚未下载完成"
                                }
                            }) { Text("安装已下载版本") }
                        }
                    }
                }
            } ?: item {
                OutlinedButton(onClick = ::checkUpdate) { Text("重新检查更新") }
            }
            updateMessage?.let { message ->
                item {
                    Text(
                        message,
                        color = if (message.contains("无法") || message.contains("失败")) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunCardManagerApp(active: Boolean) {
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
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val currentWorkingKey by rememberUpdatedState(workingKey)
    val serviceReady = capabilities.compatible && capabilities.hookReady && capabilities.managerCaptured

    suspend fun refresh(manual: Boolean = false) {
        if (refreshing) return
        refreshing = true
        var completed = false
        try {
            val snapshot = manager.refresh()
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
            workingMessage = if (target == null) "正在检查卡片包…" else "正在检查替换模板…"
            var handedOff = false
            try {
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
                        workingMessage = null
                        handedOff = true
                        commitImport(preview, target)
                        return@launch
                    }
                    else -> pendingImport = preview
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                replacementTarget = null
                snackbar.showSnackbar(error.message ?: "无法读取所选文件")
            } finally {
                if (!handedOff) {
                    workingKey = null
                    workingMessage = null
                }
            }
        }
    }

    LaunchedEffect(active) {
        if (active) {
            // A previous tab activation may still be unwinding a blocking host
            // connection.  Wait for it instead of dropping this activation.
            while (refreshing) delay(50L)
            refresh()
        }
    }
    DisposableEffect(context) {
        var refreshJob: Job? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context?, intent: Intent?) {
                if (intent?.action == FunCardHostContract.ACTION_CARD_RUNTIME_EVENT) {
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
                title = {
                    Column {
                        Text("助手卡片", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                initialLoading -> "正在连接背屏服务…"
                                serviceReady -> "背屏服务已连接"
                                capabilities.compatible -> "正在等待背屏服务…"
                                else -> "背屏服务未就绪"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (serviceReady) {
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
                        enabled = cards.isNotEmpty() && workingKey == null && !refreshing,
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "删除全部卡片")
                    }
                    IconButton(
                        onClick = { scope.launch { refresh(manual = true) } },
                        enabled = workingKey == null && !refreshing,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = "正在刷新助手卡片" },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新助手卡片")
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
                    replacementTarget = null
                    fileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                modifier = Modifier.semantics {
                    contentDescription = "导入助手卡片"
                    if (workingKey != null || refreshing) disabled()
                },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("导入卡片") },
                expanded = workingKey == null && !refreshing,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            workingMessage?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(message, style = MaterialTheme.typography.bodyMedium)
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
            } else if (cards.isEmpty()) {
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
                        replacementTarget = card
                        fileLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
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
}

@Composable
private fun HookWarningBanner(
    message: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("背屏服务未就绪", fontWeight = FontWeight.SemiBold)
            }
            Text(
                message ?: "请确认 LSPosed 中已启用 OuterView，并重启小米背屏中心后重试。",
                style = MaterialTheme.typography.bodySmall,
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
        Text("正在读取助手卡片…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCards(onImport: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text("还没有助手卡片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "导入可信的 MAML ZIP 卡片，即可在小米背屏上显示。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport) { Text("选择卡片文件") }
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
    var menuExpanded by remember(card.cardId) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        card.displayName,
                        style = MaterialTheme.typography.titleMedium,
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
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "管理 ${card.displayName}")
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
                                text = { Text("参数设置") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                enabled = enabled && !desiredVisible,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        Text("显示到背屏", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (desiredVisible) "卡片将在背屏可用时显示" else "保留卡片，但不在背屏显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            card.lastMessage.orEmpty().ifBlank { "上次操作未完成，请重试。" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRetry, enabled = enabled) {
                                Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp))
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

@Composable
private fun CardStatusPill(card: ManagedRearCard, working: Boolean) {
    val color = if (working) MaterialTheme.colorScheme.secondary else statusColor(card)
    Surface(
        color = color.copy(alpha = 0.13f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            if (working) "正在处理…" else userStatus(card),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
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
private fun statusColor(card: ManagedRearCard): Color = when {
    card.cleanupPending -> MaterialTheme.colorScheme.tertiary
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(preview.suggestedName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        preview.author?.takeIf { it.isNotBlank() }?.let { "作者 $it" },
                        preview.templateVersion?.takeIf { it.isNotBlank() }?.let { "版本 $it" },
                        "${preview.entryCount} 个文件",
                        formatFileSize(preview.compressedBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        "此卡片包含可调用外部功能的命令。请仅导入来源可信、内容已确认的文件。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                preview.findings.take(8).forEach { finding ->
                    Text(
                        "${finding.type}: ${finding.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (preview.findings.size > 8) {
                    Text(
                        "另有 ${preview.findings.size - 8} 项未显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (replacing) "仍要替换" else "仍要导入") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卡片参数") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = advanced, onCheckedChange = { advanced = it })
                    Column {
                        Text("高级模式")
                        Text(
                            "仅供调试或兼容特殊模板",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (advanced) {
                    OutlinedTextField(
                        rear,
                        { rear = it },
                        label = { Text("背屏参数（miui.rear.param）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        focus,
                        { focus = it },
                        label = { Text("焦点参数（miui.focus.param）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        config,
                        { config = it },
                        label = { Text("卡片配置（maml_config）") },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
        Text(label, Modifier.width(104.dp), style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun diagnosticState(ready: Boolean): String = if (ready) "正常" else "未就绪"

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

@Composable
private fun FunCardManagerTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF81D5C2),
            secondary = Color(0xFFB4CCC5),
            tertiary = Color(0xFFE6C36D),
            background = Color(0xFF101412),
            surface = Color(0xFF101412),
            surfaceContainer = Color(0xFF1C211F),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF176B5C),
            secondary = Color(0xFF52645F),
            tertiary = Color(0xFF765A1B),
            surface = Color(0xFFFFF9FD),
            surfaceContainer = Color(0xFFF3EDF1),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
