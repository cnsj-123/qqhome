package com.xiaoming.closie

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.xiaoming.closie.data.appearance.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearanceRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `preset and custom survive a new repository and original preserves custom`() = runBlocking {
        val file = File(temporary.root, "appearance.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = AppearanceRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file }, firstScope)
        val custom = CustomTheme("#ab12cd", "#f0eadc", "#98ab76")
        try {
            assertEquals(ThemePreset.ORIGINAL.id, first.read().selectedThemeId)
            first.saveCustom(custom)
            assertEquals(custom.normalized(), first.read().custom)
            first.selectPreset(ThemePreset.WATER_LILY)
            assertEquals(ThemePreset.WATER_LILY.id, first.read().selectedThemeId)
        } finally { firstJob.cancelAndJoin() }

        // A second DataStore reads the file from disk, rather than the first StateFlow's cache.
        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        val second = AppearanceRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file }, secondScope)
        try {
            val reloaded = second.read()
            assertEquals(ThemePreset.WATER_LILY.id, reloaded.selectedThemeId)
            assertEquals(custom.normalized(), reloaded.custom)
            second.selectSavedCustom()
            assertEquals(AppearanceSettings.CUSTOM_ID, second.read().selectedThemeId)
            second.selectPreset(ThemePreset.ORIGINAL)
            assertEquals(ThemePreset.ORIGINAL.id, second.read().selectedThemeId)
            assertEquals(custom.normalized(), second.read().custom)
            second.selectSavedCustom()
            assertEquals(custom.normalized(), second.read().selectedColors())
        } finally { secondJob.cancelAndJoin() }
    }

    @Test
    fun `invalid save leaves the previously persisted palette untouched`() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(temporary.root, "invalid.preferences_pb") }
        val repository = AppearanceRepository(store, scope)
        try {
            repository.saveCustom(CustomTheme("#765432", "#EFEFEF", "#BBAADD"))
            val before = repository.read()
            assertTrue(runCatching { repository.saveCustom(CustomTheme("oops", "#FFFFFF", "#FFFFFF")) }.isFailure)
            assertEquals(before, repository.read())
            store.edit { it[AppearanceCodec.customPaper] = "broken" }
            val repaired = repository.read()
            assertEquals(ThemePreset.ORIGINAL.id, repaired.selectedThemeId)
            assertNull(repaired.custom)
        } finally { job.cancelAndJoin() }
    }
}
