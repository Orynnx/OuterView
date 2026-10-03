package org.orynnx.outerview.hook

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import org.orynnx.outerview.core.ai.AiPackageParser
import org.orynnx.outerview.core.ai.AiRegistryPolicy
import org.orynnx.outerview.core.ai.AiRegistryRecord
import org.orynnx.outerview.core.internal.BoundedDeadlineCopy
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs in the theme process, using its Room repository and native resource transaction. */
internal class ThemeAiBridge(private val context: Context, private val hostLoader: ClassLoader) {
    companion object {
        private const val BASE = "com.rearScreen.aiapp."
        private const val OWNER = "org.orynnx.outerview"
        private const val PREFIX = "outerview_ai_"
        private val OWNED_ID = Regex("outerview_ai_[0-9a-f]{32}")
        private val SAFE_RESOURCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}")
        private const val MAX_RUNTIME_JSON_BYTES = 4L * 1024 * 1024
        private const val INTERNAL_PATH_KEY = "outerview.runtimePath"
        private val STRING_FIELDS = listOf("appName", "mtzPath", "bindApp", "appIconPath", "previewLightPath", "previewDarkPath")
        private val BOOLEAN_FIELDS = listOf("isGame", "isPreset")
    }

    private val continuationClass by lazy { hostClass("kotlin.coroutines.Continuation") }
    private val paramsClass by lazy { hostClass(BASE + "manager.ApplyParams") }
    private val managerClass by lazy { hostClass(BASE + "manager.RearScreenAiAppResourceApplyManager") }
    private val manager by lazy { singleton(managerClass) }
    private val repositoryClass by lazy { hostClass(BASE + "repository.AiAppAppliedRepository") }
    private val repository by lazy { repositoryClass.getDeclaredConstructor().newInstance() }
    private val config by lazy { singleton(hostClass(BASE + "config.RearScreenAiAppConfig")) }
    private val provider: Any get() = invoke(config.javaClass.getMethod("getResourceProvider"), config)!!

    // Kotlin generic signatures distinguish suspend methods with identical JVM erasure.
    // If the vendor removes that evidence, fail closed instead of guessing a mutator.
    private val applyMethod by lazy {
        uniqueMethod(managerClass) {
            it.parameterTypes.contentEquals(arrayOf(paramsClass, Boolean::class.javaPrimitiveType, continuationClass))
        }
    }
    private val removeMethod by lazy {
        uniqueMethod(managerClass) {
            it.parameterTypes.contentEquals(arrayOf(String::class.java, continuationClass)) &&
                it.genericParameterTypes.last().typeName.contains(BASE + "manager.RemoveResult")
        }
    }
    private val listMethod by lazy {
        uniqueMethod(repositoryClass) {
            it.parameterTypes.contentEquals(arrayOf(continuationClass)) &&
                it.genericParameterTypes.last().typeName.contains("java.util.List") &&
                it.genericParameterTypes.last().typeName.contains("RearScreenAiAddItemBean")
        }
    }
    private val repositoryDeleteMethod by lazy {
        uniqueMethod(repositoryClass) {
            it.parameterTypes.contentEquals(arrayOf(String::class.java, continuationClass)) &&
                it.genericParameterTypes.last().typeName.contains("java.lang.Integer")
        }
    }
    private val repositoryRestoreRuntimeMethod by lazy {
        uniqueMethod(repositoryClass) {
            it.parameterTypes.contentEquals(arrayOf(continuationClass)) &&
                it.genericParameterTypes.last().typeName.contains("java.lang.Integer")
        }
    }
    private val subScreenClass by lazy { hostClass("com.xiaomi.subscreencenter.service.SubScreen") }
    private val subScreen by lazy {
        val factory = uniqueMethod(subScreenClass) {
            Modifier.isStatic(it.modifiers) && it.returnType == subScreenClass &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        }
        invoke(factory, null, context)!!
    }
    private val widgetListMethod by lazy {
        uniqueMethod(subScreenClass) { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
    }
    private val nativeWidgetBridgeClass by lazy { hostClass(BASE + "impl.RearScreenAiAppWidgetBridgeImpl") }
    private val nativeWidgetBridge by lazy { singleton(nativeWidgetBridgeClass) }
    private val restoreMethod by lazy {
        uniqueMethod(nativeWidgetBridgeClass) {
            it.returnType == Int::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(
                hostClass(BASE + "bean.RearScreenAiAddItemBean"), Boolean::class.javaPrimitiveType,
            ))
        }
    }

    private data class RegistrySnapshot(val records: List<AiRegistryRecord>, val widgets: List<Bundle>) {
        val ids: Set<String> get() = records.mapTo(linkedSetOf()) { it.id }
    }

    fun checkCompatibility() {
        check(android.os.Process.myUid() / 100000 == 0) { "主题原生智能应用暂不支持第二空间" }
        applyMethod; removeMethod; listMethod; widgetListMethod; repositoryDeleteMethod
        paramsConstructor()
        provider
        rearDisplayId()
    }

    fun listCards(): Bundle {
        checkCompatibility()
        val records = appliedRecords()
        // Refresh never launches the rear display. Durable registration is separate from live loading.
        val disk = readDiskRegistry()
        val widgets = widgetExtras()
        AiRegistryPolicy.requireTransition(disk, readDiskRegistry())
        val diskById = disk.associateBy { it.id }
        val runtime = registryRecords(widgets)
        val registryReady = disk == runtime
        val recordsById = records.groupBy { beanString(it, "getProductId") }
        val widgetsById = widgets.groupBy { it.getString("resId").orEmpty() }
        check(recordsById.values.all { it.size == 1 } && widgetsById.values.all { it.size == 1 }) {
            "系统应用 ID 不唯一，请在系统管理中检查"
        }
        // Catalog membership and the service's current memory are different observations.
        // Keep both visible; absence here is not proof of deletion or permission to clean up.
        val ids = (recordsById.keys + disk.filter { it.fields["appCardType"] == "2" }.map { it.id } +
            widgets.filter { it.getInt("appCardType") == 2 }
            .map { it.getString("resId").orEmpty() }).filter(String::isNotBlank).distinct()
        val items = ids.map { id ->
            val widget = widgetsById[id]?.single()
            val record = recordsById[id]?.single()
            val saved = diskById[id]
            val path = record?.let { beanString(it, "getResLocalPath") }
                ?: saved?.fields?.get("mtzPath") ?: widget?.getString(INTERNAL_PATH_KEY).orEmpty()
            Bundle().apply {
                putString("id", id)
                putString("name", record?.let { beanString(it, "getResName") }
                    ?.takeIf(String::isNotBlank) ?: saved?.fields?.get("appName") ?: widget?.getString("appName").orEmpty())
                putString("resourcePath", path)
                putBoolean("managed", record != null && runCatching { isManaged(record) }.getOrDefault(false))
                putBoolean("registered", saved?.fields?.get("appCardType") == "2")
                putBoolean("runtimeRegistered", widget?.getInt("appCardType") == 2)
            }
        }
        val message = if (registryReady) "已读取 ${items.size} 个智能应用" else
            "已读取 ${items.size} 个智能应用的持久化登记；背屏服务尚未同步"
        return result(true, message).apply {
            putParcelableArrayList("items", ArrayList(items))
            putBoolean("registryReady", registryReady)
        }
    }

    fun importCard(packageFd: ParcelFileDescriptor, displayName: String?): Bundle {
        checkCompatibility()
        val stageRoot = File(context.cacheDir, "outerview_ai_imports")
        check(stageRoot.isDirectory || stageRoot.mkdirs()) { "无法创建导入临时目录" }
        val stage = File(stageRoot, UUID.randomUUID().toString())
        check(stage.mkdir()) { "无法创建独立导入临时目录" }
        try {
            val input = File(stage, "input.zip")
            ParcelFileDescriptor.AutoCloseInputStream(packageFd).use { source ->
                FileOutputStream(input).use { destination ->
                    BoundedDeadlineCopy.copyWithSupervisor(
                        source, destination, AiPackageParser.MAX_PACKAGE_BYTES,
                        TimeUnit.SECONDS.toNanos(20), closeSource = { source.close() },
                    )
                    destination.fd.sync()
                }
            }
            val parsed = AiPackageParser.parse(input)
            val name = displayName?.trim()?.takeIf(String::isNotEmpty)
                ?: parsed.name.trim().takeIf(String::isNotEmpty) ?: "智能应用"
            require(name.length <= 100 && name.none { it.isISOControl() }) { "应用名称无效" }
            val id = PREFIX + UUID.randomUUID().toString().replace("-", "")
            val nativeDir = runtimeDir(id)
            require(!nativeDir.exists()) { "资源 ID 已存在，请重试" }
            require(appliedRecords().none { beanString(it, "getProductId") == id }) { "应用 ID 已存在" }

            // The native provider expects an already unpacked source plus these sibling assets.
            val source = File(stage, "rearScreen.mrc").apply { writeBytes(parsed.mamlZipBytes) }
            File(stage, "app").mkdir()
            File(stage, "preview").mkdir()
            File(stage, "app/app_icon.png").writeBytes(parsed.appIconBytes ?: placeholder(192, 192, name))
            File(stage, "preview/preview.png").writeBytes(parsed.previewBytes ?: placeholder(452, 488, name))
            val marker = JSONObject().put("providerPackage", OWNER).put("formatVersion", 1).toString()
            val params = paramsConstructor().newInstance(
                id, source.absolutePath, name, null, "由 OuterView 本地导入", null, null,
                null, null, null, null, false, marker, sha256(source),
            )
            // A connected service can still hold an empty, uninitialized launcher list.
            // Read its durable registry first and never let that empty list replace old cards.
            val before = ensureRegistryReady()
            require(id !in before.ids) { "背屏应用 ID 已存在" }
            // This performs files -> Room + JSON -> insertAppWidget and its native rollback.
            val nativeResult = try { suspendCall(applyMethod, manager, params, false) }
            catch (failure: Throwable) {
                error("导入结果未确认（$id），已保留现场且未执行额外清理，请刷新检查：${failure.message}")
            }
            if (!hostClass(BASE + "manager.ApplyResult\$Success").isInstance(nativeResult)) {
                error(nativeMessage(nativeResult, "导入") + "；未执行额外清理，请刷新检查")
            }
            try {
                check(awaitState(id, present = true)) { "原生接口返回成功，但持久化记录或背屏列表未完成同步" }
                awaitRegistryTransition(before.records, addedId = id)
                val record = appliedRecords().single { beanString(it, "getProductId") == id }
                check(isManaged(record)) { "宿主登记的资源路径与导入路径不一致" }
                check(File(beanString(record, "getResLocalPath")).isFile) { "宿主资源文件不存在" }
                return result(true, "已导入，可在系统智能应用管理中移除").apply {
                    putString("id", id)
                    putString("name", name)
                    putString("resourcePath", beanString(record, "getResLocalPath"))
                    putStringArrayList("warnings", ArrayList(parsed.warnings))
                }
            } catch (failure: Throwable) {
                // The native save is asynchronous. An absent widget does not prove that it was rejected.
                error("导入已提交但结果未确认（$id），已保留现场且未执行额外清理，请刷新检查：${failure.message}")
            }
        } finally {
            // Only a cache child created by this invocation is ever recursively removed here.
            if (stage.canonicalFile.parentFile == stageRoot.canonicalFile) stage.deleteRecursively()
        }
    }

    fun removeCard(id: String): Bundle {
        checkCompatibility()
        validateId(id)
        val before = ensureRegistryReady()
        val matches = before.widgets.filter { it.getString("resId") == id }
        check(matches.size <= 1) { "背屏应用 ID 不唯一，未执行移除" }
        val widget = matches.singleOrNull() ?: return removeCatalogOnlyRecord(id, before.records)
        require(widget.getInt("appCardType") == 2) { "此接口仅移除智能应用" }
        // Native cards are allowed, but all deletion is delegated to Xiaomi's own transaction.
        removeNative(id)
        check(awaitState(id, present = false)) { "原生移除已执行，但持久化状态未确认，请刷新检查" }
        awaitRegistryTransition(before.records, removedId = id)
        check(!runtimeDir(id).exists()) { "列表已移除，但主题未完成资源清理" }
        return result(true, "已从背屏和系统智能应用管理移除").apply { putString("id", id) }
    }

    /** Re-register the existing native record without copying resources or changing its Room row. */
    fun restoreCard(id: String): Bundle {
        checkCompatibility()
        validateId(id)
        var record = appliedRecords().filter { beanString(it, "getProductId") == id }.singleOrNull()
        if (record == null) {
            // Xiaomi's own repository can rebuild Room from its durable runtime JSON.
            // Only invoke it when the requested ID and all original files are present.
            val mirror = runtimeMirror()
            check((0 until mirror.length()).any { mirror.optJSONObject(it)?.optString("productId") == id }) {
                "找不到唯一的系统管理记录或原生恢复快照，未执行恢复"
            }
            suspendCall(repositoryRestoreRuntimeMethod, repository)
            record = appliedRecords().filter { beanString(it, "getProductId") == id }.singleOrNull()
        }
        checkNotNull(record) { "系统管理记录仍未恢复，未执行背屏登记" }
        val directory = safeNativeResourceDirectory(record, id)
            ?: error("原应用资源目录无法安全确认，未执行恢复")
        requireRestoreFile(beanString(record, "getResLocalPath"), File(directory, "rearScreen.mrc"))
        requireRestoreFile(beanString(record, "getAppPicPath"), File(directory, "app_icon.png"))
        requireRestoreFile(beanString(record, "getPreviewImagePath"), File(directory, "preview.png"))
        val before = ensureRegistryReady()
        val existing = before.widgets.singleOrNull { it.getString("resId") == id }
        if (existing != null) {
            check(existing.getInt("appCardType") == 2) { "该 ID 已被其他类型应用登记，未执行恢复" }
            check(existing.getString(INTERNAL_PATH_KEY) == beanString(record, "getResLocalPath") &&
                existing.getString("appIconPath") == beanString(record, "getAppPicPath") &&
                existing.getString("previewLightPath") == beanString(record, "getPreviewImagePath")) {
                "该 ID 已登记，但资源路径与原管理记录不同，未覆写任何记录"
            }
            AiRegistryPolicy.requireTransition(before.records, ensureRegistryReady().records)
            return result(true, "应用已在背屏登记，无需重复恢复").apply {
                putString("id", id); putBoolean("registered", true)
            }
        }
        val nativeCode = invoke(restoreMethod, nativeWidgetBridge, record, false) as? Number
        check(nativeCode?.toInt() == 0) { "原生恢复登记失败（错误码 ${nativeCode ?: "未知"}），原记录和资源已保留" }
        // Do not attempt a destructive rollback if unrelated state changed during this operation.
        awaitRegistryTransition(before.records, addedId = id)
        check(awaitState(id, present = true)) { "恢复登记已执行，但系统管理索引未确认一致；原记录和资源已保留" }
        val afterRecord = appliedRecords().singleOrNull { beanString(it, "getProductId") == id }
        check(record == afterRecord) { "恢复期间管理记录发生变化，请检查；未覆写原管理记录" }
        return result(true, "已恢复原应用的背屏登记，原资源和记录时间保持不变").apply {
            putString("id", id)
            putString("name", beanString(record, "getResName"))
            putString("resourcePath", beanString(record, "getResLocalPath"))
            putBoolean("registered", true)
        }
    }

    /** Only reached from an explicit remove request, never while enumerating or refreshing. */
    private fun removeCatalogOnlyRecord(id: String, beforeRecords: List<AiRegistryRecord>): Bundle {
        val record = appliedRecords().filter { beanString(it, "getProductId") == id }.singleOrNull()
            ?: error("该应用已不在系统管理记录中，或记录不唯一，请刷新")
        // Requery after Room: a missing/unavailable service response must not authorize cleanup.
        val ready = ensureRegistryReady()
        AiRegistryPolicy.requireTransition(beforeRecords, ready.records)
        check(id !in ready.ids) {
            "应用注册状态已变化，未执行管理记录清理，请刷新"
        }
        // This narrows the cross-client window; Xiaomi's delete-by-id API is not compare-and-delete.
        val currentRecord = appliedRecords().filter { beanString(it, "getProductId") == id }.singleOrNull()
        check(record == currentRecord) { "管理记录已变化，未执行移除，请刷新后重新确认" }
        val deleted = suspendCall(repositoryDeleteMethod, repository, id) as? Number
        check(deleted?.toInt() == 1) { "系统管理记录删除未返回明确成功结果" }
        check(awaitState(id, present = false)) { "管理记录已请求移除，但数据库和索引未确认同步，资源已保留" }
        awaitRegistryTransition(beforeRecords)
        // Never recursively delete an unknown native directory merely because its widget is absent.
        return result(true, "已移除未登记应用的系统管理记录；原资源文件已保留").apply {
            putString("id", id)
            putBoolean("registered", false)
            putBoolean("resourcesRetained", true)
        }
    }

    private fun safeNativeResourceDirectory(record: Any, id: String): File? = runCatching {
        if (!SAFE_RESOURCE_ID.matches(id)) return@runCatching null
        val root = runtimeDir(PREFIX + "probe").parentFile?.canonicalFile ?: return@runCatching null
        val directory = runtimeDir(id)
        val expectedDirectory = File(root, id)
        // Restore only reads files from this native id directory; it never copies or deletes them.
        if (directory.canonicalFile != expectedDirectory || directory.canonicalFile.parentFile != root) {
            return@runCatching null
        }
        val resource = File(beanString(record, "getResLocalPath"))
        val expectedResource = File(expectedDirectory, "rearScreen.mrc")
        if (!resource.isAbsolute || resource.canonicalFile != expectedResource ||
            expectedResource.canonicalFile != expectedResource) return@runCatching null
        expectedDirectory
    }.getOrNull()

    private fun removeNative(id: String) {
        val nativeResult = suspendCall(removeMethod, manager, id)
        check(hostClass(BASE + "manager.RemoveResult\$Success").isInstance(nativeResult)) {
            nativeMessage(nativeResult, "移除")
        }
    }

    private fun validateId(id: String) {
        require(id.isNotBlank() && id.length <= 200 && '/' !in id && '\\' !in id &&
            id.none { it.isISOControl() } && id != "." && id != "..") { "应用 ID 无效" }
    }

    private fun requireRestoreFile(path: String, expected: File) {
        val file = File(path)
        check(file.isAbsolute && file.canonicalFile == expected && expected.canonicalFile == expected &&
            file.isFile && file.canRead()) { "恢复所需的原资源不存在或路径不符：${expected.name}" }
    }

    private fun appInfoFile(): File {
        val themeRoot = runtimeDir(PREFIX + "probe").parentFile ?: error("原生资源根目录不可用")
        val userRoot = themeRoot.parentFile ?: error("原生用户目录不可用")
        return File(userRoot, "subscreencenter/config/appInfo.json")
    }

    /** Reads the on-disk snapshot before asking the service: querying does not initialize it. */
    private fun readDiskRegistry(): List<AiRegistryRecord> {
        val file = appInfoFile()
        check(file.isFile && file.canRead()) { "无法读取背屏持久化登记，已停止写入" }
        require(file.length() in 1..MAX_RUNTIME_JSON_BYTES) { "背屏登记文件大小异常，已停止写入" }
        val array = JSONArray(file.readText().removePrefix("\uFEFF"))
        val records = (0 until array.length()).map { normalizeRegistryRecord(array.getJSONObject(it)) }
        check(records.size == records.map { it.id }.toSet().size) { "背屏登记 ID 重复，已停止写入" }
        return records
    }

    private fun registryRecords(widgets: List<Bundle>): List<AiRegistryRecord> {
        val records = widgets.map { widget ->
            val json = JSONObject().put("resId", widget.getString("resId"))
            STRING_FIELDS.forEach { key ->
                json.put(key, (if (key == "mtzPath") widget.getString(INTERNAL_PATH_KEY) else widget.getString(key)) ?: JSONObject.NULL)
            }
            BOOLEAN_FIELDS.forEach { json.put(it, widget.getBoolean(it, false)) }
            json.put("appCardType", widget.getInt("appCardType", -1))
            normalizeRegistryRecord(json)
        }
        check(records.size == records.map { it.id }.toSet().size) { "背屏服务 ID 重复，已停止写入" }
        return records
    }

    private fun normalizeRegistryRecord(json: JSONObject): AiRegistryRecord {
        val id = json.opt("resId") as? String ?: error("背屏登记 ID 格式无效，已停止写入")
        validateId(id)
        val fields = linkedMapOf<String, String?>()
        STRING_FIELDS.forEach { key ->
            val value = json.opt(key)
            check(value == null || value == JSONObject.NULL || value is String) { "背屏登记字段格式无效：$key" }
            fields[key] = value as? String
        }
        BOOLEAN_FIELDS.forEach { key ->
            val value = json.opt(key)
            check(value == null || value == JSONObject.NULL || value is Boolean) { "背屏登记字段格式无效：$key" }
            fields[key] = ((value as? Boolean) ?: false).toString()
        }
        val cardType = json.opt("appCardType")
        check(cardType == null || cardType == JSONObject.NULL || cardType is Int) { "背屏应用类型格式无效" }
        fields["appCardType"] = ((cardType as? Int) ?: -1).toString()
        val known = STRING_FIELDS + BOOLEAN_FIELDS + listOf("resId", "appCardType")
        // Preserve unknown durable fields too. A host SDK unable to represent them is not safe to write.
        json.keys().asSequence().filter { it !in known }.sorted().forEach { key ->
            fields[key] = canonicalJson(json.get(key))
        }
        return AiRegistryRecord(id, fields.toMap())
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> error("背屏登记包含无法比较的字段")
    }

    private fun rearDisplayId(): Int {
        // The Xiaomi SDK uses SUB_BUILTIN_DISPLAY; display 1 is its documented fallback in this build.
        val id = runCatching {
            Display::class.java.getDeclaredField("SUB_BUILTIN_DISPLAY").apply { isAccessible = true }.getInt(null)
        }.getOrDefault(1)
        check(id > 0 && context.getSystemService(DisplayManager::class.java)?.getDisplay(id) != null) {
            "未找到系统背屏显示器，未执行修改"
        }
        return id
    }

    private fun ensureRegistryReady(): RegistrySnapshot {
        val diskSnapshot = readDiskRegistry()
        var startedLauncher = false
        repeat(40) {
            val widgets = widgetExtras()
            val nativeRecords = registryRecords(widgets)
            val currentDisk = readDiskRegistry()
            // Do not replace a nonempty original snapshot with a later empty or changed file.
            AiRegistryPolicy.requireTransition(diskSnapshot, currentDisk)
            if (diskSnapshot == nativeRecords) {
                AiRegistryPolicy.requireReady(diskSnapshot, nativeRecords)
                return RegistrySnapshot(nativeRecords, widgets)
            }
            if (!startedLauncher) {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(rearDisplayId())
                context.startActivity(Intent().setClassName(
                    "com.xiaomi.subscreencenter", "com.xiaomi.subscreencenter.SubScreenLauncher",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
                startedLauncher = true
            }
            Thread.sleep(150)
        }
        error("背屏服务尚未加载完整的持久化登记，已停止写入；请打开背屏应用列表后重试")
    }

    private fun awaitRegistryTransition(beforeRecords: List<AiRegistryRecord>, addedId: String? = null, removedId: String? = null) {
        repeat(35) {
            val diskRecords = readDiskRegistry()
            val nativeRecords = registryRecords(widgetExtras())
            val diskAfter = readDiskRegistry()
            if (diskRecords == diskAfter && diskRecords == nativeRecords && runCatching {
                AiRegistryPolicy.requireTransition(beforeRecords, nativeRecords, addedId, removedId)
            }.isSuccess) {
                AiRegistryPolicy.requireReady(diskRecords, nativeRecords)
                return
            }
            Thread.sleep(100)
        }
        error("操作后的背屏登记未完整保留既有应用，已停止后续写入；请检查系统应用状态")
    }

    private fun awaitState(id: String, present: Boolean): Boolean {
        repeat(35) {
            val records = appliedRecords()
            val hasRecord = records.any { beanString(it, "getProductId") == id }
            val hasWidget = widgetExtras().any { it.getString("resId") == id && it.getInt("appCardType") == 2 }
            val mirror = runtimeMirror()
            val mirrorIds = (0 until mirror.length()).map { mirror.optJSONObject(it)?.optString("productId") }
            val recordIds = records.map { beanString(it, "getProductId") }
            val hasMirror = id in mirrorIds
            val mirrorMatchesDatabase = mirrorIds.size == recordIds.size && mirrorIds.toSet() == recordIds.toSet()
            if (hasRecord == present && hasWidget == present && hasMirror == present && mirrorMatchesDatabase) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun runtimeMirror(): JSONArray {
        val root = runtimeDir(PREFIX + "probe").parentFile ?: error("原生目录没有父路径")
        val file = File(root, "runtimeAiApp.json")
        if (!file.isFile) return JSONArray()
        require(file.length() <= MAX_RUNTIME_JSON_BYTES) { "宿主智能应用索引过大" }
        return JSONArray(file.readText().removePrefix("\uFEFF"))
    }

    private fun runtimeDir(id: String): File {
        val path = invoke(provider.javaClass.getMethod("getRuntimeDir", String::class.java), provider, id) as? String
            ?: error("主题没有返回资源目录")
        val directory = File(path)
        require(directory.isAbsolute && directory.name == id && directory.canonicalFile.name == id) {
            "主题返回了不安全的资源目录"
        }
        return directory
    }

    private fun isManaged(bean: Any): Boolean {
        val id = beanString(bean, "getProductId")
        if (!OWNED_ID.matches(id)) return false
        val marker = runCatching { JSONObject(beanString(bean, "getAiAppExtra")) }.getOrNull() ?: return false
        if (marker.optString("providerPackage") != OWNER) return false
        return File(beanString(bean, "getResLocalPath")).canonicalFile == File(runtimeDir(id), "rearScreen.mrc").canonicalFile
    }

    private fun appliedRecords(): List<Any> {
        val records = suspendCall(listMethod, repository) as? List<*> ?: error("无法读取主题智能应用库")
        return records.map { it ?: error("主题智能应用库包含空记录") }
    }

    private fun widgetExtras(): List<Bundle> {
        val widgets = invoke(widgetListMethod, subScreen) as? List<*> ?: error("背屏服务尚未连接，请稍后重试")
        return widgets.map { widget ->
            requireNotNull(widget)
            val extra = invoke(widget.javaClass.getMethod("getExtra"), widget) as? Bundle
                ?: error("背屏应用缺少标识信息")
            Bundle(extra).apply {
                putString(INTERNAL_PATH_KEY, invoke(widget.javaClass.getMethod("getPath"), widget) as? String)
            }
        }
    }

    private fun beanString(bean: Any, getter: String): String =
        invoke(bean.javaClass.getMethod(getter), bean) as? String ?: ""

    private fun paramsConstructor() = paramsClass.declaredConstructors.singleOrNull { constructor ->
        val p = constructor.parameterTypes
        p.size == 14 && p.indices.all { p[it] == if (it == 11) Boolean::class.javaPrimitiveType else String::class.java }
    }?.apply { isAccessible = true } ?: error("当前主题 ApplyParams 结构不受支持")

    private fun hostClass(name: String): Class<*> = Class.forName(name, false, hostLoader)

    private fun singleton(type: Class<*>): Any {
        val field = type.declaredFields.singleOrNull { Modifier.isStatic(it.modifiers) && it.type == type }
            ?: error("无法识别宿主单例：${type.name}")
        field.isAccessible = true
        return field.get(null) ?: error("宿主尚未初始化：${type.name}")
    }

    private fun uniqueMethod(type: Class<*>, predicate: (Method) -> Boolean): Method =
        type.declaredMethods.filter { !it.isSynthetic && predicate(it) }.singleOrNull()
            ?.apply { isAccessible = true } ?: error("当前主题接口不受支持：${type.simpleName}")

    /** A host-loaded proxy avoids casting the theme's Kotlin runtime into the module's runtime. */
    private fun suspendCall(method: Method, receiver: Any, vararg arguments: Any?): Any? {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "宿主调用不能阻塞主线程" }
        val latch = CountDownLatch(1)
        val completion = AtomicReference<Any?>()
        val emptyContext = hostClass("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null)
        val continuation = Proxy.newProxyInstance(hostLoader, arrayOf(continuationClass)) { proxy, callback, args ->
            when (callback.name) {
                "getContext" -> emptyContext
                "resumeWith" -> { completion.set(args?.get(0)); latch.countDown(); null }
                "toString" -> "OuterViewThemeContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("未知 Continuation 方法：${callback.name}")
            }
        }
        val immediate = invoke(method, receiver, *arguments, continuation)
        val marker = invoke(hostClass("kotlin.coroutines.intrinsics.IntrinsicsKt")
            .getMethod("getCOROUTINE_SUSPENDED"), null)
        if (immediate !== marker) return immediate
        // The Binder wrapper has a bounded wait. Never cancel a host transaction mid-write.
        latch.await()
        val value = completion.get()
        invoke(hostClass("kotlin.ResultKt").getMethod("throwOnFailure", Any::class.java), null, value)
        return value
    }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.isAccessible = true
        method.invoke(receiver, *args)
    } catch (failure: InvocationTargetException) {
        throw (failure.targetException ?: failure)
    }

    private fun nativeMessage(value: Any?, operation: String): String = when (value?.javaClass?.simpleName) {
        "WidgetFull" -> "背屏应用数量已达上限"
        "AlreadyExists" -> "背屏应用 ID 已存在"
        "Failed" -> {
            val code = value.javaClass.declaredFields.singleOrNull {
                !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
            }?.let { it.isAccessible = true; it.getInt(value) }
            if (code == -3) "系统要求至少保留一个背屏应用" else "主题${operation}失败（错误码 ${code ?: "未知"}）"
        }
        else -> "主题${operation}未返回明确成功结果（${value?.javaClass?.simpleName ?: "null"}）"
    }

    private fun placeholder(width: Int, height: Int, name: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(28, 36, 55))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(136, 200, 255)
                textAlign = Paint.Align.CENTER
                textSize = width * 0.24f
                isFakeBoldText = true
            }
            canvas.drawText(name.take(2), width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            return java.io.ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun result(success: Boolean, message: String) = Bundle().apply {
        putBoolean("success", success)
        putString("message", message)
    }
}
