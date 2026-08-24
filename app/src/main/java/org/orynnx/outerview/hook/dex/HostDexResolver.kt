package org.orynnx.outerview.hook.dex

import android.util.AtomicFile
import android.util.Log
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

internal data class HostMethodRef(
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
)

internal data class HostMethodQuery(
    val owner: String? = null,
    val packagePrefix: String? = null,
    val parameterTypes: List<String>? = null,
    val parameterCount: Int? = null,
    val returnType: String? = null,
    val requiredModifiers: Int = 0,
    val strings: Set<String> = emptySet(),
) {
    internal fun fingerprint(): String = listOf(
        owner.orEmpty(),
        packagePrefix.orEmpty(),
        parameterTypes?.joinToString(",").orEmpty(),
        parameterCount?.toString().orEmpty(),
        returnType.orEmpty(),
        requiredModifiers.toString(),
        strings.sorted().joinToString("\u0001"),
    ).joinToString("\u0002").sha256()
}

internal data class HostClassQuery(
    val packagePrefix: String? = null,
    val strings: Set<String> = emptySet(),
) {
    internal fun fingerprint(): String = listOf(
        packagePrefix.orEmpty(),
        strings.sorted().joinToString("\u0001"),
    ).joinToString("\u0002").sha256()
}

internal data class HostFieldQuery(
    val owner: String? = null,
    val packagePrefix: String? = null,
    val type: String? = null,
    val readBy: HostMethodRef? = null,
) {
    internal fun fingerprint(): String = listOf(
        owner.orEmpty(),
        packagePrefix.orEmpty(),
        type.orEmpty(),
        readBy?.className.orEmpty(),
        readBy?.methodName.orEmpty(),
        readBy?.parameterTypes?.joinToString(",").orEmpty(),
    ).joinToString("\u0002").sha256()
}

/**
 * Small, project-owned DEX index used only for locating the Xiaomi host entry points that
 * OuterView needs. It deliberately exposes queries instead of a general deobfuscation API.
 */
internal class HostDexResolver private constructor(
    private val sourceApk: File,
    cacheFile: File,
    versionCode: Long,
) {
    private val lock = Any()
    private val sourceIdentity = buildSourceIdentity(sourceApk, versionCode)
    private val pointCache = HookPointCache(cacheFile, sourceIdentity)
    private var loadedIndex: HostDexIndex? = null

    fun method(cacheKey: String, query: HostMethodQuery): HostMethodRef? = synchronized(lock) {
        val fingerprint = query.fingerprint()
        pointCache.method(cacheKey, fingerprint)?.let {
            Log.d(TAG, "cache method $cacheKey -> ${it.className}#${it.methodName}")
            return@synchronized it
        }
        val candidates = index().methods.filter { it.matches(query) }
        val match = candidates.singleOrNull() ?: run {
            logCandidates(cacheKey, candidates.map { "${it.owner}#${it.name}" })
            return@synchronized null
        }
        HostMethodRef(match.owner, match.name, match.parameterTypes).also {
            pointCache.putMethod(cacheKey, fingerprint, it)
            Log.i(TAG, "resolved method $cacheKey -> ${it.className}#${it.methodName}")
        }
    }

    fun className(cacheKey: String, query: HostClassQuery): String? = synchronized(lock) {
        val fingerprint = query.fingerprint()
        pointCache.className(cacheKey, fingerprint)?.let {
            Log.d(TAG, "cache class $cacheKey -> $it")
            return@synchronized it
        }
        val candidates = index().classes.filter { it.matches(query) }
        val match = candidates.singleOrNull() ?: run {
            logCandidates(cacheKey, candidates.map { it.name })
            return@synchronized null
        }
        match.name.also { pointCache.putClass(cacheKey, fingerprint, it) }
    }

    fun fieldName(cacheKey: String, query: HostFieldQuery): String? = synchronized(lock) {
        val fingerprint = query.fingerprint()
        pointCache.field(cacheKey, fingerprint)?.let {
            Log.d(TAG, "cache field $cacheKey -> ${it.owner}#${it.name}")
            return@synchronized it.name
        }
        val candidates = index().fields.filter { it.matches(query, index().methods) }
        val match = candidates.singleOrNull() ?: run {
            logCandidates(cacheKey, candidates.map { "${it.owner}#${it.name}" })
            if (query.readBy != null) {
                val reader = index().methods.singleOrNull {
                    it.owner == query.readBy.className &&
                        it.name == query.readBy.methodName &&
                        it.parameterTypes == query.readBy.parameterTypes
                }
                Log.e(TAG, "lookup $cacheKey reader fields: ${reader?.fieldReads?.take(12).orEmpty()}")
            }
            return@synchronized null
        }
        pointCache.putField(cacheKey, fingerprint, match)
        Log.i(TAG, "resolved field $cacheKey -> ${match.owner}#${match.name}")
        match.name
    }

    private fun index(): HostDexIndex = loadedIndex ?: HostDexIndex.read(sourceApk).also {
        loadedIndex = it
    }

    private fun logCandidates(cacheKey: String, candidates: List<String>) {
        Log.e(TAG, "lookup $cacheKey expected one candidate, found ${candidates.size}: ${candidates.take(8)}")
    }

    companion object {
        private const val TAG = "OuterView-Dex"
        private val instances = ConcurrentHashMap<String, HostDexResolver>()

        fun open(sourceDir: String, dataDir: String, versionCode: Long): HostDexResolver {
            val key = "$sourceDir\u0000$versionCode"
            return instances.computeIfAbsent(key) {
                val files = File(dataDir, "files").apply { mkdirs() }
                HostDexResolver(
                    sourceApk = File(sourceDir),
                    cacheFile = File(files, "outerview_hook_points.json"),
                    versionCode = versionCode,
                )
            }
        }
    }
}

private data class DexFieldRecord(
    val owner: String,
    val name: String,
    val type: String,
) {
    fun matches(query: HostFieldQuery, methods: List<DexMethodRecord>): Boolean {
        if (query.owner != null && owner != query.owner) return false
        if (query.packagePrefix != null && !owner.startsWith(query.packagePrefix)) return false
        if (query.type != null && type != query.type) return false
        val reader = query.readBy ?: return true
        val method = methods.singleOrNull {
            it.owner == reader.className &&
                it.name == reader.methodName &&
                it.parameterTypes == reader.parameterTypes
        }
            ?: return false
        return this in method.fieldReads
    }
}

private data class DexMethodRecord(
    val owner: String,
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val accessFlags: Int,
    val strings: Set<String>,
    val fieldReads: Set<DexFieldRecord>,
) {
    fun matches(query: HostMethodQuery): Boolean {
        if (query.owner != null && owner != query.owner) return false
        if (query.packagePrefix != null && !owner.startsWith(query.packagePrefix)) return false
        if (query.parameterTypes != null && parameterTypes != query.parameterTypes) return false
        if (query.parameterCount != null && parameterTypes.size != query.parameterCount) return false
        if (query.returnType != null && returnType != query.returnType) return false
        if (query.requiredModifiers != 0 && accessFlags and query.requiredModifiers != query.requiredModifiers) {
            return false
        }
        return strings.containsAll(query.strings)
    }
}

private data class DexClassRecord(
    val name: String,
    val strings: Set<String>,
) {
    fun matches(query: HostClassQuery): Boolean {
        if (query.packagePrefix != null && !name.startsWith(query.packagePrefix)) return false
        return strings.containsAll(query.strings)
    }
}

private data class HostDexIndex(
    val classes: List<DexClassRecord>,
    val methods: List<DexMethodRecord>,
    val fields: List<DexFieldRecord>,
) {
    companion object {
        fun read(sourceApk: File): HostDexIndex {
            require(sourceApk.isFile) { "Host APK not found: $sourceApk" }
            val startedAt = System.currentTimeMillis()
            val classes = ArrayList<DexClassRecord>()
            val methods = ArrayList<DexMethodRecord>()
            val fields = ArrayList<DexFieldRecord>()

            ZipFile(sourceApk).use { apk ->
                val dexEntries = apk.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(DEX_ENTRY) }
                    .sortedBy { it.name }
                    .toList()
                require(dexEntries.isNotEmpty()) { "No classes*.dex in $sourceApk" }

                dexEntries.forEach { entry ->
                    val dex = apk.getInputStream(entry).use { input ->
                        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(input))
                    }
                    dex.classes.forEach { classDef ->
                        val owner = descriptorToJavaName(classDef.type)
                        val classStrings = LinkedHashSet<String>()
                        classDef.fields.forEach { field ->
                            fields += DexFieldRecord(
                                owner = owner,
                                name = field.name,
                                type = descriptorToJavaName(field.type),
                            )
                        }
                        classDef.methods.forEach { method ->
                            val methodStrings = LinkedHashSet<String>()
                            val fieldReads = LinkedHashSet<DexFieldRecord>()
                            method.implementation?.instructions?.forEach { instruction ->
                                val reference = runCatching {
                                    (instruction as? ReferenceInstruction)?.reference
                                }.getOrNull()
                                when (reference) {
                                    is StringReference -> methodStrings += reference.string
                                    is FieldReference -> if (instruction.opcode.isFieldRead()) {
                                        fieldReads += DexFieldRecord(
                                            owner = descriptorToJavaName(reference.definingClass),
                                            name = reference.name,
                                            type = descriptorToJavaName(reference.type),
                                        )
                                    }
                                }
                            }
                            classStrings += methodStrings
                            methods += DexMethodRecord(
                                owner = owner,
                                name = method.name,
                                parameterTypes = method.parameterTypes.map { descriptorToJavaName(it.toString()) },
                                returnType = descriptorToJavaName(method.returnType),
                                accessFlags = method.accessFlags,
                                strings = methodStrings,
                                fieldReads = fieldReads,
                            )
                        }
                        classes += DexClassRecord(owner, classStrings)
                    }
                }
            }
            return HostDexIndex(classes, methods, fields).also {
                Log.i(
                    "OuterView-Dex",
                    "indexed ${sourceApk.name}: classes=${classes.size} methods=${methods.size} " +
                        "fields=${fields.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
                )
            }
        }

        private val DEX_ENTRY = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
    }
}

private class HookPointCache(
    cacheFile: File,
    private val sourceIdentity: String,
) {
    private val file = AtomicFile(cacheFile)
    private var root: JSONObject = readRoot()

    fun method(key: String, fingerprint: String): HostMethodRef? {
        val entry = entry(key, fingerprint, "method") ?: return null
        return HostMethodRef(
            className = entry.getString("owner"),
            methodName = entry.getString("name"),
            parameterTypes = entry.optString("parameters")
                .takeIf(String::isNotEmpty)
                ?.split(PARAMETER_SEPARATOR)
                .orEmpty(),
        )
    }

    fun className(key: String, fingerprint: String): String? =
        entry(key, fingerprint, "class")?.getString("owner")

    fun field(key: String, fingerprint: String): DexFieldRecord? {
        val entry = entry(key, fingerprint, "field") ?: return null
        return DexFieldRecord(
            owner = entry.getString("owner"),
            name = entry.getString("name"),
            type = entry.getString("type"),
        )
    }

    fun putMethod(key: String, fingerprint: String, value: HostMethodRef) {
        put(
            key,
            fingerprint,
            "method",
            value.className,
            value.methodName,
            null,
            value.parameterTypes.joinToString(PARAMETER_SEPARATOR),
        )
    }

    fun putClass(key: String, fingerprint: String, value: String) {
        put(key, fingerprint, "class", value, null, null, null)
    }

    fun putField(key: String, fingerprint: String, value: DexFieldRecord) {
        put(key, fingerprint, "field", value.owner, value.name, value.type, null)
    }

    private fun entry(key: String, fingerprint: String, kind: String): JSONObject? {
        val entry = root.optJSONObject("entries")?.optJSONObject(key) ?: return null
        if (entry.optString("fingerprint") != fingerprint || entry.optString("kind") != kind) return null
        return entry
    }

    private fun put(
        key: String,
        fingerprint: String,
        kind: String,
        owner: String,
        name: String?,
        type: String?,
        parameters: String?,
    ) {
        val entries = root.optJSONObject("entries") ?: JSONObject().also { root.put("entries", it) }
        entries.put(key, JSONObject().apply {
            put("fingerprint", fingerprint)
            put("kind", kind)
            put("owner", owner)
            name?.let { put("name", it) }
            type?.let { put("type", it) }
            parameters?.let { put("parameters", it) }
        })
        writeRoot()
    }

    private fun readRoot(): JSONObject {
        val loaded = runCatching {
            file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }.getOrNull()
        return if (loaded?.optString("sourceIdentity") == sourceIdentity) {
            loaded
        } else {
            JSONObject().apply {
                put("schema", CACHE_SCHEMA)
                put("sourceIdentity", sourceIdentity)
                put("entries", JSONObject())
            }
        }
    }

    private fun writeRoot() {
        val output = file.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val CACHE_SCHEMA = 1
        private const val PARAMETER_SEPARATOR = "\u0001"
    }
}

private fun Opcode.isFieldRead(): Boolean = when (this) {
    Opcode.IGET,
    Opcode.IGET_BOOLEAN,
    Opcode.IGET_BYTE,
    Opcode.IGET_CHAR,
    Opcode.IGET_OBJECT,
    Opcode.IGET_OBJECT_VOLATILE,
    Opcode.IGET_QUICK,
    Opcode.IGET_OBJECT_QUICK,
    Opcode.IGET_SHORT,
    Opcode.IGET_VOLATILE,
    Opcode.IGET_WIDE,
    Opcode.IGET_WIDE_QUICK,
    Opcode.IGET_WIDE_VOLATILE,
    Opcode.SGET,
    Opcode.SGET_BOOLEAN,
    Opcode.SGET_BYTE,
    Opcode.SGET_CHAR,
    Opcode.SGET_OBJECT,
    Opcode.SGET_OBJECT_VOLATILE,
    Opcode.SGET_SHORT,
    Opcode.SGET_VOLATILE,
    Opcode.SGET_WIDE,
    Opcode.SGET_WIDE_VOLATILE -> true
    else -> false
}

private fun buildSourceIdentity(sourceApk: File, versionCode: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    sourceApk.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return "$versionCode:${digest.digest().joinToString("") { "%02x".format(it) }}"
}

private fun descriptorToJavaName(descriptor: String): String {
    var dimensions = 0
    while (dimensions < descriptor.length && descriptor[dimensions] == '[') dimensions++
    val base = descriptor.substring(dimensions)
    val name = when (base) {
        "V" -> "void"
        "Z" -> "boolean"
        "B" -> "byte"
        "S" -> "short"
        "C" -> "char"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        else -> base.removePrefix("L").removeSuffix(";").replace('/', '.')
    }
    return name + "[]".repeat(dimensions)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun hostRequiredModifiers(vararg modifiers: Int): Int = modifiers.fold(0, Int::or)

internal val PUBLIC_STATIC: Int = hostRequiredModifiers(Modifier.PUBLIC, Modifier.STATIC)
