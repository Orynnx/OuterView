package org.orynnx.outerview.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Kotlin/R8 static accessors can duplicate an instance method's parameters and return type. */
internal fun uniqueInstanceMethod(type: Class<*>, predicate: (Method) -> Boolean): Method {
    val candidates = type.declaredMethods.filter {
        !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.isBridge && predicate(it)
    }
    check(candidates.size == 1) {
        "Expected one native instance method in ${type.name}, found ${candidates.size}: " +
            candidates.joinToString { it.toGenericString() }
    }
    return candidates.single()
}
