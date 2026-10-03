package org.orynnx.outerview.core.ai

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.orynnx.outerview.core.internal.BoundedDeadlineCopy
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Public API for the intelligent-app manager; all binder and file I/O runs off the main thread. */
class AiAppManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val client = AiAppHostClient()

    companion object {
        private val operationMutex = Mutex()
        private val previews = AiPreviewStore()
        @JvmStatic fun create(context: Context): AiAppManager = AiAppManager(context)
    }

    suspend fun snapshot(): AiAppSnapshot = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            previews.prune()
            try {
                if (!client.connect(appContext)) AiAppSnapshot(false, emptyList(), client.message)
                else AiAppSnapshot(true, client.list(), client.message)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AiAppSnapshot(false, emptyList(), error.message ?: "读取智能应用失败")
            }
        }
    }

    /** Throws on invalid input. Inspection changes only this app's private cache. */
    suspend fun inspect(uri: Uri): AiImportPreview = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            require(uri.scheme in setOf("content", "file")) { "请选择本地文件或文档提供者中的 ZIP/MRC" }
            val directory = File(appContext.cacheDir, "ai-imports").apply { check(isDirectory || mkdirs()) { "无法创建导入缓存" } }
            // A previous process cannot have any live preview tokens. Limit stale private staging files.
            directory.listFiles()?.filter { it.isFile && it.name.startsWith("incoming-") &&
                System.currentTimeMillis() - it.lastModified() > TimeUnit.HOURS.toMillis(1) }?.forEach { it.delete() }
            val staging = File.createTempFile("incoming-", ".zip", directory)
            try {
                val source = AtomicReference<Closeable?>()
                BoundedDeadlineCopy.runWithSupervisor(TimeUnit.SECONDS.toNanos(20), { source.get()?.close() }) {
                    val input = appContext.contentResolver.openInputStream(uri) ?: error("无法打开选中的文件")
                    source.set(input)
                    input.use { stream ->
                        staging.outputStream().use { output ->
                            BoundedDeadlineCopy.copy(stream, output, AiPackageParser.MAX_PACKAGE_BYTES, TimeUnit.SECONDS.toNanos(20))
                        }
                    }
                }
                val parsed = AiPackageParser.parse(staging)
                val token = previews.put(staging)
                AiImportPreview(token, parsed.name, parsed.warnings)
            } catch (error: Throwable) {
                staging.delete()
                throw error
            }
        }
    }

    suspend fun importCard(preview: AiImportPreview, displayName: String = preview.name): AiActionResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val pending = previews.take(preview.token)
                ?: return@withLock AiActionResult(false, "导入预览已过期，请重新选择文件")
            try {
                val name = displayName.trim()
                require(name.isNotEmpty() && name.length <= 80 && name.none(Character::isISOControl)) { "名称必须为 1 至 80 个字符" }
                require(pending.file.isFile && pending.file.length() <= AiPackageParser.MAX_PACKAGE_BYTES &&
                    AiPreviewStore.digest(pending.file) == pending.hash) { "预览文件已变化，请重新选择文件" }
                if (!client.connect(appContext)) return@withLock AiActionResult(false, client.message)
                ParcelFileDescriptor.open(pending.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    client.import(fd, name)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AiActionResult(false, error.message ?: "导入智能应用失败")
            } finally {
                pending.file.delete()
            }
        }
    }

    suspend fun remove(cardId: String): AiActionResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                require(cardId.isNotBlank()) { "应用 ID 为空" }
                if (!client.connect(appContext)) return@withLock AiActionResult(false, client.message)
                if (client.list().none { it.id == cardId }) {
                    return@withLock AiActionResult(false, "应用已不存在，请刷新列表")
                }
                client.remove(cardId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AiActionResult(false, error.message ?: "移除智能应用失败")
            }
        }
    }

    fun discardPreview(token: String) = previews.discard(token)

    /** Restores a system management record whose resource is intact but rear registration is absent. */
    suspend fun restore(cardId: String): AiActionResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                require(cardId.isNotBlank()) { "应用 ID 为空" }
                if (!client.connect(appContext)) return@withLock AiActionResult(false, client.message)
                client.restore(cardId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AiActionResult(false, error.message ?: "恢复背屏显示失败")
            }
        }
    }

    /** The system activity requires an internal permission; only its own host may launch it. */
    suspend fun openSystemManager(context: Context): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                client.connect(context.applicationContext) && client.openSystemManager().success
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }
}
