package hk.uwu.reareye.funcardcore.hostapi

object FunCardHostContract {
    const val API_VERSION = 5
    const val PROVIDER_PACKAGE = "org.orynnx.outerview"
    const val HOST_PACKAGE = "com.xiaomi.subscreencenter"
    const val ACTION_REQUEST_SERVICE =
        "org.orynnx.outerview.action.REQUEST_FUN_CARD_HOST_SERVICE"
    const val EXTRA_BUNDLE = "hostApiBundle"
    const val EXTRA_CALLBACK = "callback"
    const val ACTION_CARD_RUNTIME_EVENT =
        "org.orynnx.outerview.action.CARD_RUNTIME_EVENT"

    object Keys {
        const val SUCCESS = "success"
        const val MESSAGE = "message"
        const val ERROR_CODE = "errorCode"
        const val API_VERSION = "apiVersion"
        const val PROVIDER_PACKAGE = "providerPackage"
        const val PROVIDER_INSTANCE_ID = "providerInstanceId"
        const val HOST_VERSION = "hostVersion"
        const val HOOK_READY = "hookReady"
        const val MANAGER_CAPTURED = "managerCaptured"
        const val CARD_ID = "cardId"
        const val BUSINESS = "business"
        const val DISPLAY_NAME = "displayName"
        const val TEMPLATE_PATH = "templatePath"
        const val TEMPLATE_SHA256 = "templateSha256"
        const val NOTIFICATION_ID = "notificationId"
        const val COMMAND_ID = "commandId"
        const val REAR_PARAM = "rearParam"
        const val FOCUS_PARAM = "focusParam"
        const val RUNTIME_ACTIVATED = "runtimeActivated"
        const val TEMPLATE_READABLE = "templateReadable"
        const val HOST_REGISTRY_CONTAINS = "hostRegistryContains"
        const val NOTIFICATION_SEEN = "notificationSeen"
        const val MANAGER_LIST_CONTAINS = "managerListContains"
        const val LIVE_WIDGET_CONTAINS = "liveWidgetContains"
        const val LOAD_ATTEMPTED = "loadAttempted"
        const val LOAD_SUCCEEDED = "loadSucceeded"
        const val SYSTEM_PERSISTENCE_CONTAINS = "systemPersistenceContains"
        const val LAST_COMMAND_ID = "lastCommandId"
        const val LAST_EVENT_AT = "lastEventAt"
        const val LAST_ERROR = "lastError"
        const val LEGACY_CONFLICTS = "legacyConflicts"
        const val ITEMS = "items"
        const val ACTIVE = "active"
        const val SYSTEM_TEMPLATE = "systemTemplate"
        const val SYSTEM_PATH_NAME = "systemPathName"
        const val RUNTIME_EVENT = "runtimeEvent"
        const val CLEANUP_PENDING = "cleanupPending"
    }
}
