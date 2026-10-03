package org.orynnx.outerview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.orynnx.outerview.core.ai.AiAppManager
import java.io.File

/** Device acceptance entry point; debug-only and protected by DUMP. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.removePrefix(PREFIX) ?: return
        if (intent.action != PREFIX + action || action !in ACTIONS) return
        val pending = goAsync()
        Thread({
            try {
                runBlocking {
                    val manager = AiAppManager.create(context.applicationContext)
                    val result = when (action) {
                        "SNAPSHOT" -> {
                            val snapshot = manager.snapshot()
                            JSONObject().put("success", snapshot.connected)
                                .put("message", snapshot.message)
                                .put("cards", JSONArray().apply {
                                    snapshot.cards.forEach { card ->
                                        put(JSONObject().put("id", card.id).put("name", card.name)
                                            .put("resourcePath", card.resourcePath).put("managed", card.managed)
                                            .put("registered", card.registered))
                                    }
                                })
                        }
                        "IMPORT" -> {
                            val source = File(requireNotNull(intent.getStringExtra("path")))
                            require(source.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) {
                                "测试导入仅允许应用 cache 目录"
                            }
                            val preview = manager.inspect(Uri.fromFile(source))
                            try {
                                val imported = manager.importCard(preview, intent.getStringExtra("name") ?: preview.name)
                                JSONObject().put("success", imported.success).put("message", imported.message)
                            } finally {
                                manager.discardPreview(preview.token)
                            }
                        }
                        "REMOVE" -> {
                            val id = requireNotNull(intent.getStringExtra("id"))
                            require(id.startsWith("outerview_ai_")) { "测试入口仅移除 OuterView 测试卡片" }
                            val removed = manager.remove(id)
                            JSONObject().put("success", removed.success).put("message", removed.message)
                        }
                        "RESTORE" -> {
                            val restored = manager.restore(requireNotNull(intent.getStringExtra("id")))
                            JSONObject().put("success", restored.success).put("message", restored.message)
                        }
                        else -> JSONObject().put("success", manager.openSystemManager(context))
                    }
                    Log.i(TAG, "$action $result")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "$action " + JSONObject().put("success", false).put("message", error.message), error)
            } finally {
                pending.finish()
            }
        }, "OuterView-AI-Acceptance").start()
    }

    private companion object {
        const val PREFIX = "org.orynnx.outerview.DEBUG_AI_"
        const val TAG = "OuterView-AI-Test"
        val ACTIONS = setOf("SNAPSHOT", "IMPORT", "REMOVE", "RESTORE", "OPEN_SYSTEM")
    }
}
