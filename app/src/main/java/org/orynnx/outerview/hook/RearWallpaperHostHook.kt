package org.orynnx.outerview.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import org.orynnx.outerview.core.internal.ManagedRearWallpaperPaths
import org.orynnx.outerview.core.internal.RearWallpaperPackageValidator
import org.orynnx.outerview.core.internal.RearWallpaperRuntimeCodec
import org.orynnx.outerview.core.internal.RearWallpaperRuntimeRecord
import org.orynnx.outerview.core.internal.RearWallpaperSelectionPolicy
import org.orynnx.outerview.core.wallpaperapi.IRearWallpaperHostConnection
import org.orynnx.outerview.core.wallpaperapi.IRearWallpaperHostService
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostContract
import org.orynnx.outerview.hook.dex.HostDexResolver
import org.orynnx.outerview.hook.dex.HostFieldQuery
import org.orynnx.outerview.hook.dex.HostMethodQuery
import org.orynnx.outerview.hook.dex.HostMethodRef
import org.orynnx.outerview.hook.dex.PUBLIC_STATIC
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns only OuterView-prefixed entries in the rear-screen wallpaper runtime. */
class RearWallpaperHostHook : YukiBaseHooker() {
    companion object {
        private const val TAG = "OuterView-Wallpaper"
        private const val APP = "org.orynnx.outerview"
        private const val MAX_RUNTIME_BYTES = 8L * 1024L * 1024L
        private const val MAX_METADATA_BYTES = 256L * 1024L
        private const val DELETE_TOMBSTONE_PREFIX = ".outerview_wallpaper_delete_"
        private const val IMPORT_STAGING_PREFIX = ".outerview_wallpaper_import_"
        private const val ATOMIC_WRITE_TEMP_PREFIX = ".outerview_write_"
    }
    private val providerInstanceId = UUID.randomUUID().toString()
    private var context: Context? = null
    private var receiverRegistered = false
    private val lock = Any()
    private var hostDex: HostDexResolver? = null
    private var mainPanel: Any? = null
    private var mainHandler: Handler? = null
    // Last wallpaper the user actually selected, from OuterView or the system panel.
    // Cleared when a non-managed wallpaper is picked so we never treat an absent id
    // as "one of ours is still current".
    @Volatile private var appliedId: Int? = null
    @Volatile private var persistedSelectionHintId: Int? = null
    @Volatile private var selectionKnown = false
    @Volatile private var pendingApplyId: Int? = null
    private val createdManagedWidgets = IdentityHashMap<Any, Int>()

    override fun onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode = hostPackageVersionCode(systemContext, appInfo.packageName, appInfo.sourceDir)
            hostDex = HostDexResolver.open(appInfo.sourceDir, appInfo.dataDir, versionCode)
            "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve().firstMethod {
                name = "attachBaseContext"; parameterCount = 1
            }.hook().after {
                Handler(Looper.getMainLooper()).post {
                    context = ((args[0] as? Context) ?: (instance as? Context) ?: currentApplication())?.applicationContext
                    runCatching { synchronized(lock) { recoverWallpaperTransactions() } }
                        .onFailure { YLog.error("[$TAG] failed to recover wallpaper transactions", it) }
                    runCatching { restorePersistedSelectionHint() }
                        .onFailure { YLog.error("[$TAG] failed to restore current wallpaper marker", it) }
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
                    // Invalidate the previous observation before the host starts
                    // switching. Binder deletes must fail closed during this window.
                    selectionKnown = false
                    val incoming = args.getOrNull(0) as? List<Any> ?: return@before
                    val augmented = augmentWithManagedWidgets(incoming)
                    if (augmented.size != incoming.size) {
                        args[0] = augmented
                        YLog.info("[$TAG] system list augmented ${incoming.size}->${augmented.size}")
                    }
                }
                point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 2 }.hook().after {
                    refreshAuthoritativeSelection("select")
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
        host.registerReceiver(
            receiver,
            IntentFilter(RearWallpaperHostContract.ACTION_REQUEST_SERVICE),
            RearWallpaperHostContract.ACCESS_HOST_API_PERMISSION,
            null,
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        YLog.info("[$TAG] receiver registered")
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private val service = object : IRearWallpaperHostService.Stub() {
        override fun getCapabilities(): Bundle { enforceCaller(); return Bundle().apply { putInt(RearWallpaperHostContract.Keys.API_VERSION, RearWallpaperHostContract.API_VERSION); putString(RearWallpaperHostContract.Keys.PROVIDER_PACKAGE, RearWallpaperHostContract.PROVIDER_PACKAGE); putString(RearWallpaperHostContract.Keys.PROVIDER_INSTANCE_ID, providerInstanceId); putBoolean(RearWallpaperHostContract.Keys.HOOK_READY, context != null); putBoolean(RearWallpaperHostContract.Keys.PANEL_READY, mainPanel != null) } }
        override fun listWallpapers(): Bundle { enforceCaller(); return catalog() }
        override fun importWallpaper(packageFd: ParcelFileDescriptor?, displayName: String?): Bundle { enforceCaller(); return import(packageFd, displayName) }
        override fun applyWallpaper(wallpaperId: Int): Bundle { enforceCaller(); return apply(wallpaperId) }
        override fun renameWallpaper(wallpaperId: Int, displayName: String?): Bundle { enforceCaller(); return rename(wallpaperId, displayName) }
        override fun deleteWallpaper(wallpaperId: Int): Bundle { enforceCaller(); return delete(wallpaperId) }
    }

    private fun catalog(): Bundle = synchronized(lock) {
        recoverWallpaperTransactions()
        val raw = readRuntime()
        val records = globallyUniqueManagedRecords(raw)
        val currentId = appliedId.takeIf { selectionKnown }
        Bundle().apply { putParcelableArrayList(RearWallpaperHostContract.Keys.ITEMS, ArrayList(records.map { record -> Bundle().apply {
            putInt(RearWallpaperHostContract.Keys.WALLPAPER_ID, record.wallpaperId); putString(RearWallpaperHostContract.Keys.RES_ID, record.resId)
            putString(RearWallpaperHostContract.Keys.NAME, resolveDisplayName(record)); putString(RearWallpaperHostContract.Keys.PATH, record.resLocalPath)
            putBoolean(RearWallpaperHostContract.Keys.MANAGED, ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)); putBoolean(RearWallpaperHostContract.Keys.CURRENT, record.wallpaperId == currentId)
        }})) }
    }

    private fun apply(id: Int): Bundle = runCatching {
        synchronized(lock) {
            recoverWallpaperTransactions()
            uniqueManagedRecord(id)
            check(pendingApplyId == null) { "已有壁纸切换正在进行" }
            pendingApplyId = id
            selectionKnown = false
        }
        try {
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
            val observation = refreshAuthoritativeSelection("apply-save", allowPendingApply = true)
            require(observation.known && observation.managedCurrentId == id) {
                "宿主未确认目标壁纸为当前项"
            }
            YLog.info("[$TAG] apply dispatched id=$id index=$index")
        }.onFailure { failure.set(it); YLog.error("[$TAG] MainPanel apply failed", it) }.also { completed.countDown() }
        }
        check((mainHandler ?: Handler(context!!.mainLooper)).post(task)) { "failed to post MainPanel action" }
        check(completed.await(5, TimeUnit.SECONDS)) { "MainPanel apply timed out" }
        failure.get()?.let { throw it }
        success("已请求背屏应用壁纸", id)
        } finally {
            synchronized(lock) {
                if (pendingApplyId == id) pendingApplyId = null
            }
        }
    }.getOrElse { YLog.error("[$TAG] apply failed", it); failure(it.message ?: "应用壁纸失败", "APPLY_FAILED") }

    private fun capturePanel(launcher: Any?) {
        val targetClass = runCatching { selectPoint().className }.getOrNull() ?: return
        launcher?.javaClass?.declaredFields?.forEach { field -> runCatching {
            field.isAccessible = true
            val value = field.get(launcher)
            if (value != null && value.javaClass.name == targetClass) mainPanel = value
            if (value is Handler && value.looper == Looper.getMainLooper()) mainHandler = value
        } }
        injectManagedWidgetsIntoPanel()
        refreshAuthoritativeSelection("panel-capture")
    }

    private fun refreshAuthoritativeSelection(
        source: String,
        allowPendingApply: Boolean = false,
    ): RearWallpaperSelectionPolicy.Observation = runCatching {
        val panel = mainPanel ?: error("背屏 MainPanel 尚未就绪")
        val widgets = readPanelField(panel, selectedWidgetListFieldName()) as? List<*>
            ?: error("无法读取背屏当前 widget 列表")
        require(widgets.all { it != null }) { "背屏当前 widget 列表包含空项" }
        val selectedIndex = readPanelField(panel, selectedIndexFieldName()) as? Int
            ?: error("无法读取背屏当前索引")
        val selectedWidget = widgets.getOrNull(selectedIndex)
            ?: error("背屏当前索引越界")
        val runtime = RearWallpaperRuntimeCodec.decode(readRuntime())
        val managedIds = runtime.groupBy(RearWallpaperRuntimeRecord::wallpaperId)
            .values
            .mapNotNull { matches -> matches.singleOrNull() }
            .filter { ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), it) }
            .mapTo(HashSet(), RearWallpaperRuntimeRecord::wallpaperId)
        val createdManagedId = synchronized(createdManagedWidgets) {
            createdManagedWidgets[selectedWidget]
        }
        val observation = RearWallpaperSelectionPolicy.observe(
            reflectedIds = intFields(selectedWidget),
            runtimeIds = runtime.map(RearWallpaperRuntimeRecord::wallpaperId),
            managedIds = managedIds,
            createdManagedId = createdManagedId,
        )
        require(observation.known) { "背屏当前 widget 身份无法唯一确认" }
        if (pendingApplyId != null && !allowPendingApply) {
            selectionKnown = false
            YLog.info("[$TAG] authoritative selection deferred during apply source=$source index=$selectedIndex")
            return@runCatching observation
        }
        val managedCurrentId = observation.managedCurrentId
        appliedId = managedCurrentId
        persistedSelectionHintId = managedCurrentId
        selectionKnown = true
        if (managedCurrentId != null) {
            markManagedWallpaperCurrent(managedCurrentId)
        } else {
            clearManagedWallpaperCurrent()
        }
        YLog.info(
            "[$TAG] authoritative selection source=$source index=$selectedIndex " +
                "managedWallpaperId=${managedCurrentId ?: "none"}",
        )
        observation
    }.onFailure { error ->
        selectionKnown = false
        YLog.warn("[$TAG] authoritative selection unavailable source=$source", error)
    }.getOrDefault(RearWallpaperSelectionPolicy.Observation(false, null))

    private fun readPanelField(panel: Any, name: String): Any? {
        val field = generateSequence(panel.javaClass) { it.superclass }
            .mapNotNull { clazz -> clazz.declaredFields.singleOrNull { it.name == name } }
            .firstOrNull() ?: error("背屏字段不存在：$name")
        field.isAccessible = true
        return field.get(panel)
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
        val managedIds = globallyUniqueManagedRecords(
            readRuntime(),
        ).mapTo(HashSet()) { it.wallpaperId }
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

    private fun augmentWithManagedWidgets(base: List<Any>): List<Any> = runCatching {
        val managedIds = globallyUniqueManagedRecords(
            readRuntime(),
        ).mapTo(HashSet()) { it.wallpaperId }
        if (managedIds.isEmpty()) return base
        val present = base.flatMapTo(HashSet()) { intFields(it) }
        val additions = loadRuntimeSpecs().mapNotNull { spec ->
            val id = intFields(spec).firstOrNull { it in managedIds && it !in present } ?: return@mapNotNull null
            createWidget(spec, id)?.also { present += id }
        }
        if (additions.isEmpty()) base else base + additions
    }.onFailure { YLog.error("[$TAG] failed to augment managed wallpapers", it) }
        .getOrDefault(base)

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
    }.onSuccess { result ->
        if (result == null) {
            YLog.warn("[$TAG] widget factory returned null spec=${spec.javaClass.name} id=$specId")
        } else if (specId != null) {
            synchronized(createdManagedWidgets) { createdManagedWidgets[result] = specId }
        }
    }
        .onFailure { YLog.error("[$TAG] widget factory failed spec=${spec.javaClass.name}", it) }.getOrNull()
    private fun intFields(target: Any): List<Int> = generateSequence(target.javaClass) { it.superclass }.flatMap { clazz -> clazz.declaredFields.asSequence().mapNotNull { field -> runCatching { field.isAccessible = true; (field.get(target) as? Int) }.getOrNull() } }.toList()
    private fun resolvePoint(key: String, query: HostMethodQuery): HostMethodRef =
        hostDex?.method(key, query) ?: error("Host DEX lookup failed: $key")

    private fun resolveFieldName(key: String, query: HostFieldQuery): String =
        hostDex?.fieldName(key, query) ?: error("Host DEX field lookup failed: $key")

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

    private fun selectedIndexFieldName(): String {
        val save = saveSelectionPoint()
        return resolveFieldName(
            "OV_MAIN_PANEL_SELECTED_INDEX",
            HostFieldQuery(owner = save.className, type = "int", readBy = save),
        )
    }

    private fun selectedWidgetListFieldName(): String {
        val select = selectPoint()
        return listOf("java.util.List", "java.util.ArrayList")
            .firstNotNullOfOrNull { fieldType ->
                runCatching {
                    resolveFieldName(
                        "OV_MAIN_PANEL_WIDGET_LIST_${fieldType.substringAfterLast('.')}",
                        HostFieldQuery(owner = select.className, type = fieldType, readBy = select),
                    )
                }.getOrNull()
            }
            ?: error("无法定位背屏当前 widget 列表字段")
    }

    private fun import(fd: ParcelFileDescriptor?, name: String?): Bundle {
        val descriptor = fd ?: return failure("empty wallpaper file", "IMPORT_FAILED")
        return try {
            runCatching {
                val sourceStat = Os.fstat(descriptor.fileDescriptor)
                require(OsConstants.S_ISREG(sourceStat.st_mode)) {
                    "wallpaper source must be a regular file"
                }
                require(sourceStat.st_size in 1L..RearWallpaperPackageValidator.MaxCompressedBytes) {
                    "wallpaper package must be between 1 byte and 32 MB"
                }
                synchronized(lock) {
                    recoverWallpaperTransactions()
                    val sourceName = name?.takeIf(String::isNotBlank) ?: "wallpaper.mrc"
                    require(sourceName.endsWith(".mrc", true) || sourceName.endsWith(".zip", true)) {
                        "only MRC/ZIP wallpaper packages are supported"
                    }
                    val displayName = friendlyDisplayName(sourceName)
                    val old = readRuntime()
                    val existingIds = RearWallpaperRuntimeCodec.decode(old).mapTo(HashSet()) { it.wallpaperId }
                    val applyId = System.currentTimeMillis().toString()
                    val resId = generateSequence {
                        ManagedRearWallpaperPaths.ResourcePrefix + UUID.randomUUID().toString().replace('-', '_')
                    }.first { candidate -> (candidate + applyId).hashCode() !in existingIds }
                    val root = runtimeRoot()
                    check(root.isDirectory || root.mkdirs()) { "failed to create wallpaper runtime root" }
                    val directory = ManagedRearWallpaperPaths.resourceDirectory(root, resId, applyId)
                    val stagingDirectory = File(root, "$IMPORT_STAGING_PREFIX${UUID.randomUUID()}").absoluteFile
                    require(stagingDirectory.parentFile == root.absoluteFile) {
                        "wallpaper staging directory escaped runtime root"
                    }
                    check(!stagingDirectory.exists() && stagingDirectory.mkdir()) {
                        "failed to create wallpaper staging directory"
                    }
                    try {
                        val stagedPackage = File(stagingDirectory, ManagedRearWallpaperPaths.PackageFileName)
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            FileOutputStream(stagedPackage).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var copied = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    copied += read
                                    require(copied <= RearWallpaperPackageValidator.MaxCompressedBytes) {
                                        "wallpaper package exceeds 32 MB"
                                    }
                                    output.write(buffer, 0, read)
                                }
                                output.fd.sync()
                            }
                        }
                        RearWallpaperPackageValidator.inspect(stagedPackage)
                        writeFileAtomically(
                            File(stagingDirectory, ManagedRearWallpaperPaths.MetadataFileName),
                            JSONObject().put("resId", resId).put("resName", displayName)
                                .toString().toByteArray(Charsets.UTF_8),
                            MAX_METADATA_BYTES,
                            "wallpaper metadata",
                        )
                        val record = RearWallpaperRuntimeRecord(
                            resId,
                            applyId,
                            File(directory, ManagedRearWallpaperPaths.PackageFileName).path,
                            File(directory, ManagedRearWallpaperPaths.MetadataFileName).path,
                            null,
                            RearWallpaperRuntimeCodec.decode(old).maxOfOrNull { it.position }?.plus(1) ?: 0,
                            displayName,
                        )
                        check(!directory.exists() && stagingDirectory.renameTo(directory)) {
                            "failed to publish wallpaper directory"
                        }
                        writeRuntime(RearWallpaperRuntimeCodec.append(old, record))
                        success("壁纸已导入", record.wallpaperId)
                    } catch (error: Throwable) {
                        runCatching {
                            if (directory.exists()) discardWallpaperDirectory(directory)
                            if (stagingDirectory.exists()) safeDeleteFlatDirectory(stagingDirectory)
                        }.onFailure(error::addSuppressed)
                        throw error
                    }
                }
            }.getOrElse { failure(it.message ?: "导入壁纸失败", "IMPORT_FAILED") }
        } finally {
            runCatching { descriptor.close() }
                .onFailure { YLog.warn("[$TAG] failed to close wallpaper import descriptor", it) }
        }
    }

    private fun rename(id: Int, requestedName: String?): Bundle = runCatching { synchronized(lock) {
        val displayName = requestedName?.trim().orEmpty()
        require(displayName.codePointCount(0, displayName.length) in 1..48) {
            "名称应为 1 到 48 个字符"
        }
        require(displayName.none(::isUnsafeDisplayCharacter)) { "名称包含无效字符" }
        recoverWallpaperTransactions()
        val old = readRuntime()
        val record = uniqueManagedRecord(id, old)
        val ownedDirectory = requireNotNull(
            ManagedRearWallpaperPaths.managedResourceDirectory(runtimeRoot(), record),
        ) { "refusing to rename non-OuterView wallpaper" }
        val metadataFile = File(ownedDirectory, ManagedRearWallpaperPaths.MetadataFileName)
        require(!Files.isSymbolicLink(metadataFile.toPath())) { "wallpaper metadata must not be a symbolic link" }
        require(metadataFile.canonicalFile.parentFile == ownedDirectory.canonicalFile) {
            "wallpaper metadata escaped its managed directory"
        }
        val previousMetadata = metadataFile.takeIf(File::exists)?.let { file ->
            readBytesBounded(file, MAX_METADATA_BYTES, "wallpaper metadata", allowEmpty = true)
        }
        val array = JSONArray(old)
        val target = (0 until array.length()).asSequence().mapNotNull(array::optJSONObject)
            .firstOrNull { it.optString("resId") == record.resId && it.optString("applyId") == record.applyId }
            ?: error("wallpaper not found")
        target.put("resName", localeValue(displayName))
        val metadata = previousMetadata?.let { bytes ->
            runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        } ?: JSONObject()
        val nextMetadata = metadata.put("resId", record.resId).put("resName", displayName)
            .toString(2).toByteArray(Charsets.UTF_8)
        writeFileAtomically(metadataFile, nextMetadata, MAX_METADATA_BYTES, "wallpaper metadata")
        try {
            writeRuntime(array.toString(2))
        } catch (error: Throwable) {
            runCatching {
                if (previousMetadata == null) {
                    check(!metadataFile.exists() || metadataFile.delete()) { "failed to roll back wallpaper metadata" }
                } else {
                    writeFileAtomically(
                        metadataFile,
                        previousMetadata,
                        MAX_METADATA_BYTES,
                        "wallpaper metadata rollback",
                    )
                }
            }.onFailure(error::addSuppressed)
            throw error
        }
        success("已重命名为 $displayName", id)
    }}.getOrElse { failure(it.message ?: "重命名失败", "RENAME_FAILED") }

    private fun delete(id: Int): Bundle = runCatching { synchronized(lock) {
        recoverWallpaperTransactions()
        val old = readRuntime()
        val target = uniqueManagedRecord(id, old)
        // The observer clears this durable marker whenever the user selects a
        // system wallpaper.  Until that observer runs after a host restart, the
        // last persisted selection must still block deletion of its live files.
        require(selectionKnown) { "当前壁纸状态尚未确认，请先打开背屏设置或应用另一张壁纸" }
        require(pendingApplyId != id) { "这张壁纸正在切换中，请稍后再删除" }
        require(RearWallpaperSelectionPolicy.canDelete(id, selectionKnown, appliedId, pendingApplyId)) {
            "请先应用另一张壁纸，再删除当前壁纸"
        }
        val ownedDirectory = ManagedRearWallpaperPaths.managedResourceDirectory(runtimeRoot(), target)
        require(ownedDirectory != null && ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), target)) { "refusing to delete non-OuterView wallpaper" }
        val array = JSONArray(old); val next = JSONArray(); for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item == null || item.optString("resId") != target.resId || item.optString("applyId") != target.applyId) {
                next.put(array.get(i))
            }
        }
        val tombstone = ownedDirectory.takeIf(File::exists)?.let(::moveWallpaperDirectoryToTombstone)
        try {
            writeRuntime(next.toString(2))
        } catch (error: Throwable) {
            if (tombstone != null) {
                runCatching {
                    check(tombstone.renameTo(ownedDirectory)) { "failed to restore wallpaper files" }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
        tombstone?.let { stale ->
            runCatching { safeDeleteFlatDirectory(stale) }
                .onFailure { YLog.warn("[$TAG] wallpaper tombstone cleanup deferred: ${stale.name}", it) }
        }
        success("壁纸已删除", id)
    }}.getOrElse { failure(it.message ?: "删除壁纸失败", "DELETE_FAILED") }

    private fun runtimeRoot(): File = File(
        "/data/system/theme_magic/users/${(Process.myUid() / 100000).coerceAtLeast(0)}/rearScreen",
    ).absoluteFile.also { root ->
        require(!Files.isSymbolicLink(root.toPath())) { "wallpaper runtime root must not be a symbolic link" }
    }
    private fun runtimeFile(): File {
        val root = runtimeRoot()
        val file = File(root, "runtime.json").absoluteFile
        require(file.parentFile == root) { "wallpaper runtime path is invalid" }
        require(!Files.isSymbolicLink(file.toPath())) { "wallpaper runtime must not be a symbolic link" }
        return file
    }
    private fun readRuntime(): String = runtimeFile().takeIf(File::isFile)?.let { file ->
        readTextBounded(file, MAX_RUNTIME_BYTES, "wallpaper runtime")
    }.orEmpty()
    private fun readTextBounded(file: File, maxBytes: Long, label: String): String {
        return readBytesBounded(file, maxBytes, label, allowEmpty = false).toString(Charsets.UTF_8)
    }
    private fun readBytesBounded(
        file: File,
        maxBytes: Long,
        label: String,
        allowEmpty: Boolean,
    ): ByteArray {
        val validRange = if (allowEmpty) 0L..maxBytes else 1L..maxBytes
        require(file.isFile && file.length() in validRange) { "$label file size is invalid" }
        val output = ByteArrayOutputStream(file.length().toInt())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) { "$label file is too large" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
    private fun writeRuntime(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeFileAtomically(runtimeFile(), bytes, MAX_RUNTIME_BYTES, "wallpaper runtime")
    }
    private fun writeFileAtomically(target: File, bytes: ByteArray, maxBytes: Long, label: String) {
        require(bytes.size.toLong() <= maxBytes) { "$label file is too large" }
        val parent = requireNotNull(target.absoluteFile.parentFile) { "$label parent is missing" }
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "$label parent is invalid" }
        require(!Files.isSymbolicLink(target.toPath())) { "$label must not be a symbolic link" }
        require(target.canonicalFile.parentFile == parent.canonicalFile) { "$label escaped its managed directory" }
        val temp = File.createTempFile(ATOMIC_WRITE_TEMP_PREFIX, ".tmp", parent)
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp.toPath()) }.onFailure(error::addSuppressed)
            throw error
        }
        // The atomic move is the commit point. A post-commit cleanup problem
        // must never be reported as a failed write (callers could then roll
        // back files that the committed runtime now references).
        runCatching { Files.deleteIfExists(temp.toPath()) }
            .onFailure { YLog.warn("[$TAG] committed $label temp cleanup deferred: ${temp.name}", it) }
    }
    private fun moveWallpaperDirectoryToTombstone(directory: File): File {
        val root = runtimeRoot().canonicalFile
        require(directory.name.startsWith(ManagedRearWallpaperPaths.ResourcePrefix)) {
            "wallpaper directory is not managed by OuterView"
        }
        require(!Files.isSymbolicLink(directory.toPath())) { "wallpaper directory must not be a symbolic link" }
        require(directory.canonicalFile.parentFile == root) { "wallpaper directory escaped runtime root" }
        // Encoding the exact original basename makes a process-death recovery
        // deterministic: runtime still references it => restore; otherwise delete.
        val tombstone = File(root, "$DELETE_TOMBSTONE_PREFIX${directory.name}")
        check(!tombstone.exists() && directory.renameTo(tombstone)) { "failed to isolate wallpaper files" }
        return tombstone
    }
    private fun discardWallpaperDirectory(directory: File) {
        if (!directory.exists()) return
        val tombstone = moveWallpaperDirectoryToTombstone(directory)
        runCatching { safeDeleteFlatDirectory(tombstone) }
            .onFailure { YLog.warn("[$TAG] wallpaper import tombstone cleanup deferred: ${tombstone.name}", it) }
    }
    private fun safeDeleteFlatDirectory(directory: File) {
        val root = runtimeRoot().canonicalFile
        require(
            directory.name.startsWith(ManagedRearWallpaperPaths.ResourcePrefix) ||
                directory.name.startsWith(DELETE_TOMBSTONE_PREFIX) ||
                directory.name.startsWith(IMPORT_STAGING_PREFIX),
        ) { "refusing to delete an unowned wallpaper directory" }
        require(!Files.isSymbolicLink(directory.toPath())) { "wallpaper directory must not be a symbolic link" }
        require(directory.canonicalFile.parentFile == root) { "wallpaper directory escaped runtime root" }
        if (!directory.exists()) return
        require(directory.isDirectory) { "wallpaper resource path is not a directory" }
        val children = requireNotNull(directory.listFiles()) { "failed to list wallpaper resource directory" }
        children.forEach { child ->
            require(child.absoluteFile.parentFile == directory.absoluteFile) { "wallpaper child escaped its directory" }
            if (Files.isSymbolicLink(child.toPath())) {
                Files.deleteIfExists(child.toPath())
            } else {
                require(child.isFile) { "refusing recursive wallpaper directory deletion" }
                check(child.delete()) { "failed to delete wallpaper resource file" }
            }
        }
        check(directory.delete()) { "failed to delete wallpaper resource directory" }
    }
    private fun recoverWallpaperTransactions() {
        val root = runtimeRoot()
        if (!root.isDirectory) return
        require(!Files.isSymbolicLink(root.toPath())) { "wallpaper runtime root must not be a symbolic link" }
        val raw = readRuntime()
        val decoded = RearWallpaperRuntimeCodec.decode(raw)
        // Any structurally valid runtime key reserves its expected directory,
        // even if other path fields are corrupt. Recovery must fail closed
        // rather than misclassify that directory as an orphan and delete it.
        val reserved = decoded.mapNotNull { record ->
            val directory = ManagedRearWallpaperPaths.managedResourceDirectory(root, record)
                ?: return@mapNotNull null
            directory.name to record
        }.toMap()
        val referenced = reserved.filterValues { record ->
            ManagedRearWallpaperPaths.isManagedResource(root, record)
        }

        root.listFiles().orEmpty()
            .filter { it.absoluteFile.parentFile == root.absoluteFile && it.name.startsWith(DELETE_TOMBSTONE_PREFIX) }
            .forEach { tombstone ->
                val originalName = tombstone.name.removePrefix(DELETE_TOMBSTONE_PREFIX)
                if (!originalName.startsWith(ManagedRearWallpaperPaths.ResourcePrefix)) {
                    // Old random tombstones have no durable identity. Quarantine
                    // instead of guessing and possibly deleting a pre-commit record.
                    YLog.warn("[$TAG] legacy wallpaper tombstone quarantined: ${tombstone.name}")
                    return@forEach
                }
                val record = reserved[originalName]
                if (record == null) {
                    if (Files.isSymbolicLink(tombstone.toPath())) {
                        Files.deleteIfExists(tombstone.toPath())
                    } else {
                        safeDeleteFlatDirectory(tombstone)
                    }
                    return@forEach
                }
                val original = requireNotNull(
                    ManagedRearWallpaperPaths.managedResourceDirectory(root, record),
                ) { "invalid wallpaper recovery target" }
                require(original.name == originalName) { "wallpaper tombstone target mismatch" }
                require(!Files.isSymbolicLink(tombstone.toPath())) {
                    "wallpaper tombstone must not be a symbolic link"
                }
                require(!Files.isSymbolicLink(original.toPath())) {
                    "wallpaper recovery target must not be a symbolic link"
                }
                if (original.exists()) {
                    safeDeleteFlatDirectory(tombstone)
                } else {
                    check(tombstone.renameTo(original)) { "failed to restore interrupted wallpaper delete" }
                }
            }

        root.listFiles().orEmpty()
            .filter { it.absoluteFile.parentFile == root.absoluteFile && it.name.startsWith(IMPORT_STAGING_PREFIX) }
            .forEach { staging ->
                if (Files.isSymbolicLink(staging.toPath())) {
                    Files.deleteIfExists(staging.toPath())
                } else {
                    safeDeleteFlatDirectory(staging)
                }
            }

        cleanupAtomicWriteTemps(root)

        root.listFiles().orEmpty()
            .filter {
                    it.absoluteFile.parentFile == root.absoluteFile &&
                    it.name.startsWith(ManagedRearWallpaperPaths.ResourcePrefix) &&
                    it.name !in reserved
            }
            .forEach { orphan ->
                if (Files.isSymbolicLink(orphan.toPath())) {
                    Files.deleteIfExists(orphan.toPath())
                } else {
                    safeDeleteFlatDirectory(orphan)
                }
            }

        referenced.values.forEach { record -> repairWallpaperMetadataFromRuntime(root, record) }
    }

    private fun repairWallpaperMetadataFromRuntime(
        root: File,
        record: RearWallpaperRuntimeRecord,
    ) {
        val directory = requireNotNull(
            ManagedRearWallpaperPaths.managedResourceDirectory(root, record),
        ) { "invalid managed wallpaper directory" }
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "managed wallpaper directory is missing"
        }
        cleanupAtomicWriteTemps(directory)
        val displayName = record.displayName?.trim()?.takeIf(String::isNotBlank) ?: return
        val packageFile = File(directory, ManagedRearWallpaperPaths.PackageFileName)
        require(packageFile.isFile && !Files.isSymbolicLink(packageFile.toPath())) {
            "managed wallpaper package is missing"
        }
        val metadataFile = File(directory, ManagedRearWallpaperPaths.MetadataFileName)
        require(!Files.isSymbolicLink(metadataFile.toPath())) {
            "wallpaper metadata must not be a symbolic link"
        }
        val metadata = metadataFile.takeIf(File::isFile)?.let { file ->
            runCatching { JSONObject(readTextBounded(file, MAX_METADATA_BYTES, "wallpaper metadata")) }
                .getOrNull()
        } ?: JSONObject()
        if (metadata.optString("resId") == record.resId && metadata.optString("resName") == displayName) return
        writeFileAtomically(
            metadataFile,
            metadata.put("resId", record.resId).put("resName", displayName)
                .toString(2).toByteArray(Charsets.UTF_8),
            MAX_METADATA_BYTES,
            "wallpaper metadata recovery",
        )
    }

    private fun cleanupAtomicWriteTemps(parent: File) {
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) {
            "atomic-write parent is invalid"
        }
        parent.listFiles().orEmpty()
            .filter {
                it.absoluteFile.parentFile == parent.absoluteFile &&
                    it.name.startsWith(ATOMIC_WRITE_TEMP_PREFIX)
            }
            .forEach { temp ->
                require(Files.isSymbolicLink(temp.toPath()) || temp.isFile) {
                    "refusing recursive atomic-write temp cleanup"
                }
                Files.deleteIfExists(temp.toPath())
            }
    }
    private fun resolveDisplayName(record: RearWallpaperRuntimeRecord): String {
        val runtimeName = record.displayName.orEmpty().trim()
        if (runtimeName.isNotBlank() && runtimeName != record.resId) return runtimeName
        return record.metaPath?.let(::File)?.takeIf(File::isFile)?.let { file ->
            runCatching {
                JSONObject(readTextBounded(file, MAX_METADATA_BYTES, "wallpaper metadata"))
                    .optString("resName")
            }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: record.resId
    }
    private fun friendlyDisplayName(sourceName: String): String = sourceName
        // Bound by Unicode code points so an emoji/supplementary character is
        // never split in half before the name is persisted to runtime.json.
        .takeCodePoints(256)
        // The document provider controls this filename.  Apply the same
        // control/bidi policy used by the rename flow before showing it in UI.
        .filterNot(::isUnsafeDisplayCharacter)
        .replace(Regex("(?i)\\.(mrc|zip)$"), "")
        .replace(Regex("(?i)^outerview[_ -]*"), "")
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "OuterView 壁纸" }
        .takeCodePoints(48)
    private fun String.takeCodePoints(maxCodePoints: Int): String {
        val count = codePointCount(0, length).coerceAtMost(maxCodePoints)
        return substring(0, offsetByCodePoints(0, count))
    }
    private fun isUnsafeDisplayCharacter(character: Char): Boolean =
        character.isISOControl() || character in '\u202a'..'\u202e' ||
            character in '\u2066'..'\u2069' || character == '\u200e' ||
            character == '\u200f' || character == '\u061c'
    private fun localeValue(value: String): String = JSONObject()
        .put("fallback", value).put("zh_CN", value).toString()
    private fun markManagedWallpaperCurrent(id: Int) = synchronized(lock) {
        val old = readRuntime()
        val target = uniqueManagedRecord(id, old)
        val array = JSONArray(old)
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { item ->
            val record = RearWallpaperRuntimeRecord(
                item.optString("resId"), item.optString("applyId"), item.optString("resLocalPath").ifBlank { null },
                item.optString("metaPath").ifBlank { null }, null, item.optInt("position", -1),
            )
            if (ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)) {
                item.put("outerviewCurrent", record.resId == target.resId && record.applyId == target.applyId)
            }
        }
        writeRuntime(array.toString())
    }
    private fun clearManagedWallpaperCurrent(): Unit = synchronized(lock) {
        val old = readRuntime().takeIf(String::isNotBlank) ?: return
        val array = JSONArray(old); var changed = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val record = RearWallpaperRuntimeRecord(
                item.optString("resId"),
                item.optString("applyId"),
                item.optString("resLocalPath").ifBlank { null },
                item.optString("metaPath").ifBlank { null },
                null,
                item.optInt("position", -1),
            )
            if (
                item.optBoolean("outerviewCurrent") &&
                ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)
            ) {
                item.remove("outerviewCurrent"); changed = true
            }
        }
        if (changed) writeRuntime(array.toString())
    }
    private fun restorePersistedSelectionHint() = synchronized(lock) {
        if (selectionKnown || pendingApplyId != null) return@synchronized
        val restored = persistedManagedCurrentIds(readRuntime())
        persistedSelectionHintId = restored.singleOrNull()
        // A durable marker is only a conservative hint. The host may have
        // committed a newer selection immediately before its process stopped.
        if (restored.size > 1) {
            YLog.warn("[$TAG] multiple managed wallpapers are marked current; deletion stays blocked")
        } else {
            YLog.info("[$TAG] restored managed wallpaper hint=${persistedSelectionHintId ?: "none"}")
        }
    }
    private fun persistedManagedCurrentIds(raw: String): Set<Int> {
        val globallyUnique = globallyUniqueManagedRecords(raw).associateBy {
            it.resId to it.applyId
        }
        return RearWallpaperRuntimeCodec.decodeMarkedCurrent(raw).mapNotNullTo(linkedSetOf()) { marked ->
            globallyUnique[marked.resId to marked.applyId]?.wallpaperId
        }
    }
    private fun uniqueManagedRecord(id: Int, raw: String = readRuntime()): RearWallpaperRuntimeRecord {
        val matches = RearWallpaperRuntimeCodec.decode(
            raw,
        ).filter { it.wallpaperId == id }
        require(matches.size == 1) {
            if (matches.isEmpty()) "wallpaper not found" else "wallpaper ID collision; refusing ambiguous operation"
        }
        return matches.single().also { record ->
            require(ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), record)) {
                "refusing to modify non-OuterView wallpaper"
            }
        }
    }
    private fun globallyUniqueManagedRecords(raw: String): List<RearWallpaperRuntimeRecord> =
        RearWallpaperRuntimeCodec.decode(raw)
            .groupBy(RearWallpaperRuntimeRecord::wallpaperId)
            .values
            .mapNotNull { matches -> matches.singleOrNull() }
            .filter { ManagedRearWallpaperPaths.isManagedResource(runtimeRoot(), it) }
    private fun success(message: String, id: Int) = Bundle().apply { putBoolean(RearWallpaperHostContract.Keys.SUCCESS, true); putString(RearWallpaperHostContract.Keys.MESSAGE, message); putInt(RearWallpaperHostContract.Keys.WALLPAPER_ID, id) }
    private fun failure(message: String, code: String) = Bundle().apply { putBoolean(RearWallpaperHostContract.Keys.SUCCESS, false); putString(RearWallpaperHostContract.Keys.MESSAGE, message); putString(RearWallpaperHostContract.Keys.ERROR_CODE, code) }
    private fun enforceCaller() { val packages = context?.packageManager?.getPackagesForUid(Binder.getCallingUid()).orEmpty(); check(APP in packages) { "unauthorized caller" } }
}
