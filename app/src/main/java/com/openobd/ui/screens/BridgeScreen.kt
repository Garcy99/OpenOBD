package com.openobd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.data.ConnectionType
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.GradientButton
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.SoftButton
import com.openobd.ui.theme.Good
import com.openobd.ui.tr

/**
 * The phone side of the bridge: lends this phone's adapter to the car's screen over Wi-Fi, for head units whose own
 * Bluetooth cannot hold the adapter.
 */
@Composable
fun BridgeScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val settings = appSettings()
    val st by app.bridge.state.collectAsStateWithLifecycle()
    val on = st.running
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Car screen"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Some car screens cannot hold the OBD adapter over Bluetooth. With the bridge, this phone keeps the adapter and the car screen uses it over Wi-Fi, with all of OpenOBD: live data, codes, modules and trips."),
                style = MaterialTheme.typography.bodyMedium)
            SectionCard(title = tr("How to")) {
                listOf(
                    tr("Choose this phone's adapter on Home as usual (Bluetooth, USB…)."),
                    tr("Turn on this phone's Wi-Fi hotspot and connect the car screen to it."),
                    tr("Start the bridge here."),
                    tr("On the car screen, in OpenOBD: Adapter → Phone, then Connect. It finds this phone by itself."),
                ).forEachIndexed { i, t ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SectionCard(title = tr("Bridge")) {
                val adapterLabel = when (settings.connectionType) {
                    ConnectionType.DEMO -> tr("Demo car")
                    ConnectionType.WIFI -> settings.wifiHost
                    ConnectionType.USB -> tr("USB cable")
                    ConnectionType.PHONE -> "—"
                    else -> settings.deviceName ?: tr("Choose adapter")
                }
                KeyValueRow(tr("Adapter"), adapterLabel)
                if (on) {
                    KeyValueRow(tr("Link to the adapter"), st.adapter?.let { tr("Open") } ?: tr("Connecting to the adapter…"))
                    KeyValueRow(tr("Car screen"), st.client?.let { tr("Connected (%1\$s)", it) } ?: tr("Waiting for the car screen"))
                    if (st.toCar + st.fromCar > 0) KeyValueRow(tr("Data passed"), "↑ %d B · ↓ %d B".format(st.toCar, st.fromCar))
                }
                st.error?.let { ErrorText(tr(it)) }
                Spacer(Modifier.padding(4.dp))
                if (!on) GradientButton(tr("Start the bridge"), { app.bridge.start() }, Modifier.fillMaxWidth().testTag("bridgeStart"), icon = Icons.Default.Cast,
                    enabled = settings.connectionType != ConnectionType.PHONE)
                else SoftButton(tr("Stop the bridge"), { app.bridge.stop() }, Modifier.fillMaxWidth().testTag("bridgeStop"), icon = Icons.Default.Stop)
                if (settings.connectionType == ConnectionType.PHONE) Text(tr("This phone is set to use another phone's bridge. Choose its own adapter first."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (on) {
                val addresses = app.bridge.addresses()
                if (addresses.isNotEmpty()) SectionCard(title = tr("If the car screen does not find the phone")) {
                    Text(tr("On the car screen choose Wi-Fi with one of these addresses and port %1\$s:", st.port), style = MaterialTheme.typography.bodySmall)
                    addresses.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a, style = MaterialTheme.typography.titleMedium, color = Good, modifier = Modifier.testTag("bridgeAddress"))
                        }
                    }
                }
            }
            Text(tr("While the bridge is on, this phone does not use the adapter itself, and it keeps working with the screen off. Stop it here or from the notification."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Every phrase of the bridge, for translation coverage. */
val bridgeTexts = listOf("How to", "Bridge", "Link to the adapter", "Data passed", "Car screen", "Connected (%1\$s)", "Phone", "My phone (bridge)")
