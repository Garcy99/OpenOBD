package com.openobd

import android.app.Application
import android.content.Intent
import com.openobd.core.dtc.DtcDatabase
import com.openobd.data.GarageRepository
import com.openobd.data.HistoryRepository
import com.openobd.data.LogRepository
import com.openobd.data.SettingsRepository
import com.openobd.service.ObdService
import com.openobd.session.ConnectionState
import com.openobd.session.ObdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app, scope)
    val history = HistoryRepository(app)
    val logs = LogRepository(app)
    val garage = GarageRepository(app)
    val dtcDatabase: DtcDatabase by lazy {
        DtcDatabase(
            runCatching { app.assets.open("dtc/generic.tsv") }.getOrNull(),
            runCatching { app.assets.open("dtc/causes.tsv") }.getOrNull(),
        )
    }
    val session by lazy { ObdSession(app, scope, settings, history, logs, dtcDatabase, garage) }
}

class OpenObdApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.openobd.ui.I18n.load(this, "system")
        container.scope.launch {
            container.garage.load(container.settings.readOnce())
            container.history.load()
            container.logs.refresh()
            container.dtcDatabase.size // warm up off the main thread
        }
        keepWidgetUpdated()
        // Keep a foreground service alive while connected so logging continues with the screen off.
        container.scope.launch {
            var wasConnected = false
            container.session.state.collect { s ->
                val connected = s is ConnectionState.Connected
                if (connected && !wasConnected) startService()
                if (!connected && wasConnected) stopService(Intent(this@OpenObdApp, ObdService::class.java))
                wasConnected = connected
            }
        }
    }

    /** Refreshes the home-screen widget whenever the car, scan results or garage data change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun keepWidgetUpdated() {
        val c = container
        container.scope.launch {
            kotlinx.coroutines.flow.combine(c.session.state, c.session.dtcScan, c.garage.data, c.history.records) { _, _, _, _ -> }
                .debounce(1000)
                .collect { runCatching { com.openobd.widget.CarWidget.updateAll(this@OpenObdApp, c) } }
        }
    }

    private fun startService() {
        val i = Intent(this, ObdService::class.java)
        runCatching { startForegroundService(i) }
    }
}
