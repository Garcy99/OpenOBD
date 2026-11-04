package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.Pids
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.formatSensor
import com.openobd.ui.connectedVehicle

@Composable
fun FreezeFrameScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val settings = appSettings()
    val op by session.freezeFrame.collectAsStateWithLifecycle()
    LaunchedEffect(vehicle != null) { if (vehicle != null && op !is Op.Done) session.loadFreezeFrame() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Freeze frame"), onBack, actions = {
            if (vehicle != null) IconButton(onClick = session::loadFreezeFrame) { Icon(Icons.Default.Refresh, tr("Refresh")) }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = op) {
                is Op.Loading -> LoadingBox(s.progress)
                is Op.Error -> ErrorText(s.message)
                Op.Idle -> Unit
                is Op.Done -> {
                    val ff = s.value
                    if (ff == null) {
                        Text(tr("No freeze frame stored. A freeze frame is captured when a confirmed trouble code sets."),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        SectionCard(title = tr("Triggered by")) {
                            Text(ff.dtc ?: tr("Unknown"), fontFamily = com.openobd.ui.theme.Inter, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                            ff.dtc?.let { Text(session.describe(it).description) }
                        }
                        SectionCard(title = tr("Conditions at the time of the fault")) {
                            if (ff.values.isEmpty()) Text(tr("The ECU did not provide sensor values."))
                            ff.values.forEach { (k, v) ->
                                val def = Pids.sensors[k] ?: return@forEach
                                val f = formatSensor(def, v, settings.units)
                                KeyValueRow(tr(def.name), "${f.value} ${f.unit}".trim())
                            }
                        }
                    }
                }
            }
        }
    }
}
