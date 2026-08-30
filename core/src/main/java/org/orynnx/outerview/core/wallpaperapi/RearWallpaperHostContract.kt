package org.orynnx.outerview.core.wallpaperapi

object RearWallpaperHostContract {
    const val API_VERSION = 3
    const val PROVIDER_PACKAGE = "org.orynnx.outerview"
    const val HOST_PACKAGE = "com.xiaomi.subscreencenter"
    const val ACCESS_HOST_API_PERMISSION =
        "org.orynnx.outerview.permission.ACCESS_HOST_API"
    const val ACTION_REQUEST_SERVICE = "org.orynnx.outerview.action.REQUEST_REAR_WALLPAPER_HOST_SERVICE"
    const val EXTRA_BUNDLE = "wallpaperHostApiBundle"
    const val EXTRA_CALLBACK = "callback"

    object Keys {
        const val SUCCESS = "success"
        const val MESSAGE = "message"
        const val ERROR_CODE = "errorCode"
        const val ITEMS = "items"
        const val WALLPAPER_ID = "wallpaperId"
        const val RES_ID = "resId"
        const val NAME = "name"
        const val PATH = "path"
        const val CURRENT = "current"
        const val MANAGED = "managed"
        const val API_VERSION = "apiVersion"
        const val HOOK_READY = "hookReady"
        const val PANEL_READY = "panelReady"
        const val PROVIDER_PACKAGE = "providerPackage"
        const val PROVIDER_INSTANCE_ID = "providerInstanceId"
    }
}
