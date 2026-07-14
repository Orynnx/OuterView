package org.orynnx.outerview

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateManagerTest {
    @Test fun comparesSemanticVersionNumbers() {
        assertEquals(1, AppUpdateManager.compareVersions("2.10.0", "2.3.0"))
        assertEquals(0, AppUpdateManager.compareVersions("v2.3", "2.3.0"))
        assertEquals(-1, AppUpdateManager.compareVersions("2.3.0", "2.3.1"))
    }
}
