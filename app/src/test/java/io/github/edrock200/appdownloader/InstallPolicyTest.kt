package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.InstallPolicy
import org.junit.Assert.*
import org.junit.Test

class InstallPolicyTest {
    @Test fun shieldCanUseShizukuWithoutUnknownSourcesPermission() {
        assertTrue(InstallPolicy.canInstallInBackground(InstallMethod.SHIZUKU, 30, false, true))
        assertFalse(InstallPolicy.canInstallInBackground(InstallMethod.STANDARD, 30, true, true))
    }

    @Test fun stoppedOrUnauthorizedShizukuNeverFallsBackToAnotherInstaller() {
        for (sdk in listOf(23, 30, 31, 35)) {
            assertFalse(InstallPolicy.canInstallInBackground(InstallMethod.SHIZUKU, sdk, true, false))
        }
    }

    @Test fun standardInstallerKeepsItsExistingAndroid12AndPermissionRequirements() {
        assertTrue(InstallPolicy.canInstallInBackground(InstallMethod.STANDARD, 31, true, false))
        assertFalse(InstallPolicy.canInstallInBackground(InstallMethod.STANDARD, 31, false, true))
    }

    @Test fun android51CannotUseShizukuEvenWithStaleReadiness() {
        assertFalse(InstallPolicy.canInstallInBackground(InstallMethod.SHIZUKU, 22, true, true))
    }
}
