package org.orynnx.outerview.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import org.orynnx.outerview.core.ai.AiAppHostContract
import org.orynnx.outerview.core.ai.IAiAppHostConnection
import org.orynnx.outerview.core.ai.IAiAppHostService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Signature-protected transport; native theme operations remain inside the theme UID. */
class AiAppHostHook : YukiBaseHooker() {
    companion object { private const val TAG = "OuterView-AiApp" }

    @Volatile private var context: Context? = null
    @Volatile private var bridge: ThemeAiBridge? = null
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "OuterView-AiApp") }
    private var registered = false

    override fun onHook() {
        loadApp(AiAppHostContract.HOST_PACKAGE) {
            runCatching {
                val store = "com.rearScreen.subscreen.RearAppStoreController".toClass()
                val base = store.superclass!!
                fun unique(type: Class<*>, predicate: (java.lang.reflect.Method) -> Boolean) =
                    uniqueInstanceMethod(type, predicate)
                val titleGetter = unique(base) {
                    it.parameterCount == 0 && it.returnType == String::class.java &&
                        java.lang.reflect.Modifier.isFinal(it.modifiers) &&
                        java.lang.reflect.Modifier.isPublic(it.modifiers)
                }
                base.resolve().firstMethod { name = titleGetter.name; parameterCount = 0 }.hook().after {
                    if (RearScreenManagerEntry.isEntry(instance)) result = "OuterView 智能应用"
                }
                val keyGetter = unique(store) { it.parameterCount == 0 && it.returnType == String::class.java }
                store.resolve().firstMethod { name = keyGetter.name; parameterCount = 0 }.hook().after {
                    if (RearScreenManagerEntry.isEntry(instance)) result = RearScreenManagerEntry.KEY
                }
                val intentGetter = unique(store) { it.parameterCount == 0 && it.returnType == Intent::class.java }
                store.resolve().firstMethod { name = intentGetter.name; parameterCount = 0 }.hook().after {
                    if (RearScreenManagerEntry.isEntry(instance)) result = RearScreenManagerEntry.intent()
                }
                val click = unique(store) {
                    it.parameterCount == 2 && it.parameterTypes[0] == android.app.Activity::class.java &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                store.resolve().firstMethod { name = click.name; parameterCount = 2 }.hook().before {
                    if (RearScreenManagerEntry.isEntry(instance)) {
                        RearScreenManagerEntry.open(args[0] as android.app.Activity)
                        result = false
                    }
                }
                val config = "com.rearScreen.subscreen.EntryConfig\$Companion".toClass()
                val entries = unique(config) {
                    it.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                        java.util.List::class.java.isAssignableFrom(it.returnType)
                }
                config.resolve().firstMethod { name = entries.name; parameterCount = 1 }.hook().after {
                    runCatching {
                        result = RearScreenManagerEntry.insert(result as List<*>, args[0] as Context, store)
                    }.onFailure { YLog.error("[$TAG] native settings entry unavailable", it) }
                }
            }.onFailure { YLog.error("[$TAG] native settings entry unsupported", it) }
            "com.android.thememanager.ThemeApplication".toClass().resolve().firstMethod {
                name = "onCreate"; parameterCount = 0
            }.hook().after {
                if (Application.getProcessName() != AiAppHostContract.HOST_PACKAGE) return@after
                val host = (instance as? Context)?.applicationContext ?: return@after
                context = host
                bridge = ThemeAiBridge(host, instance!!.javaClass.classLoader!!)
                registerReceiver(host)
            }
        }
    }

    private fun registerReceiver(host: Context) {
        if (registered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AiAppHostContract.ACTION_REQUEST_SERVICE) return
                val callback = intent.getBundleExtra(AiAppHostContract.EXTRA_BUNDLE)
                    ?.getBinder(AiAppHostContract.EXTRA_CALLBACK) ?: return
                runCatching { IAiAppHostConnection.Stub.asInterface(callback).onServiceConnected(service) }
                    .onFailure { YLog.error("[$TAG] service callback failed", it) }
            }
        }
        host.registerReceiver(
            receiver, IntentFilter(AiAppHostContract.ACTION_REQUEST_SERVICE),
            AiAppHostContract.ACCESS_HOST_API_PERMISSION, null, Context.RECEIVER_EXPORTED,
        )
        registered = true
        YLog.info("[$TAG] theme host service registered")
    }

    private val service = object : IAiAppHostService.Stub() {
        override fun getCapabilities(): Bundle {
            enforceCaller()
            return execute {
                hostBridge().checkCompatibility()
                response(true, "主题智能应用接口就绪").apply {
                    putBoolean("ready", true)
                    putInt("apiVersion", AiAppHostContract.API_VERSION)
                    putString("providerPackage", AiAppHostContract.PROVIDER_PACKAGE)
                }
            }.apply {
                putInt("apiVersion", AiAppHostContract.API_VERSION)
                putString("providerPackage", AiAppHostContract.PROVIDER_PACKAGE)
            }
        }

        override fun listCards(): Bundle {
            enforceCaller()
            return execute { hostBridge().listCards() }
        }

        override fun importCard(packageFd: ParcelFileDescriptor?, displayName: String?): Bundle {
            enforceCaller()
            val original = packageFd ?: return response(false, "没有收到智能应用包")
            // Own a duplicate until the worker finishes, including when the Binder caller times out.
            val owned = try { ParcelFileDescriptor.dup(original.fileDescriptor) }
            catch (failure: Throwable) { return response(false, failure.message ?: "读取应用包失败") }
            finally { runCatching { original.close() } }
            return execute(onRejected = { owned.close() }) {
                owned.use { hostBridge().importCard(it, displayName) }
            }
        }

        override fun removeCard(id: String?): Bundle {
            enforceCaller()
            return execute { hostBridge().removeCard(id.orEmpty()) }
        }

        override fun restoreCard(id: String?): Bundle {
            enforceCaller()
            return execute { hostBridge().restoreCard(id.orEmpty()) }
        }

        override fun openSystemManager(): Bundle {
            enforceCaller()
            val host = context ?: return response(false, "主题宿主尚未就绪")
            return execute {
                // Starting as the theme UID satisfies its internal activity permission.
                val intent = Intent().setClassName(
                    AiAppHostContract.HOST_PACKAGE,
                    "com.rearScreen.aiapp.activity.RearScreenAiAppListActivity",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                host.startActivity(intent)
                response(true, "已打开系统智能应用管理")
            }
        }
    }

    private fun hostBridge(): ThemeAiBridge = bridge ?: error("主题宿主尚未就绪")

    private fun enforceCaller() {
        val host = context ?: throw SecurityException("theme host not ready")
        host.enforceCallingPermission(AiAppHostContract.ACCESS_HOST_API_PERMISSION, "OuterView API permission required")
        val callerPackages = host.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (AiAppHostContract.PROVIDER_PACKAGE !in callerPackages) throw SecurityException("unauthorized caller")
    }

    private fun execute(onRejected: () -> Unit = {}, action: () -> Bundle): Bundle {
        if (!busy.compareAndSet(false, true)) {
            runCatching(onRejected)
            return response(false, "上一次操作仍在主题中执行，请稍后刷新")
        }
        val identity = Binder.clearCallingIdentity()
        try {
            val job = try {
                worker.submit<Bundle> {
                    try { action() }
                    catch (failure: Throwable) {
                        YLog.error("[$TAG] host operation failed", failure)
                        response(false, failure.message ?: "主题智能应用操作失败")
                    } finally { busy.set(false) }
                }
            } catch (failure: Throwable) {
                busy.set(false)
                runCatching(onRejected)
                throw failure
            }
            return try { job.get(45, TimeUnit.SECONDS) }
            catch (_: TimeoutException) {
                // Do not cancel: cancellation while Room/native files are changing would split state.
                response(false, "操作仍在主题中执行，请稍后刷新；请勿重复导入").apply { putBoolean("pending", true) }
            }
        } catch (failure: Throwable) {
            YLog.error("[$TAG] dispatch failed", failure)
            return response(false, failure.message ?: "主题服务调用失败")
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun response(success: Boolean, message: String) = Bundle().apply {
        putBoolean("success", success)
        putString("message", message)
    }
}
