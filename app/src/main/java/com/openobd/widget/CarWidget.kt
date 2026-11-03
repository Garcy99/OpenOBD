package com.openobd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.openobd.AppContainer
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.R
import com.openobd.core.garage.Maintenance
import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.display
import com.openobd.core.obd.DtcType
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import java.text.DateFormat
import java.util.Date

/** Home-screen widget: the selected car, trouble codes, next maintenance and the last trip. */
class CarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? OpenObdApp ?: return
        render(context, app.container, manager, ids)
    }

    companion object {
        fun updateAll(context: Context, container: AppContainer) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CarWidget::class.java))
            if (ids.isNotEmpty()) render(context, container, manager, ids)
        }

        fun render(context: Context, c: AppContainer, manager: AppWidgetManager, ids: IntArray) {
            val views = build(context, c)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        fun build(context: Context, c: AppContainer): RemoteViews {
            val g = c.garage.data.value
            val v = g.active
            val units = c.settings.settings.value.units
            val session = c.session
            val connected = session.state.value is ConnectionState.Connected
            val rv = RemoteViews(context.packageName, R.layout.widget_car)
            rv.setTextViewText(R.id.widget_title, v?.name ?: context.getString(R.string.app_name))
            rv.setTextViewText(R.id.widget_status, context.getString(if (connected) R.string.widget_connected else R.string.widget_not_connected))

            // Codes: live scan if available, otherwise the latest stored scan for this car
            val scan = (session.dtcScan.value as? Op.Done)?.value
            val codesText = if (connected && scan != null) {
                val stored = scan.codes.count { it.type == DtcType.STORED }
                when {
                    scan.readiness?.milOn == true -> context.getString(R.string.widget_mil_on, stored)
                    scan.codes.isEmpty() -> context.getString(R.string.widget_no_codes)
                    else -> context.getString(R.string.widget_codes, scan.codes.size)
                }
            } else {
                c.history.records.value.firstOrNull { !it.cleared && (it.vehicleId == null || it.vehicleId == v?.id) }?.let { r ->
                    val what = if (r.codes.isEmpty()) context.getString(R.string.widget_no_codes) else context.getString(R.string.widget_codes, r.codes.size)
                    context.getString(R.string.widget_last_scan, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(r.timestamp)), what)
                } ?: context.getString(R.string.widget_never_scanned)
            }
            rv.setTextViewText(R.id.widget_codes, codesText)

            val now = System.currentTimeMillis()
            val items = g.maintenance.filter { it.vehicleId == v?.id }.map { it to Maintenance.status(it, v?.odometerKm, now) }
            val serviceText = when {
                items.isEmpty() -> context.getString(R.string.widget_service_none)
                items.any { it.second.overdue } -> context.getString(R.string.widget_service_overdue, items.filter { it.second.overdue }.joinToString { it.first.name })
                items.any { it.second.dueSoon } -> context.getString(R.string.widget_service_due, items.filter { it.second.dueSoon }.joinToString { it.first.name })
                else -> context.getString(R.string.widget_service_ok)
            }
            rv.setTextViewText(R.id.widget_service, serviceText)

            val trip = g.trips.firstOrNull { it.vehicleId == v?.id }
            rv.setTextViewText(R.id.widget_trip, trip?.let {
                val d = ObdUnit.KM.display(it.distanceKm, units)
                context.getString(R.string.widget_trip, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it.start)), "%.1f %s".format(d.value, d.unit.symbol))
            } ?: context.getString(R.string.widget_no_trip))

            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            rv.setOnClickPendingIntent(R.id.widget_root, open)
            return rv
        }
    }
}
