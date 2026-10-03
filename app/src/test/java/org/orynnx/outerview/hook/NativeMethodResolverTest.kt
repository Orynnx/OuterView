package org.orynnx.outerview.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMethodResolverTest {
    class WithAccessor {
        fun target(): String = "instance"
        companion object {
            @JvmStatic fun accessor(): String = "static"
        }
    }

    class Ambiguous {
        fun first(): String = "first"
        fun second(): String = "second"
    }

    @Test fun ignoresStaticAccessorsWithTheSameSignature() {
        val method = uniqueInstanceMethod(WithAccessor::class.java) {
            it.parameterCount == 0 && it.returnType == String::class.java
        }
        assertEquals("target", method.name)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAmbiguousInstanceMethods() {
        uniqueInstanceMethod(Ambiguous::class.java) {
            it.parameterCount == 0 && it.returnType == String::class.java
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingMethods() {
        uniqueInstanceMethod(WithAccessor::class.java) { it.returnType == Long::class.javaPrimitiveType }
    }
}
