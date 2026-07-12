package hk.uwu.reareye.funcardcore.internal

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object RearWallpaperRuntimeCodec {
    fun decode(raw: String): List<RearWallpaperRuntimeRecord> {
        if (raw.isBlank()) return emptyList()
        val array = JsonParser.parseString(raw).asJsonArray
        return array.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val resId = item.string("resId") ?: return@mapNotNull null
            val applyId = item.string("applyId") ?: return@mapNotNull null
            RearWallpaperRuntimeRecord(
                resId = resId,
                applyId = applyId,
                resLocalPath = item.string("resLocalPath"),
                metaPath = item.string("metaPath"),
                previewPath = item.string("snapshotPreviewPath") ?: item.string("resPreviewPath"),
                position = item.get("position")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1,
                displayName = item.localizedString("resName"),
            )
        }
    }

    fun append(raw: String, record: RearWallpaperRuntimeRecord): String {
        val array = if (raw.isBlank()) JsonArray() else JsonParser.parseString(raw).asJsonArray.deepCopy()
        require(array.none { item ->
            item.isJsonObject && item.asJsonObject.string("resId") == record.resId &&
                item.asJsonObject.string("applyId") == record.applyId
        }) { "duplicate wallpaper runtime key" }
        val now = System.currentTimeMillis()
        array.add(JsonObject().apply {
            addProperty("resType", "OuterView")
            addProperty("resId", record.resId)
            addProperty("resSubType", "outerview_import")
            addProperty("resTypeName", localeValue("OuterView"))
            addProperty("applyId", record.applyId)
            addProperty("resName", localeValue(record.displayName?.takeIf { it.isNotBlank() } ?: record.resId))
            addProperty("resDescription", localeValue("Imported by OuterView"))
            // These fields intentionally match the complete record shape emitted by
            // Xiaomi's rear-screen resource pipeline.  In particular, null preview
            // values and a missing onlineId cause some Android 16 builds to discard
            // third-party runtime records before widget construction.
            addProperty("resPreviewPath", record.previewPath ?: "")
            addProperty("resDesigner", localeValue("OuterView"))
            addProperty("resLocalPath", record.resLocalPath)
            addProperty("resSnapshotPath", record.resLocalPath)
            addProperty("metaPath", record.metaPath)
            addProperty("metaSnapshotPath", record.metaPath)
            addProperty("snapshotPreviewPath", record.previewPath ?: "")
            addProperty("position", record.position)
            addProperty("isDownload", false)
            addProperty("downloadUrl", "")
            addProperty("applyTime", now)
            addProperty("updateTime", now)
            addProperty("isNFC", false)
            addProperty("packageName", "org.orynnx.outerview")
            addProperty("editable", false)
            addProperty("isThirdParties", true)
            addProperty("supportAon", false)
            addProperty("isOnlineResource", false)
            addProperty("onlineId", "")
        })
        return array.toString()
    }

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun localeValue(value: String): String = JsonObject().apply {
        addProperty("fallback", value)
        addProperty("zh_CN", value)
    }.toString()

    private fun JsonObject.localizedString(name: String): String? {
        val raw = string(name) ?: return null
        return runCatching {
            val locale = JsonParser.parseString(raw).asJsonObject
            locale.string("zh_CN") ?: locale.string("fallback")
        }.getOrNull() ?: raw
    }
}
