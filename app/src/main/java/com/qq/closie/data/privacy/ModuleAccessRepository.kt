package com.qq.closie.data.privacy

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.*
import com.qq.closie.life.permissions.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.moduleReadPolicyStore by preferencesDataStore("life_os_module_read_policy")

/** The one module-policy store. It holds no records or purpose grants and is never backed up.
 * Persisted choices are tied to the canonical restore epoch; stale choices read as ASK. */
class ModuleAccessRepository(context: Context, readEpoch: suspend () -> Long) {
    private val store = context.applicationContext.moduleReadPolicyStore
    private fun mode(module: ReadModule) = stringPreferencesKey("${module.name}_mode")
    private fun epoch(module: ReadModule) = longPreferencesKey("${module.name}_epoch")
    private fun revision(module: ReadModule) = longPreferencesKey("${module.name}_revision")
    private fun decode(p: Preferences, module: ReadModule) = ModuleReadRule(
        runCatching { ModuleReadMode.valueOf(p[mode(module)].orEmpty()) }.getOrDefault(ModuleReadMode.ASK),
        p[epoch(module)] ?: -1, p[revision(module)] ?: 0)
    val rules = store.data.map { p -> ReadModule.entries.associateWith { decode(p, it) } }
    val owner = ModuleAccessOwner(
        readRule = { module -> decode(store.data.first(), module) },
        writeRule = { module, rule -> store.edit {
            it[mode(module)] = rule.mode.name; it[epoch(module)] = rule.epoch; it[revision(module)] = rule.revision
        }; Unit }, readEpoch = readEpoch,
        readGeneration = { com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration },
        ready = { com.qq.closie.data.backup.RestoreStartupGate.isReady })
}
