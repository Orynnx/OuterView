package org.orynnx.outerview

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OuterViewTheme {
                AboutApp(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

@Composable
private fun AboutApp(resumeTick: Int) {
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
    LaunchedEffect(Unit) {
        if (!checkedOnce) {
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
    Scaffold(topBar = {
        TopAppBar(
            title = "关于",
            scrollBehavior = scrollBehavior,
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
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
                        Text("小米背屏智能应用管理器", style = MiuixTheme.textStyles.title2)
                        Text("导入本地应用包，与系统 AI 应用管理同步。", style = MiuixTheme.textStyles.body2)
                        Text("请从系统设置 → 背屏 → OuterView 智能应用进入管理页面。", style = MiuixTheme.textStyles.body2)
                    }
                }
            }
            item {
                Card(
                    onClick = { uriHandler.openUri("https://github.com/Orynnx/OuterView") },
                    showIndication = true,
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
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    BasicComponent(title = "当前版本", summary = BuildConfig.VERSION_NAME)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    BasicComponent(
                        title = "开源许可",
                        summary = "GPL-3.0 · 查看许可与第三方声明",
                        onClick = { uriHandler.openUri("https://github.com/Orynnx/OuterView/blob/main/LICENSE") },
                        endActions = { Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                    )
                    BasicComponent(
                        title = "第三方声明",
                        summary = "依赖组件的版权与许可",
                        onClick = { uriHandler.openUri("https://github.com/Orynnx/OuterView/tree/main/LICENSES") },
                        endActions = { Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
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
                            Button(colors = ButtonDefaults.buttonColorsPrimary(),
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
                            Button(
                                onClick = { installDownloaded(release.version) },
                                enabled = !updateActionBusy &&
                                    downloadState.status == AppUpdateDownloadStatus.SUCCESSFUL,
                            ) { Text("验证并安装") }
                            Button(onClick = { uriHandler.openUri(release.releaseUrl) }) {
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
                            Button(colors = ButtonDefaults.buttonColorsPrimary(),
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
                item { Button(onClick = ::checkUpdate) { Text("重新检查更新") } }
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
internal fun OuterViewTheme(content: @Composable () -> Unit) {
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
