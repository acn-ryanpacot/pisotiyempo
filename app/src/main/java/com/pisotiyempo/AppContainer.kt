package com.pisotiyempo

import android.content.Context
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.remote.PhisixClient
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.repo.AiRepository
import com.pisotiyempo.data.repo.AlertRepository
import com.pisotiyempo.data.repo.AnalysisRepository
import com.pisotiyempo.data.repo.MarketRepository
import com.pisotiyempo.data.repo.PortfolioRepository
import com.pisotiyempo.data.settings.SettingsStore
import com.pisotiyempo.work.SyncService

/** Manual dependency wiring: one instance of each service for the whole app. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val db: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    private val api = PisoApi()
    val market = MarketRepository(db, api, PhisixClient())
    val analysis = AnalysisRepository(db, market)
    val ai = AiRepository(db, api, settings)
    val portfolio = PortfolioRepository(db)
    val alerts = AlertRepository(db)
    val sync = SyncService(appContext, market, analysis, portfolio, alerts, settings)
}
