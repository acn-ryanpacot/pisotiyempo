package com.pisotiyempo.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pisotiyempo.domain.ph.FeeSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val disclaimerAccepted: Boolean = false,
    val aiEnabled: Boolean = true,
    /** Trading capital used by the position sizer. */
    val capital: Double = 100_000.0,
    /** Percent of capital to risk per trade. */
    val riskPct: Double = 1.0,
    val fees: FeeSchedule = FeeSchedule(),
    val lastSyncAt: Long = 0,
    val lastSyncDate: String? = null,
    val lastSyncSource: String? = null,
    val lastSyncMessage: String? = null,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val disclaimer = booleanPreferencesKey("disclaimer_accepted_v1")
        val ai = booleanPreferencesKey("ai_enabled")
        val capital = doublePreferencesKey("capital")
        val risk = doublePreferencesKey("risk_pct")
        val commission = doublePreferencesKey("commission_rate")
        val minCommission = doublePreferencesKey("min_commission")
        val salesTax = doublePreferencesKey("sales_tax_rate")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncDate = stringPreferencesKey("last_sync_date")
        val lastSyncSource = stringPreferencesKey("last_sync_source")
        val lastSyncMessage = stringPreferencesKey("last_sync_message")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings(): Settings {
        val d = FeeSchedule()
        return Settings(
            disclaimerAccepted = this[Keys.disclaimer] ?: false,
            aiEnabled = this[Keys.ai] ?: true,
            capital = this[Keys.capital] ?: 100_000.0,
            riskPct = this[Keys.risk] ?: 1.0,
            fees = d.copy(
                commissionRate = this[Keys.commission] ?: d.commissionRate,
                minCommission = this[Keys.minCommission] ?: d.minCommission,
                salesTaxRate = this[Keys.salesTax] ?: d.salesTaxRate,
            ),
            lastSyncAt = this[Keys.lastSyncAt] ?: 0,
            lastSyncDate = this[Keys.lastSyncDate],
            lastSyncSource = this[Keys.lastSyncSource],
            lastSyncMessage = this[Keys.lastSyncMessage],
        )
    }

    suspend fun acceptDisclaimer() = context.dataStore.edit { it[Keys.disclaimer] = true }

    suspend fun setAiEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.ai] = enabled }

    suspend fun setRisk(capital: Double, riskPct: Double) = context.dataStore.edit {
        it[Keys.capital] = capital
        it[Keys.risk] = riskPct
    }

    suspend fun setFees(commissionRate: Double, minCommission: Double, salesTaxRate: Double) = context.dataStore.edit {
        it[Keys.commission] = commissionRate
        it[Keys.minCommission] = minCommission
        it[Keys.salesTax] = salesTaxRate
    }

    suspend fun recordSync(date: String?, source: String?, message: String) = context.dataStore.edit {
        it[Keys.lastSyncAt] = System.currentTimeMillis()
        if (date != null) it[Keys.lastSyncDate] = date
        if (source != null) it[Keys.lastSyncSource] = source
        it[Keys.lastSyncMessage] = message
    }
}
