package org.orynnx.outerview.core.ai

object AiAppHostContract {
    const val API_VERSION = 1
    const val PROVIDER_PACKAGE = "org.orynnx.outerview"
    const val HOST_PACKAGE = "com.android.thememanager"
    const val ACCESS_HOST_API_PERMISSION = "org.orynnx.outerview.permission.ACCESS_HOST_API"
    const val ACTION_REQUEST_SERVICE = "org.orynnx.outerview.action.REQUEST_AI_APP_HOST_SERVICE"
    const val EXTRA_BUNDLE = "hostApiBundle"
    const val EXTRA_CALLBACK = "callback"

    object Keys {
        const val API_VERSION = "apiVersion"
        const val PROVIDER_PACKAGE = "providerPackage"
        const val READY = "ready"
        const val SUCCESS = "success"
        const val MESSAGE = "message"
        const val ITEMS = "items"
        const val ID = "id"
        const val NAME = "name"
        const val RESOURCE_PATH = "resourcePath"
        const val MANAGED = "managed"
        const val REGISTERED = "registered"
        const val PENDING = "pending"
    }
}
