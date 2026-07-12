package org.orynnx.outerview

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostClient
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostContract
import hk.uwu.reareye.funcardcore.wallpaperapi.RearWallpaperHostInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RearWallpaperManagerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RearWallpaperHostClient() }
    val snackbar = remember { SnackbarHostState() }
    var entries by remember { mutableStateOf<List<RearWallpaperHostInfo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RearWallpaperHostInfo?>(null) }
    var renameTarget by remember { mutableStateOf<RearWallpaperHostInfo?>(null) }
    var renameName by remember { mutableStateOf("") }
    suspend fun refresh() {
        if (!client.connect(context)) { snackbar.showSnackbar("未连接到背屏壁纸 Host Hook"); return }
        entries = client.list().filter { it.managed }
    }
    fun runAction(action: () -> android.os.Bundle) {
        if (busy) return
        busy = true
        scope.launch { try { val result = action(); snackbar.showSnackbar(result.getString(RearWallpaperHostContract.Keys.MESSAGE).orEmpty()); refresh() } finally { busy = false } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "wallpaper.mrc" } ?: "wallpaper.mrc"
        runAction { context.contentResolver.openFileDescriptor(uri, "r")!!.use { client.import(it, name) } }
    }
    LaunchedEffect(Unit) { refresh() }
    Scaffold(
        topBar = { TopAppBar(title = { Text("背屏壁纸", fontWeight = FontWeight.SemiBold) }, actions = { IconButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "刷新") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { IconButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) { Icon(Icons.Rounded.Add, "导入壁纸") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("此处仅显示通过 OuterView 添加的壁纸。", style = MaterialTheme.typography.bodySmall) }
            if (entries.isEmpty()) item { Text("还没有 OuterView 壁纸。", modifier = Modifier.padding(top = 24.dp)) }
            items(entries, key = { it.wallpaperId }) { item -> Card(Modifier.fillMaxWidth().animateContentSize()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("由 OuterView 管理", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ElevatedButton(onClick = { runAction { client.apply(item.wallpaperId) } }, enabled = !busy) { Text(if (item.current) "当前壁纸" else "应用") }
                    TextButton(onClick = { renameTarget = item; renameName = item.name }, enabled = !busy) { Icon(Icons.Rounded.Edit, null); Spacer(Modifier.width(4.dp)); Text("重命名") }
                    if (item.managed) TextButton(onClick = { deleteTarget = item }, enabled = !busy) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(4.dp)); Text("删除") }
                }
            }} }
        }
    }
    renameTarget?.let { item -> AlertDialog(
        onDismissRequest = { renameTarget = null },
        title = { Text("重命名壁纸") },
        text = { OutlinedTextField(renameName, { renameName = it.take(48) }, singleLine = true, label = { Text("名称") }) },
        confirmButton = { TextButton(onClick = { val name = renameName.trim(); renameTarget = null; runAction { client.rename(item.wallpaperId, name) } }, enabled = renameName.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
    ) }
    deleteTarget?.let { item -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除壁纸？") }, text = { Text("将从背屏 runtime 移除 ${item.name}，此操作不可撤销。") }, confirmButton = { TextButton(onClick = { deleteTarget = null; runAction { client.delete(item.wallpaperId) } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }) }
}
