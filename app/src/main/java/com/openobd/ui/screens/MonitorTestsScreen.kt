package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
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
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.formatNumber
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good

@Composable
fun MonitorTestsScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val op by session.monitorTests.collectAsStateWithLifecycle()
    LaunchedEffect(vehicle != null) { if (vehicle != null && op !is Op.Done) session.loadMonitorTests() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("On-board monitor tests"), onBack, actions = {
            if (vehicle != null) IconButton(onClick = session::loadMonitorTests) { Icon(Icons.Default.Refresh, tr("Refresh")) }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        if (!vehicle.elm.protocol.isCan) {
            ErrorText(tr("Mode 06 results are only standardised on CAN vehicles (most cars from 2008 onwards)."), Modifier.padding(16.dp))
            return@Column
        }
        when (val s = op) {
            is Op.Loading -> LoadingBox(s.progress)
            is Op.Error -> ErrorText(s.message, Modifier.padding(16.dp))
            Op.Idle -> Unit
            is Op.Done -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val failed = s.value.count { !it.passed }
                    Text(
                        if (s.value.isEmpty()) tr("The vehicle did not report any test results.")
                        else tr("%1\$s tests · %2\$s", s.value.size, (if (failed == 0) tr("all passed") else tr("%1\$s outside limits", failed))),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(tr("Tests close to their limits hint at parts that are about to fail before a code is set."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.value.groupBy { it.mid to it.ecu }.forEach { (key, tests) ->
                    item(key = "${key.first}_${key.second}") {
                        SectionCard(title = tests.first().monitorName) {
                            tests.forEach { t ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.testName, style = MaterialTheme.typography.bodyMedium)
                                        Text(tr("limits %1\$s … %2\$s %3\$s", (num(t.min)), (num(t.max)), t.unit),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${num(t.value)} ${t.unit}", fontFamily = com.openobd.ui.theme.Inter, fontWeight = FontWeight.Bold)
                                        Pill(if (t.passed) "PASS" else "FAIL", if (t.passed) Good else Bad)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun num(v: Double): String = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) formatNumber(v, 0) else formatNumber(v, 3)
