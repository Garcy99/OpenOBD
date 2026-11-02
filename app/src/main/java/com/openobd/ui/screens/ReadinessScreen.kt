package com.openobd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.MonitorState
import com.openobd.core.obd.ReadinessStatus
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn

@Composable
fun ReadinessScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val op by session.readiness.collectAsStateWithLifecycle()
    LaunchedEffect(vehicle != null) { if (vehicle != null && op !is Op.Done) session.loadReadiness() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Emission readiness", onBack, actions = {
            if (vehicle != null) IconButton(onClick = session::loadReadiness) { Icon(Icons.Default.Refresh, "Refresh") }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = op) {
                is Op.Loading -> LoadingBox(s.progress)
                is Op.Error -> ErrorText(s.message)
                Op.Idle -> Unit
                is Op.Done -> {
                    val (since, cycle) = s.value
                    if (since == null) ErrorText("This vehicle does not report monitor status.")
                    since?.let {
                        Verdict(it)
                        MonitorList("Since trouble codes were cleared", it)
                    }
                    cycle?.let { MonitorList("This drive cycle", it) }
                    Text("Continuous monitors (misfire, fuel system, components) run all the time. The others need specific driving " +
                        "conditions (a drive cycle) to complete after codes are cleared or the battery is disconnected.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Verdict(r: ReadinessStatus) {
    SectionCard {
        val ok = r.likelyPassesInspection
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel, null, tint = if (ok) Good else Bad, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (ok) "Likely ready for inspection" else "Not ready for inspection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(if (r.milOn) "Check engine light ON. " else "Check engine light off. ")
                        append("${r.complete.size} complete, ${r.incomplete.size} incomplete, ${r.monitors.size - r.supported.size} not supported.")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(if (r.isDiesel) "Compression ignition (diesel) monitors" else "Spark ignition (gasoline) monitors", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MonitorList(title: String, r: ReadinessStatus) {
    SectionCard(title = title) {
        r.monitors.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, color) = when (m.state) {
                    MonitorState.COMPLETE -> Icons.Default.CheckCircle to Good
                    MonitorState.INCOMPLETE -> Icons.Default.Cancel to Warn
                    MonitorState.NOT_SUPPORTED -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.outline
                }
                Icon(icon, null, tint = color)
                Spacer(Modifier.width(10.dp))
                Text(m.name, Modifier.weight(1f))
                Pill(m.state.label, color)
            }
        }
    }
}
