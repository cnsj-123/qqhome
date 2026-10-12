package com.qq.closie.life.finance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class FinanceDiscoverySettings(val enabled: Boolean = false, val wechat: Boolean = true,
    val alipay: Boolean = true, val deliveryMode: FinanceReviewDeliveryMode = FinanceReviewDeliveryMode.IMMEDIATE,
    val lastStatus: String = "", val enhancedReceiptOverlay: Boolean = false)

/** Existing device-scoped owner/store. Only the actually published V14 key is migrated. */
class FinanceAutomationSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE)
    init {
        synchronized(prefs) {
            if (!prefs.contains(MODE) && prefs.contains("background")) {
                val mode = FinanceReviewDeliveryMode.fromV14Background(prefs.getBoolean("background", false))
                prefs.edit().putString(MODE, mode.name).remove("background").apply()
            }
        }
    }
    fun read() = FinanceDiscoverySettings(prefs.getBoolean("enabled", false), prefs.getBoolean("wechat", true),
        prefs.getBoolean("alipay", true), FinanceReviewDeliveryMode.entries.firstOrNull { it.name == prefs.getString(MODE, null) }
            ?: FinanceReviewDeliveryMode.IMMEDIATE, prefs.getString("status", "").orEmpty(), prefs.getBoolean(OVERLAY, false))
    fun save(value: FinanceDiscoverySettings) {
        synchronized(prefs) {
            prefs.edit().putBoolean("enabled", value.enabled).putBoolean("wechat", value.wechat)
                .putBoolean("alipay", value.alipay).putString(MODE, value.deliveryMode.name)
                .putBoolean(OVERLAY, value.enhancedReceiptOverlay).remove("background").apply()
        }
    }
    fun status(message: String) { prefs.edit().putString("status", message).apply() }
    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private companion object {
        const val MODE = "reviewDeliveryMode"
        const val OVERLAY = "enhancedReceiptOverlay"
    }
}
