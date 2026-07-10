package hk.uwu.reareye.funcardcore.internal

import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

object FunCardNotificationController {
    fun buildRuntimePayload(record: CustomCardRecord): CardRuntimePayload {
        val rearParam = if (record.advancedPayload) {
            forceBusiness(record.advancedRearParamJson.orEmpty(), record.business, rear = true)
        } else {
            val config = JSONObject(record.mamlConfigJson.ifBlank { "{}" })
            JSONObject().put(
                "rear_param_v1",
                JSONObject()
                    .put("business", record.business)
                    .put("priority", 500)
                    .put("index", 0)
                    .put("disable_popup", true)
                    .put("show_time_tip", true)
                    .put("maml_config", config),
            ).toString()
        }
        val focusParam = if (record.advancedPayload) {
            forceBusiness(record.advancedFocusParamJson.orEmpty(), record.business, rear = false)
        } else {
            JSONObject()
                .put("business", record.business)
                .put("priority", 500)
                .put("maml_config", JSONObject(record.mamlConfigJson.ifBlank { "{}" }))
                .toString()
        }
        return CardRuntimePayload(rearParam, focusParam)
    }

    fun cancelLegacySystemProbes(context: Context) {
        LegacyProbeBusinesses.forEach { business ->
            cancel(context, 780_000 + (business.hashCode() and 0x7fff) % 10_000)
        }
    }

    fun cancel(context: Context, notificationId: Int) {
        manager(context).cancel(notificationId)
    }

    fun isActive(context: Context, notificationId: Int): Boolean =
        runCatching { manager(context).activeNotifications.any { it.id == notificationId } }
            .getOrDefault(false)

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun forceBusiness(raw: String, business: String, rear: Boolean): String {
        val root = JSONObject(raw.ifBlank { "{}" })
        if (rear) {
            val v1 = root.optJSONObject("rear_param_v1") ?: JSONObject().also {
                root.put("rear_param_v1", it)
            }
            v1.put("business", business)
        } else {
            val v2 = root.optJSONObject("param_v2")
            if (v2 != null) v2.put("business", business) else root.put("business", business)
        }
        return root.toString()
    }

    private val LegacyProbeBusinesses = listOf(
        "alarm",
        "countdown",
        "incall",
        "privacy",
        "music",
        "stock",
        "carHailing",
        "foodDelivery",
        "xiaomiev",
        "sports_schedule",
    )
}

data class CardRuntimePayload(
    val rearParam: String,
    val focusParam: String,
)
