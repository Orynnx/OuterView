package org.orynnx.outerview.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import org.orynnx.outerview.core.internal.ManagedRearWallpaperPaths
import org.orynnx.outerview.core.internal.RearWallpaperPackageValidator
import org.orynnx.outerview.core.internal.RearWallpaperRuntimeCodec
import org.orynnx.outerview.core.internal.RearWallpaperRuntimeRecord
import org.orynnx.outerview.core.wallpaperapi.IRearWallpaperHostConnection
import org.orynnx.outerview.core.wallpaperapi.IRearWallpaperHostService
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostContract
import org.orynnx.outerview.hook.dex.HostDexResolver
import org.orynnx.outerview.hook.dex.HostMethodQuery
import org.orynnx.outerview.hook.dex.HostMethodRef
import org.orynnx.outerview.hook.dex.PUBLIC_STATIC
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns only OuterView-prefixed entries in the rear-screen wallpaper runtime. */
class RearWallpaperHostHook : YukiBaseHooker() {
    companion object { private const val TAG = "OuterView-Wallpaper"; private const val APP = "org.orynnx.outerview" }
    private var context: Context? = null
    private var receiverRegistered = false
    private val lock = Any()
    private var hostDex: HostDexResolver? = null
    private var mainPanel: Any? = null
    private var mainHandler: Handler? = null
    // Last wallpaper the user actually selected, from OuterView or the system panel.
    // Cleared when a non-managed wallpaper is picked so we never treat an absent id
    // as "one of ours is still current".
    private var appliedId: Int? = null
    @Volatile private var restoringManagedWallpaper = false

    override fun onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode = hostPackageVersionCode(systemContext, appInfo.packageName, appInfo.sourceDir)
            hostDex = HostDexResolver.open(appInfo.sourceDir, appInfo.dataDir, versionCode)
            "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve().firstMethod {
                name = "attachBaseContext"; parameterCount = 1
            }.hook().after {
                Handler(Looper.getMainLooper()).post {
                    context = ((args[0] as? Context) ?: (instance as? Context) ?: currentApplication())?.applicationContext
                    registerReceiver()
                    YLog.info("[$TAG] host attached receiver=$receiverRegistered")
                }
            }
            "com.xiaomi.subscreencenter.SubScreenLauncher".toClass().resolve().firstMethod {
                name = "onCreate"; parameterCount = 1
            }.hook().after { capturePanel(instance) }
            "com.xiaomi.subscreencenter.SubScreenLauncher".toClass().resolve().firstMethod {
                name = "onResume"; parameterCount = 0
            }.hook().after { capturePanel(instance) }
            runCatching {
                val point = selectPoint()
                point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 2 }.hook().before {
                    val incoming = args.getOrNull(0) as? List<Any> ?: return@before
                    val augmented = augmentWithManagedWidgets(incoming)
                    if (augmented.size != incoming.size) {
                        args[0] = augmented
                        YLog.info("[$TAG] system list augmented ${incoming.size}->${augmented.size}")
                    }
                }
                point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 2 }.hook().after {
                    val widgets = args.getOrNull(0) as? List<*> ?: return@after
                    val index = args.getOrNull(1) as? Int ?: return@after
                    val widget = widgets.getOrNull(index) ?: return@after
                    val ids = RearWallpaperRuntimeCodec.decode(runtimeFile().takeIf(File::isFile)?.readText().orEmpty()).mapTo(HashSet()) { it.wallpaperId }
                    val selectedManagedId = intFields(widget).firstOrNull(ids::contains)
                    if (selectedManagedId != null) {
                        appliedId = selectedManagedId
                        YLog.info("[$TAG] MainPanel selected index=$index managedWallpaperId=$selectedManagedId")
                        restoreManagedWallpaperAfterHostSelection()
                    } else {
                        appliedId = null
                        clearManagedWallpaperCurrent()
                        YLog.info("[$TAG] MainPanel selected non-managed wallpaper at index=$index; cleared current marker")
                    }
                }
            }.onFailure { YLog.error("[$TAG] selection observer install failed", it) }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val host = context ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != RearWallpaperHostContract.ACTION_REQUEST_SERVICE) return
                val binder = intent.getBundleExtra(RearWallpaperHostContract.EXTRA_BUNDLE)
                    ?.getBinder(RearWallpaperHostContract.EXTRA_CALLBACK) ?: return
                runCatching { IRearWallpaperHostConnection.Stub.asInterface(binder)?.onServiceConnected(service) }
                    .onFailure { YLog.error("[$TAG] callback failed", it) }
                YLog.info("[$TAG] service callback delivered")
            }
        }
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(receiver, IntentFilter(RearWallpaperHostContract.ACTION_REQUEST_SERVICE), Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") host.registerReceiver(receiver, IntentFilter(RearWallpaperHostContract.ACTION_REQUEST_SERVICE))
        receiverRegistered = true
        YLog.info("[$TAG] receiver registered")
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private val service = object : IRearWallpaperHostService.Stub() {
        override fun getCapabilities(): Bundle { enforceCaller(); return Bundle().apply { putInt(RearWallpaperHostContract.Keys.API_VERSION, RearWallpaperHostContract.API_VERSION); putBoolean(RearWallpaperHostContract.Keys.HOOK_READY, context != null); putBoolean(RearWallpaperHostContract.Keys.PANEL_READY, mainPanel != null) } }
        override fun listWallpapers(): Bundle { enforceCaller(); return catalog() }
        override fun importWallpaper(packageFd: ParcelFileDescriptor?, displayName: String?): Bundle { enforceCaller(); return import(packageFd, displayName) }
        override fun applyWallpaper(wallpaperId: Int): Bundle { enforceCaller(); return apply(wallpaperId) }
        override fun renameWallpaper(wallpaperId: Int, displayName: String?): Bundle { enforceCaller(); return rename(wallpaperId, displayName) }
        override fun deleteWallpaper(wallpaperId: Int): Bundle { enforceCaller(); return delete(wallpaperId) }
    }

    private fun catalog(): Bundle = synchronized(lock) {
        val records = RearWallpaperRuntimeCodec.decode(runtimeFile().takeIf(File::isFile)?.readText().orEmpty())
            .filter { ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), it) }
        Bundle().apply { putParcelableArrayList(RearWallpaperHostContract.Keys.ITEMS, ArrayList(records.map { record -> Bundle().apply {
            putInt(RearWallpaperHostContract.Keys.WALLPAPER_ID, record.wallpaperId); putString(RearWallpaperHostContract.Keys.RES_ID, record.resId)
            putString(RearWallpaperHostContract.Keys.NAME, resolveDisplayName(record)); putString(RearWallpaperHostContract.Keys.PATH, record.resLocalPath)
            putBoolean(RearWallpaperHostContract.Keys.MANAGED, ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)); putBoolean(RearWallpaperHostContract.Keys.CURRENT, appliedId == record.wallpaperId)
        }})) }
    }

    private fun apply(id: Int): Bundle = runCatching {
        YLog.info("[$TAG] apply requested id=$id panel=${mainPanel != null}")
        val panel = mainPanel ?: error("背屏 MainPanel 尚未就绪")
        // MainPanel may replace its list during onResume; resolve after a final
        // managed-only injection to avoid racing that lifecycle update.
        injectManagedWidgetsIntoPanel()
        YLog.info("[$TAG] apply widgetLists=" + widgetLists().joinToString { list ->
            "${list.size}:${list.firstOrNull()?.javaClass?.name}:${list.flatMap { intFields(it) }.take(3)}"
        })
        val existing = widgetLists().firstOrNull { list ->
            list.any { widget -> intFields(widget).any { candidate -> candidate == id } }
        } ?: existingWidgets()
        var widgets: List<Any> = emptyList()
        var index = -1
        if (existing.isNotEmpty()) {
            widgets = existing
            index = widgets.indexOfFirst { intFields(it).any { candidate -> candidate == id } }
            YLog.info("[$TAG] apply existingWidgets=${widgets.size} id=$id index=$index")
        }
        if (index < 0) {
            val specs = loadRuntimeSpecs()
            val specIds = specs.map { spec -> intFields(spec).joinToString(",") }
            index = specs.indexOfFirst { spec -> intFields(spec).any { candidate -> candidate == id } }
            YLog.info("[$TAG] apply runtimeSpecs=${specs.size} ids=$specIds id=$id index=$index")
            require(index >= 0) { "wallpaper spec not found in host runtime" }
            val target = createWidget(specs[index], id)
                ?: error("host failed to create wallpaper widget for spec id=$id")
            // Keep the actual current MainPanel list (rather than rebuilding every
            // runtime spec: Xiaomi's factory deliberately returns null for some
            // stock entries) and append only the requested managed widget.
            widgets = existing + target
            index = widgets.lastIndex
            YLog.info("[$TAG] apply appended managed widget id=$id size=${widgets.size} index=$index")
        }
        require(index >= 0) { "wallpaper widget is not loaded; reopen rear screen and retry" }
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val task = Runnable { runCatching {
            panel.asResolver().firstMethod { name = selectPoint().methodName; parameterCount = 2 }
                .invoke(widgets, index)
            panel.asResolver().firstMethod { name = saveSelectionPoint().methodName; parameterCount = 0 }.invoke()
            appliedId = id
            // Persist only after the host accepted the switch; if this throws, the
            // previous outerviewCurrent marker stays valid for restore-on-reboot.
            markManagedWallpaperCurrent(id)
            YLog.info("[$TAG] apply dispatched id=$id index=$index")
        }.onFailure { failure.set(it); YLog.error("[$TAG] MainPanel apply failed", it) }.also { completed.countDown() }
        }
        check((mainHandler ?: Handler(context!!.mainLooper)).post(task)) { "failed to post MainPanel action" }
        check(completed.await(5, TimeUnit.SECONDS)) { "MainPanel apply timed out" }
        failure.get()?.let { throw it }
        success("已请求背屏应用壁纸", id)
    }.getOrElse { YLog.error("[$TAG] apply failed", it); failure(it.message ?: "应用壁纸失败", "APPLY_FAILED") }

    private fun capturePanel(launcher: Any?) {
        val targetClass = runCatching { selectPoint().className }.getOrNull() ?: return
        launcher?.javaClass?.declaredFields?.forEach { field -> runCatching {
            field.isAccessible = true
            val value = field.get(launcher)
            if (value != null && value.javaClass.name == targetClass) mainPanel = value
            if (value is Handler) mainHandler = value
        } }
        injectManagedWidgetsIntoPanel()
    }

    /**
     * Xiaomi builds MainPanel's list before third-party runtime entries are
     * materialised.  Keep the system-owned list intact and append only widgets
     * whose runtime records are demonstrably owned by OuterView.
     */
    private fun injectManagedWidgetsIntoPanel() = runCatching {
        mainPanel ?: return@runCatching Unit
        val existing = existingWidgets()
        val mutable = existing as? MutableList<Any> ?: return@runCatching Unit
        val managedIds = RearWallpaperRuntimeCodec.decode(
            runtimeFile().takeIf(File::isFile)?.readText().orEmpty(),
        ).filter { ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), it) }
            .mapTo(HashSet()) { it.wallpaperId }
        if (managedIds.isEmpty()) return@runCatching Unit
        val presentIds = existing.flatMapTo(HashSet()) { intFields(it) }
        loadRuntimeSpecs().forEach { spec ->
            val id = intFields(spec).firstOrNull { it in managedIds } ?: return@forEach
            if (id in presentIds) return@forEach
            val widget = createWidget(spec, id) ?: return@forEach
            mutable.add(widget)
            presentIds += id
            YLog.info("[$TAG] injected managed widget specId=$id widgetInts=${intFields(widget)} size=${mutable.size} class=${widget.javaClass.name}")
        }
    }.onFailure { YLog.error("[$TAG] managed widget injection failed", it) }

    private fun augmentWithManagedWidgets(base: List<Any>): List<Any> {
        val managedIds = RearWallpaperRuntimeCodec.decode(
            runtimeFile().takeIf(File::isFile)?.readText().orEmpty(),
        ).filter { ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), it) }
            .mapTo(HashSet()) { it.wallpaperId }
        if (managedIds.isEmpty()) return base
        val present = base.flatMapTo(HashSet()) { intFields(it) }
        val additions = loadRuntimeSpecs().mapNotNull { spec ->
            val id = intFields(spec).firstOrNull { it in managedIds && it !in present } ?: return@mapNotNull null
            createWidget(spec, id)?.also { present += id }
        }
        return if (additions.isEmpty()) base else base + additions
    }

    private fun existingWidgets(): List<Any> {
        return widgetLists().maxByOrNull { list ->
            list.count { item -> intFields(item).isNotEmpty() }
        }.orEmpty()
    }

    private fun widgetLists(): List<List<Any>> {
        val panel = mainPanel ?: return emptyList()
        return generateSequence(panel.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence().mapNotNull { field -> runCatching {
                field.isAccessible = true
                (field.get(panel) as? List<*>)?.filterNotNull()?.takeIf { it.isNotEmpty() }
            }.getOrNull() } }
            .toList()
    }

    private fun loadRuntimeSpecs(): List<Any> {
        val point = runtimeListPoint()
        return point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 1 }.invoke(true) as? List<Any> ?: emptyList()
    }
    private fun createWidget(spec: Any, specId: Int? = null): Any? = runCatching {
        val point = widgetFactoryPoint(spec.javaClass.name)
        point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 1 }.invoke(spec)
    }.onSuccess { result -> if (result == null) YLog.warn("[$TAG] widget factory returned null spec=${spec.javaClass.name} id=$specId") }
        .onFailure { YLog.error("[$TAG] widget factory failed spec=${spec.javaClass.name}", it) }.getOrNull()
    private fun intFields(target: Any): List<Int> = generateSequence(target.javaClass) { it.superclass }.flatMap { clazz -> clazz.declaredFields.asSequence().mapNotNull { field -> runCatching { field.isAccessible = true; (field.get(target) as? Int) }.getOrNull() } }.toList()
    private fun resolvePoint(key: String, query: HostMethodQuery): HostMethodRef =
        hostDex?.method(key, query) ?: error("Host DEX lookup failed: $key")

    private fun runtimeListPoint(): HostMethodRef = resolvePoint(
        "OV_WALLPAPER_RUNTIME_LIST",
        HostMethodQuery(
            parameterCount = 1,
            returnType = "java.util.List",
            strings = setOf(
                "/data/system/theme_magic/users/\$user_id/rearScreen/runtime.json",
                "/system/media/rearscreen/template/default/rearScreen.json",
            ),
        ),
    )

    /** HyperOS 4 identifies the widget factory by its spec type and snapshot marker. */
    private fun widgetFactoryPoint(specClassName: String): HostMethodRef = resolvePoint(
        "OV_WALLPAPER_WIDGET_FACTORY_$specClassName",
        HostMethodQuery(
            parameterTypes = listOf(specClassName),
            requiredModifiers = PUBLIC_STATIC,
            strings = setOf("snapshotPath_"),
        ),
    )

    private fun selectPoint(): HostMethodRef = resolvePoint(
        "OV_MAIN_PANEL_SELECT",
        HostMethodQuery(
            packagePrefix = "com.xiaomi.subscreencenter",
            parameterCount = 2,
            returnType = "void",
            strings = setOf(
                "SubScreenWidgets is empty, at least one needs to be provided !!!",
                "onSubScreenWidgetChanged, new widgets size = ",
            ),
        ),
    )

    private fun saveSelectionPoint(): HostMethodRef = resolvePoint(
        "OV_MAIN_PANEL_SAVE_SELECTION",
        HostMethodQuery(
            packagePrefix = "com.xiaomi.subscreencenter",
            parameterCount = 0,
            returnType = "void",
            strings = setOf("Save user select, new index = ", "user_select"),
        ),
    )

    private fun import(fd: ParcelFileDescriptor?, name: String?): Bundle = runCatching { synchronized(lock) {
        requireNotNull(fd) { "empty wallpaper file" }; val sourceName = name?.takeIf(String::isNotBlank) ?: "wallpaper.mrc"
        require(sourceName.endsWith(".mrc", true) || sourceName.endsWith(".zip", true)) { "only MRC/ZIP wallpaper packages are supported" }
        val displayName = friendlyDisplayName(sourceName)
        val resId = ManagedRearWallpaperPaths.ResourcePrefix + UUID.randomUUID().toString().replace('-', '_')
        val applyId = System.currentTimeMillis().toString(); val dir = ManagedRearWallpaperPaths.resourceDirectory(runtimeRoot(), resId, applyId)
        check(dir.mkdirs()) { "failed to create wallpaper directory" }; val packageFile = File(dir, "wallpaper.mrc")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> packageFile.outputStream().use(input::copyTo) }
        RearWallpaperPackageValidator.inspect(packageFile)
        val metadata = File(dir, "metadata.mrm").apply { writeText(JSONObject().put("resId", resId).put("resName", displayName).toString()) }
        val old = runtimeFile().takeIf(File::isFile)?.readText().orEmpty(); val record = RearWallpaperRuntimeRecord(resId, applyId, packageFile.path, metadata.path, null, RearWallpaperRuntimeCodec.decode(old).maxOfOrNull { it.position }?.plus(1) ?: 0, displayName)
        writeRuntime(RearWallpaperRuntimeCodec.append(old, record)); success("壁纸已导入", record.wallpaperId)
    }}.getOrElse { failure(it.message ?: "导入壁纸失败", "IMPORT_FAILED") }

    private fun rename(id: Int, requestedName: String?): Bundle = runCatching { synchronized(lock) {
        val displayName = requestedName?.trim().orEmpty()
        require(displayName.length in 1..48) { "名称应为 1 到 48 个字符" }
        require(displayName.none { it.isISOControl() }) { "名称包含无效字符" }
        val array = JSONArray(runtimeFile().readText())
        val target = (0 until array.length()).asSequence().mapNotNull(array::optJSONObject)
            .firstOrNull { (it.optString("resId") + it.optString("applyId")).hashCode() == id }
            ?: error("wallpaper not found")
        val record = RearWallpaperRuntimeRecord(
            target.optString("resId"), target.optString("applyId"), target.optString("resLocalPath").ifBlank { null },
            target.optString("metaPath").ifBlank { null }, null, target.optInt("position", -1), displayName,
        )
        require(ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)) { "refusing to rename non-OuterView wallpaper" }
        target.put("resName", localeValue(displayName))
        target.optString("metaPath").takeIf(String::isNotBlank)?.let { path ->
            val file = File(path)
            val metadata = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
            metadata.put("resId", record.resId).put("resName", displayName)
            file.writeText(metadata.toString(2))
        }
        writeRuntime(array.toString(2))
        success("已重命名为 $displayName", id)
    }}.getOrElse { failure(it.message ?: "重命名失败", "RENAME_FAILED") }

    private fun delete(id: Int): Bundle = runCatching { synchronized(lock) {
        val old = runtimeFile().takeIf(File::isFile)?.readText().orEmpty(); val target = RearWallpaperRuntimeCodec.decode(old).firstOrNull { it.wallpaperId == id } ?: error("wallpaper not found")
        // outerviewCurrent is only our last persisted intent.  The user may have
        // selected a system wallpaper outside OuterView since then, so deletion
        // must rely on the host selection observed by this hook instead.
        require(appliedId != id) {
            "请先应用另一张壁纸，再删除当前壁纸"
        }
        require(ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), target)) { "refusing to delete non-OuterView wallpaper" }
        val array = JSONArray(old); val next = JSONArray(); for (i in 0 until array.length()) { if (array.optJSONObject(i)?.let { (it.optString("resId") + it.optString("applyId")).hashCode() } != id) next.put(array.get(i)) }
        writeRuntime(next.toString(2)); File(target.resLocalPath!!).parentFile?.deleteRecursively(); success("壁纸已删除", id)
    }}.getOrElse { failure(it.message ?: "删除壁纸失败", "DELETE_FAILED") }

    private fun runtimeRoot() = File("/data/system/theme_magic/users/${(Process.myUid() / 100000).coerceAtLeast(0)}/rearScreen")
    private fun runtimeFile() = File(runtimeRoot(), "runtime.json")
    private fun writeRuntime(text: String) { val target = runtimeFile(); val temp = File(target.parentFile, "runtime.json.outerview.tmp"); temp.writeText(text); check(temp.renameTo(target)) { "atomic runtime replace failed" } }
    private fun resolveDisplayName(record: RearWallpaperRuntimeRecord): String {
        val runtimeName = record.displayName.orEmpty().trim()
        if (runtimeName.isNotBlank() && runtimeName != record.resId) return runtimeName
        return record.metaPath?.let(::File)?.takeIf(File::isFile)?.let { file ->
            runCatching { JSONObject(file.readText()).optString("resName") }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: record.resId
    }
    private fun friendlyDisplayName(sourceName: String): String = sourceName
        .replace(Regex("(?i)\\.(mrc|zip)$"), "")
        .replace(Regex("(?i)^outerview[_ -]*"), "")
        .replace(Regex("[_-]+"), " ")
        .trim()
        .ifBlank { "OuterView 壁纸" }
        .take(48)
    private fun localeValue(value: String): String = JSONObject()
        .put("fallback", value).put("zh_CN", value).toString()
    private fun selectedManagedWallpaperId(): Int? = runCatching {
        val root = JSONArray(runtimeFile().readText())
        (0 until root.length()).asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull { item ->
            item.optBoolean("outerviewCurrent") && ManagedRearWallpaperPaths.isManagedResource(
                runtimeRoot(),
                RearWallpaperRuntimeRecord(
                    item.optString("resId"), item.optString("applyId"), item.optString("resLocalPath").ifBlank { null },
                    item.optString("metaPath").ifBlank { null }, null, item.optInt("position", -1),
                ),
            )
        }?.let { (it.optString("resId") + it.optString("applyId")).hashCode() }
    }.getOrNull()
    private fun markManagedWallpaperCurrent(id: Int) {
        val old = runtimeFile().readText(); val array = JSONArray(old)
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { item ->
            val itemId = (item.optString("resId") + item.optString("applyId")).hashCode()
            val record = RearWallpaperRuntimeRecord(
                item.optString("resId"), item.optString("applyId"), item.optString("resLocalPath").ifBlank { null },
                item.optString("metaPath").ifBlank { null }, null, item.optInt("position", -1),
            )
            if (ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)) {
                item.put("outerviewCurrent", itemId == id)
            }
        }
        writeRuntime(array.toString())
    }
    private fun clearManagedWallpaperCurrent() {
        val old = runtimeFile().takeIf(File::isFile)?.readText() ?: return
        val array = JSONArray(old); var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optBoolean("outerviewCurrent")) {
                item.remove("outerviewCurrent"); changed = true
            }
        }
        if (changed) writeRuntime(array.toString())
    }
    private fun restoreManagedWallpaperAfterHostSelection() {
        if (restoringManagedWallpaper) return
        val wanted = selectedManagedWallpaperId() ?: return
        if (wanted == appliedId) return
        restoringManagedWallpaper = true
        Thread {
            runCatching { apply(wanted) }.onFailure { YLog.error("[$TAG] managed wallpaper restore failed", it) }
            restoringManagedWallpaper = false
        }.start()
    }
    private fun success(message: String, id: Int) = Bundle().apply { putBoolean(RearWallpaperHostContract.Keys.SUCCESS, true); putString(RearWallpaperHostContract.Keys.MESSAGE, message); putInt(RearWallpaperHostContract.Keys.WALLPAPER_ID, id) }
    private fun failure(message: String, code: String) = Bundle().apply { putBoolean(RearWallpaperHostContract.Keys.SUCCESS, false); putString(RearWallpaperHostContract.Keys.MESSAGE, message); putString(RearWallpaperHostContract.Keys.ERROR_CODE, code) }
    private fun enforceCaller() { val packages = context?.packageManager?.getPackagesForUid(Binder.getCallingUid()).orEmpty(); check(APP in packages) { "unauthorized caller" } }
}
