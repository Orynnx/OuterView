package org.orynnx.outerview

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test fun comparesStrictStableVersionNumbers() {
        assertEquals(1, AppUpdateManager.compareVersions("2.10.0", "2.3.0"))
        assertEquals(0, AppUpdateManager.compareVersions("v2.3.0", "2.3.0"))
        assertEquals(-1, AppUpdateManager.compareVersions("2.3.0", "2.3.1"))
    }

    @Test fun rejectsPrereleasePathsControlCharactersAndNonCanonicalNumbers() {
        listOf(
            "2.4",
            "2.4.0-beta.1",
            "2.4.0/evil",
            "2.4.0\\evil",
            "2.4.0\n",
            " 2.4.0",
            "02.4.0",
            "V2.4.0",
        ).forEach { version ->
            assertNull(version, AppUpdateManager.normalizeStableVersion(version))
        }
        assertEquals("2.4.0", AppUpdateManager.normalizeStableVersion("v2.4.0"))
    }

    @Test fun selectsOnlyOneExactlyNamedReleaseAsset() {
        val assets = listOf(
            "app-release.apk" to "https://example.invalid/app-release.apk",
            "OuterView-2.4.0.apk.sig" to "https://example.invalid/signature",
            "OuterView-2.4.0.apk" to
                "https://github.com/Orynnx/OuterView/releases/download/v2.4.0/OuterView-2.4.0.apk",
        )
        assertEquals(
            assets.last().second,
            AppUpdateManager.selectExpectedAssetUrl("2.4.0", assets),
        )
        assertNull(
            AppUpdateManager.selectExpectedAssetUrl(
                "2.4.0",
                assets + assets.last(),
            ),
        )
    }

    @Test fun acceptsOnlyExpectedGithubReleaseAndApkUrls() {
        assertTrue(
            AppUpdateManager.isExpectedReleaseUrl(
                "https://github.com/Orynnx/OuterView/releases/tag/v2.4.0",
                "2.4.0",
            ),
        )
        assertTrue(
            AppUpdateManager.isExpectedApkUrl(
                "https://github.com/Orynnx/OuterView/releases/download/v2.4.0/OuterView-2.4.0.apk",
                "2.4.0",
            ),
        )
        listOf(
            "http://github.com/Orynnx/OuterView/releases/tag/v2.4.0",
            "https://github.com.evil.test/Orynnx/OuterView/releases/tag/v2.4.0",
            "https://user@github.com/Orynnx/OuterView/releases/tag/v2.4.0",
            "https://github.com/Orynnx/OuterView/releases/tag/v2.4.0?download=1",
            "https://github.com/Orynnx/OuterView/releases/tag/v2.4.0/../v2.5.0",
        ).forEach { url ->
            assertFalse(url, AppUpdateManager.isExpectedReleaseUrl(url, "2.4.0"))
        }
        listOf(
            "https://objects.githubusercontent.com/Orynnx/OuterView/releases/download/v2.4.0/OuterView-2.4.0.apk",
            "https://github.com/Orynnx/OuterView/releases/download/v2.4.0/app-release.apk",
            "https://github.com/Orynnx/OuterView/releases/download/v2.4.0/OuterView-2.4.0.apk#fragment",
            "https://github.com/Orynnx/OuterView/releases/download/v2.4.0/OuterView-2.4.0.apk%2f..%2fevil.apk",
        ).forEach { url ->
            assertFalse(url, AppUpdateManager.isExpectedApkUrl(url, "2.4.0"))
        }
    }

    @Test fun responseReaderEnforcesByteLimit() {
        assertEquals(
            "hello",
            AppUpdateManager.readUtf8Limited(ByteArrayInputStream("hello".toByteArray()), 5),
        )
        val error = assertThrows(AppUpdateException::class.java) {
            AppUpdateManager.readUtf8Limited(ByteArrayInputStream(ByteArray(6)), 5)
        }
        assertEquals(AppUpdateError.RESPONSE_TOO_LARGE, error.code)
    }

    @Test fun recognizesOnlyManagedUpdateApkNames() {
        assertTrue(AppUpdateManager.isManagedUpdateApkName("OuterView-2.4.0.apk"))
        assertFalse(AppUpdateManager.isManagedUpdateApkName("OuterView-v2.4.0.apk"))
        assertFalse(AppUpdateManager.isManagedUpdateApkName("OuterView-2.4.0.apk.part"))
        assertFalse(AppUpdateManager.isManagedUpdateApkName("../OuterView-2.4.0.apk"))
    }

    @Test fun signingLineageAcceptsForwardRotationButRejectsOldOrUnrelatedSigner() {
        assertTrue(
            AppUpdateManager.signingLineageAllowsUpdate(
                installedCurrent = setOf("current"),
                archiveCurrent = setOf("next"),
                archiveHistory = setOf("old", "current", "next"),
                installedHasMultipleSigners = false,
                archiveHasMultipleSigners = false,
            ),
        )
        assertFalse(
            AppUpdateManager.signingLineageAllowsUpdate(
                installedCurrent = setOf("current"),
                archiveCurrent = setOf("old"),
                archiveHistory = setOf("old"),
                installedHasMultipleSigners = false,
                archiveHasMultipleSigners = false,
            ),
        )
        assertFalse(
            AppUpdateManager.signingLineageAllowsUpdate(
                installedCurrent = emptySet(),
                archiveCurrent = setOf("current"),
                archiveHistory = setOf("current"),
                installedHasMultipleSigners = false,
                archiveHasMultipleSigners = false,
            ),
        )
    }

    @Test fun multipleSignerUpdatesRequireTheExactCurrentSignerSet() {
        assertTrue(
            AppUpdateManager.signingLineageAllowsUpdate(
                installedCurrent = setOf("a", "b"),
                archiveCurrent = setOf("b", "a"),
                archiveHistory = emptySet(),
                installedHasMultipleSigners = true,
                archiveHasMultipleSigners = true,
            ),
        )
        assertFalse(
            AppUpdateManager.signingLineageAllowsUpdate(
                installedCurrent = setOf("a", "b"),
                archiveCurrent = setOf("a"),
                archiveHistory = setOf("a", "b"),
                installedHasMultipleSigners = true,
                archiveHasMultipleSigners = false,
            ),
        )
    }
}
