package org.orynnx.outerview.hook

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed(entryClassName = "FunCardHookEntrance")
class FunCardHookEntry : IYukiHookXposedInit {
    override fun onInit() = configs {
        debugLog {
            tag = "OuterView-Hook"
            isEnable = true
            elements(TAG, PRIORITY, PACKAGE_NAME, USER_ID)
        }
    }

    override fun onHook() {
        encase(CustomRearCardHook())
    }

    companion object {
        init {
            System.loadLibrary("dexkit")
        }
    }
}
