package com.qq.closie

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import com.qq.closie.data.appearance.AppearanceRepository
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.data.appearance.CustomTheme
import com.qq.closie.data.appearance.ThemePreset
import com.qq.closie.ui.lifeos.settings.AppearanceViewModel
import com.qq.closie.ui.lifeos.settings.ThemeField
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main = UnconfinedTestDispatcher()
    private val custom = CustomTheme("#AB12CD", "#F0EADC", "#98AB76")
    private val saved = AppearanceSettings(ThemePreset.WATER_LILY.id, custom)

    @Before fun setMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test
    fun `restore previews original but cancel keeps the entire saved appearance`() = runTest {
        withEditor(saved) { repository, model ->
            model.restoreOriginal()
            assertEquals(ThemePreset.ORIGINAL.id, model.editor.value!!.selectedId)
            assertEquals(ThemePreset.ORIGINAL.id, model.appearance.first { it?.selectedThemeId == ThemePreset.ORIGINAL.id }!!.selectedThemeId)
            assertEquals(saved, repository.read())

            model.cancelAppearanceEdit()
            assertNull(model.editor.value)
            assertEquals(saved, model.appearance.first { it == saved })
            assertEquals(saved, repository.read())
        }
    }

    @Test
    fun `restore is persisted only after save and retains custom values`() = runTest {
        withEditor(saved) { repository, model ->
            model.restoreOriginal()
            assertEquals(saved, repository.read())
            saveAndAwait(model)
            val expected = saved.copy(selectedThemeId = ThemePreset.ORIGINAL.id)
            assertEquals(expected, repository.read())
            model.cancelAppearanceEdit()
            assertEquals(expected, model.appearance.first { it == expected })
        }
    }

    @Test
    fun `custom survives a preset save and is available in a new editor`() = runTest {
        withEditor(AppearanceSettings()) { repository, model ->
            model.editThemeField(ThemeField.ACCENT, custom.accent)
            model.editThemeField(ThemeField.PAPER, custom.paper)
            model.editThemeField(ThemeField.SECONDARY, custom.secondary)
            saveAndAwait(model)
            assertEquals(custom, repository.read().custom)
            repository.settings.first { it?.selectedThemeId == AppearanceSettings.CUSTOM_ID }

            model.previewPreset(ThemePreset.WATER_LILY)
            saveAndAwait(model)
            val preset = AppearanceSettings(ThemePreset.WATER_LILY.id, custom)
            assertEquals(preset, repository.read())
            repository.settings.first { it == preset }

            model.cancelAppearanceEdit()
            model.beginAppearanceEdit()
            model.previewSavedCustom()
            val draft = model.editor.value!!
            assertEquals(AppearanceSettings.CUSTOM_ID, draft.selectedId)
            assertEquals(custom, draft.preview.selectedColors())
            assertEquals(preset, repository.read())
            saveAndAwait(model)
            assertEquals(AppearanceSettings(AppearanceSettings.CUSTOM_ID, custom), repository.read())
        }
    }

    @Test
    fun `invalid hex retains last valid preview and cannot persist`() = runTest {
        withEditor(saved) { repository, model ->
            model.editThemeField(ThemeField.ACCENT, "#123456")
            val validPreview = model.editor.value!!.preview
            model.editThemeField(ThemeField.ACCENT, "not a hex")
            assertNotNull(model.editor.value!!.validationError)
            assertEquals(validPreview, model.editor.value!!.preview)
            model.saveAppearance()
            assertFalse(model.editor.value!!.isSaving)
            assertEquals(saved, repository.read())
            model.cancelAppearanceEdit()
            assertEquals(saved, model.appearance.first { it == saved })
        }
    }

    private suspend fun saveAndAwait(model: AppearanceViewModel) {
        model.saveAppearance()
        val result = model.editor.first { it != null && !it.isSaving }
        assertEquals("主题已保存", result!!.message)
    }

    /** Real Preferences DataStore; the test dispatcher controls ViewModel and store collection. */
    private suspend fun TestScope.withEditor(
        initial: AppearanceSettings,
        block: suspend (AppearanceRepository, AppearanceViewModel) -> Unit
    ) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + UnconfinedTestDispatcher(testScheduler))
        val repository = AppearanceRepository(PreferenceDataStoreFactory.create(scope = scope) {
            File(temporary.root, "editor.preferences_pb")
        }, scope)
        val models = ViewModelStore()
        try {
            initial.custom?.let { repository.saveCustom(it) }
            if (initial.selectedThemeId != AppearanceSettings.CUSTOM_ID) {
                repository.selectPreset(requireNotNull(ThemePreset.fromId(initial.selectedThemeId)))
            }
            repository.settings.first { it == initial }
            val model = AppearanceViewModel(repository)
            models.put("appearance", model)
            model.appearance.first { it == initial }
            model.beginAppearanceEdit()
            block(repository, model)
        } finally {
            models.clear()
            job.cancelAndJoin()
        }
    }
}
