package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.SensorCatalog
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.formatSensor

/** Big high-contrast numbers, optionally mirrored so they read correctly as a windshield reflection. */
@Composable
fun HudScreen(onBack: () -> Unit) {
    val session = LocalApp.current.session
    val settings = appSettings()
    var mirrored by remember { mutableStateOf(true) }
    var colorIndex by remember { mutableIntStateOf(0) }
    val colors = listOf(Color(0xFF22D3EE), Color(0xFF4ADE80), Color.White, Color(0xFFFACC15))
    val main = "010D"
    val secondary = listOf("010C", "CALC_CONS", "0105")
    SubscribeSensors("hud", secondary + main)
    val readings by session.live.readings.collectAsStateWithLifecycle()
    val color = colors[colorIndex]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { scaleY = if (mirrored) -1f else 1f }.clickable { colorIndex = (colorIndex + 1) % colors.size },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            val sd = SensorCatalog.get(main)!!
            val f = formatSensor(sd, readings[main]?.value, settings.units)
            Text(f.value, color = color, fontSize = 160.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(f.unit, color = color, fontSize = 32.sp)
            Row(Modifier.padding(top = 24.dp)) {
                secondary.forEach { k ->
                    val d = SensorCatalog.get(k) ?: return@forEach
                    val v = formatSensor(d, readings[k]?.value, settings.units)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 18.dp)) {
                        Text(v.value, color = color, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(v.unit.ifEmpty { d.shortName }, color = color.copy(alpha = 0.7f), fontSize = 16.sp)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 8.dp)) {
            IconButton(onClick = { mirrored = !mirrored }) { Icon(Icons.Default.Flip, "Mirror", tint = Color.Gray) }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close", tint = Color.Gray) }
        }
    }
}
