package hk.uwu.reareye.funcardcore.wallpaperapi

object RearWallpaperHostContract {
    const val API_VERSION = 2
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
    }
}
