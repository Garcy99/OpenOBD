package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.UnitSystem
import com.openobd.core.obd.display
import com.openobd.session.PerfPhase
import com.openobd.session.PerfTarget
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Good

@Composable
fun PerformanceScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val settings = appSettings()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Performance timer"), onBack)
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        SubscribeSensors("performance", listOf("010D"))
        val s by session.perf.state.collectAsStateWithLifecycle()
        val rate by session.live.rate.collectAsStateWithLifecycle()
        val speed = ObdUnit.KMH.display(s.speed, settings.units)
        val dist = if (settings.units == UnitSystem.IMPERIAL) "%.0f ft".format(s.distanceM * 3.28084) else "%.0f m".format(s.distanceM)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.2f s".format(s.elapsedS), fontSize = 56.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("%.0f %s · %s".format(speed.value, speed.unit.symbol, dist), style = MaterialTheme.typography.titleMedium)
                    Text(s.message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = session.perf::arm, enabled = s.phase != PerfPhase.RUNNING, modifier = Modifier.weight(1f)) {
                        Text(if (s.phase == PerfPhase.FINISHED) tr("New run") else tr("Arm"))
                    }
                    OutlinedButton(onClick = session.perf::cancel, enabled = s.phase == PerfPhase.ARMED || s.phase == PerfPhase.RUNNING, modifier = Modifier.weight(1f)) { Text(tr("Cancel")) }
                }
            }
            SectionCard(title = tr("Results")) {
                PerfTarget.entries.forEach { t ->
                    val r = s.results[t]
                    val best = s.best[t]
                    val trap = s.trapSpeeds[t]?.let { v -> ObdUnit.KMH.display(v, settings.units).let { " @ %.0f %s".format(it.value, it.unit.symbol) } } ?: ""
                    KeyValueRow(tr(t.label), (r?.let { "%.2f s".format(it) + trap } ?: "—") + (best?.let { tr("   (best %.2f)").format(it) } ?: ""),
                        valueColor = if (r != null && r == best) Good else androidx.compose.ui.graphics.Color.Unspecified)
                }
            }
            Text(tr("Speed is sampled about %.1f times per second; results are interpolated between samples. Accuracy depends on your adapter's speed. ").format(rate) +
                tr("Only use on a closed course or drag strip and obey the law."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
