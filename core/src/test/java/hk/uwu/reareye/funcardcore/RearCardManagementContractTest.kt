package hk.uwu.reareye.funcardcore

import hk.uwu.reareye.funcardcore.hostapi.FunCardHostContract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearCardManagementContractTest {
    @Test
    fun exposesOnlyHighLevelLifecycleEndpoints() {
        val methods = RearCardManagementEndpoints::class.java.methods.map { it.name }.toSet()

        assertEquals(3, RearCardManager.API_VERSION)
        assertEquals(3, FunCardHostContract.API_VERSION)
        assertEquals("org.orynnx.outerview", FunCardHostContract.PROVIDER_PACKAGE)
        assertEquals(
            "org.orynnx.outerview.action.REQUEST_FUN_CARD_HOST_SERVICE",
            FunCardHostContract.ACTION_REQUEST_SERVICE,
        )
        assertTrue("importAndInstall" in methods)
        assertTrue("replaceAndInstall" in methods)
        assertTrue("retryInstall" in methods)
        assertTrue("setVisible" in methods)
        assertTrue("deleteCard" in methods)
        assertTrue("deleteAllCards" in methods)
        assertFalse("install" in methods)
        assertFalse("show" in methods)
        assertFalse("hide" in methods)
        assertFalse("uninstall" in methods)
        assertFalse("setSystemProbe" in methods)
        assertFalse("hasNotificationPermission" in methods)
    }
}
