package com.qq.closie

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.privacy.ModuleAccessRepository
import com.qq.closie.life.permissions.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModuleAccessRepositoryTest {
    @After fun resetGate() { com.qq.closie.data.backup.RestoreStartupGate.resetForTesting() }
    @Test fun savedPolicySurvivesRecreationButItsRestoreEpochIsNeverImplicitlyRenewed() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.qq.closie.data.backup.RestoreStartupGate.resetForTesting()
        com.qq.closie.data.backup.RestoreStartupGate.markReady()
        var epoch = 41L
        val repository = ModuleAccessRepository(context) { epoch }
        repository.owner.authorize(ReadModule.FINANCE, ModuleReadMode.ALLOW, ModuleGrantDuration.LONG_TERM, epoch)
        val recreated = ModuleAccessRepository(context) { epoch }
        assertEquals(ModuleReadMode.ALLOW, recreated.rules.first().getValue(ReadModule.FINANCE).mode)
        val request = ModuleReadRequest(ReadModule.FINANCE, "offline", "request", epoch,
            restoreGeneration = com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration)
        val permit = recreated.owner.reserve(request)
        recreated.owner.validate(permit); recreated.owner.finish(permit)
        epoch++
        assertTrue(runCatching { recreated.owner.reserve(request.copy(restoreEpoch = epoch)) }.exceptionOrNull() is ModuleConsentRequired)
        assertEquals(41L, recreated.rules.first().getValue(ReadModule.FINANCE).epoch)
        recreated.owner.authorize(ReadModule.FINANCE, ModuleReadMode.ASK, ModuleGrantDuration.LONG_TERM, epoch)
    }
}
