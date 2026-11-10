package com.pisotiyempo

import android.content.Context
import com.pisotiyempo.data.billing.BillingRepository
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.remote.IntegritySession
import com.pisotiyempo.data.remote.PhisixClient
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.repo.AiRepository
import com.pisotiyempo.data.repo.AlertRepository
import com.pisotiyempo.data.repo.AnalysisRepository
import com.pisotiyempo.data.repo.MarketRepository
import com.pisotiyempo.data.repo.PortfolioRepository
import com.pisotiyempo.data.settings.SecretStore
import com.pisotiyempo.data.sync.DeviceSync
import com.pisotiyempo.data.settings.SettingsStore
import com.pisotiyempo.work.SyncService

/** Manual dependency wiring: one instance of each service for the whole app. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val db: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    private val sessions = IntegritySession(appContext, db)
    private val api = PisoApi(BuildConfig.API_BASE_URL, BuildConfig.APP_KEY, sessions = sessions)
    val market = MarketRepository(db, api, PhisixClient(), SecretStore(db))
    val billing = BillingRepository(appContext, db, sessions)
    val analysis = AnalysisRepository(db, market)
    val ai = AiRepository(db, api, settings)
    /** Sync of trades, journal, watchlist and alerts with the desktop app. */
    val devices = DeviceSync(db, api)
    val portfolio = PortfolioRepository(db, onChange = devices::changed)
    val alerts = AlertRepository(db, onChange = devices::changed)
    val sync = SyncService(appContext, market, analysis, portfolio, alerts, settings, devices)
}
