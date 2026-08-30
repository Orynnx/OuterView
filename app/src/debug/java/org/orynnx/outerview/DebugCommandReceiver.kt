package org.orynnx.outerview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.orynnx.outerview.core.RearCardManager
import org.orynnx.outerview.core.internal.BoundedDeadlineCopy
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostClient
import org.orynnx.outerview.core.wallpaperapi.RearWallpaperHostContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** ADB-only device test entry point. The debug manifest protects it with DUMP. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SupportedActions) return
        val pendingResult = goAsync()
        Thread({
            try {
                when {
                    intent.action == ActionTestBlockedImport -> runBlockedImportSelfTest()
                    intent.action in WallpaperActions -> runWallpaperAction(context.applicationContext, intent)
                    else -> runAssistantAction(context.applicationContext, intent)
                }
            } finally {
                pendingResult.finish()
            }
        }, "OuterView-Debug-Command").start()
    }

    private fun runWallpaperAction(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        val client = RearWallpaperHostClient()
        val result = runCatching {
            check(client.connect(context)) { "wallpaper host not connected or incompatible" }
            when (action) {
                ActionApplyWallpaper -> client.apply(requiredWallpaperId(intent))
                ActionRenameWallpaper -> client.rename(
                    requiredWallpaperId(intent),
                    intent.getStringExtra("name") ?: error("missing name"),
                )
                else -> {
                    val file = File(intent.getStringExtra("path") ?: error("missing path"))
                    check(file.isFile) { "wallpaper file not found" }
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        client.import(fd, file.name)
                    }
                }
            }
        }.getOrElse { error ->
            Bundle().apply {
                putBoolean(RearWallpaperHostContract.Keys.SUCCESS, false)
                putString(RearWallpaperHostContract.Keys.MESSAGE, error.message)
            }
        }
        Log.i(
            WallpaperLogTag,
            "action=$action id=${result.getInt(RearWallpaperHostContract.Keys.WALLPAPER_ID, Int.MIN_VALUE)} " +
                "success=${result.getBoolean(RearWallpaperHostContract.Keys.SUCCESS)} " +
                "message=${result.getString(RearWallpaperHostContract.Keys.MESSAGE)}",
        )
    }

    private fun runAssistantAction(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        runCatching {
            runBlocking {
                val cardId = intent.getStringExtra("cardId")?.takeIf(String::isNotBlank)
                    ?: error("missing cardId")
                val manager = RearCardManager.create(context)
                when (action) {
                    ActionShowAssistant -> manager.setVisible(cardId, true)
                    ActionHideAssistant -> manager.setVisible(cardId, false)
                    else -> manager.deleteCard(cardId)
                }
            }
        }.onSuccess { result ->
            Log.i(AssistantLogTag, "action=$action success=${result.success} message=${result.message}")
        }.onFailure { error ->
            Log.e(AssistantLogTag, "action=$action failed", error)
        }
    }

    /** Emulator/device proof that closing a real Linux pipe releases a blocked import read. */
    private fun runBlockedImportSelfTest() {
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        val startedAt = SystemClock.elapsedRealtime()
        val error = try {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(reader).use { input ->
                    BoundedDeadlineCopy.copyWithSupervisor(
                        input = input,
                        output = ByteArrayOutputStream(),
                        maxBytes = 1024L,
                        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(250L),
                        closeSource = reader::close,
                    )
                }
            }.exceptionOrNull()
        } finally {
            runCatching { reader.close() }
            runCatching { writer.close() }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val passed = error is BoundedDeadlineCopy.DeadlineExceededException && elapsedMs < 2_000L
        Log.i(
            ImportSelfTestLogTag,
            "passed=$passed elapsedMs=$elapsedMs error=${error?.javaClass?.simpleName.orEmpty()}",
        )
        check(passed) { "blocked import self-test failed after ${elapsedMs}ms: $error" }
    }

    private fun requiredWallpaperId(intent: Intent): Int =
        intent.getIntExtra("wallpaperId", Int.MIN_VALUE).also { id ->
            check(id != Int.MIN_VALUE) { "missing wallpaperId" }
        }

    private companion object {
        const val ActionApplyWallpaper = "org.orynnx.outerview.DEBUG_APPLY_WALLPAPER"
        const val ActionImportWallpaper = "org.orynnx.outerview.DEBUG_IMPORT_WALLPAPER"
        const val ActionRenameWallpaper = "org.orynnx.outerview.DEBUG_RENAME_WALLPAPER"
        const val ActionShowAssistant = "org.orynnx.outerview.DEBUG_SHOW_ASSISTANT"
        const val ActionHideAssistant = "org.orynnx.outerview.DEBUG_HIDE_ASSISTANT"
        const val ActionDeleteAssistant = "org.orynnx.outerview.DEBUG_DELETE_ASSISTANT"
        const val ActionTestBlockedImport = "org.orynnx.outerview.DEBUG_TEST_BLOCKED_IMPORT"
        const val WallpaperLogTag = "OuterView-Wallpaper-Test"
        const val AssistantLogTag = "OuterView-Assistant-Test"
        const val ImportSelfTestLogTag = "OuterView-Import-Test"

        val WallpaperActions = setOf(ActionApplyWallpaper, ActionImportWallpaper, ActionRenameWallpaper)
        val SupportedActions = WallpaperActions + setOf(
            ActionShowAssistant,
            ActionHideAssistant,
            ActionDeleteAssistant,
            ActionTestBlockedImport,
        )
    }
}
