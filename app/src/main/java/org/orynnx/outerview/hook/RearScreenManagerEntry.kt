package org.orynnx.outerview.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.orynnx.outerview.core.ai.AiAppHostContract
import java.util.Collections
import java.util.WeakHashMap

/** Adds a native controller to the same group; the host owns row styling and corners. */
internal object RearScreenManagerEntry {
    const val KEY = "org.orynnx.outerview.manager.entry"
    private val entries = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun isEntry(value: Any?) = value != null && entries.containsKey(value)

    fun intent(): Intent = Intent().setClassName(
        AiAppHostContract.PROVIDER_PACKAGE, "org.orynnx.outerview.AiAppManagerActivity",
    )

    fun open(activity: Activity) {
        runCatching { activity.startActivity(intent()) }.onFailure {
            Toast.makeText(activity, "无法打开 OuterView，请确认应用已安装", Toast.LENGTH_SHORT).show()
        }
    }

    fun insert(original: List<*>, context: Context, controllerClass: Class<*>): List<*> {
        if (original.any { isEntry(it) }) return original
        // Anchor by concrete controller identity, never localized label or screen coordinates.
        val matches = original.indices.filter {
            original[it]?.javaClass?.name == "com.rearScreen.subscreen.RearScreenAiAppController"
        }
        if (matches.size != 1) return original
        val entry = controllerClass.getConstructor(Context::class.java).newInstance(context)
        entries[entry] = true
        return original.toMutableList().apply { add(matches.single() + 1, entry) }
    }
}
