package com.openobd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.log.LogCsv
import com.openobd.core.log.LogData
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ChartSeries
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LineChart
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private val palette = listOf(Color(0xFF22D3EE), Color(0xFFF59E0B), Color(0xFFA78BFA))

private suspend fun loadLog(f: File): Result<LogData> = withContext(Dispatchers.IO) { runCatching { LogCsv.parse(f.readText()) } }

/** Recorded log as charts, with replay and an optional second log overlaid for before / after comparisons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogViewerScreen(name: String, onBack: () -> Unit) {
    val app = LocalApp.current
    val files by app.logs.files.collectAsStateWithLifecycle()
    val file = remember(name) { File(app.logs.dir, name) }
    var log by remember { mutableStateOf<Result<LogData>?>(null) }
    LaunchedEffect(file) { log = loadLog(file) }
    var compareName by remember { mutableStateOf<String?>(null) }
    var compare by remember { mutableStateOf<LogData?>(null) }
    LaunchedEffect(compareName) { compare = compareName?.let { loadLog(File(app.logs.dir, it)).getOrNull() } }
    val selected = remember { mutableStateListOf<Int>() }
    var cursor by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(playing) {
        if (playing && cursor >= 1f) cursor = 0f
        while (playing) {
            delay(50)
            val d = log?.getOrNull()?.durationS ?: break
            cursor = (cursor + (0.05 / d.coerceAtLeast(1.0)).toFloat() * 4f).coerceAtMost(1f) // 4x real time
            if (cursor >= 1f) playing = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(name.removeSuffix(".csv"), onBack)
        val result = log
        if (result == null) { LoadingBox(null); return@Column }
        val data = result.getOrElse { ErrorText(tr(it.message ?: "Failed")); return@Column }
        LaunchedEffect(data) { if (selected.isEmpty()) selected += (0 until minOf(2, data.columns.size)) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("%1\$s rows · %2\$s s", data.times.size, "%.0f".format(data.durationS)) + (data.vin?.let { " · VIN $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.columns.forEachIndexed { i, c ->
                    FilterChip(selected = i in selected, onClick = {
                        if (i in selected) selected.remove(i) else { if (selected.size >= 3) selected.removeAt(0); selected += i }
                    }, label = { Text(c, maxLines = 1) })
                }
            }
            val durMs = (data.durationS * 1000).toLong().coerceAtLeast(1)
            val tCursor = data.durationS * cursor
            val series = selected.mapIndexed { n, col ->
                ChartSeries(data.column(col).filter { it.first <= tCursor }.map { (it.first * 1000).toLong() to it.second.toFloat() }, palette[n % palette.size],
                    data.stats(col)?.min?.toFloat(), data.stats(col)?.max?.toFloat())
            } + (compare?.let { other ->
                selected.mapIndexedNotNull { n, col ->
                    val j = other.columns.indexOf(data.columns[col]).takeIf { it >= 0 } ?: return@mapIndexedNotNull null
                    ChartSeries(other.column(j).map { (it.first * 1000).toLong() to it.second.toFloat() }, palette[n % palette.size].copy(alpha = 0.35f),
                        data.stats(col)?.min?.toFloat(), data.stats(col)?.max?.toFloat())
                }
            } ?: emptyList())
            LineChart(series, durMs, durMs, Modifier.fillMaxWidth().height(220.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, tr("Replay")) }
                Slider(cursor, { cursor = it; playing = false }, Modifier.weight(1f))
                Text("%.0f s".format(tCursor), style = MaterialTheme.typography.bodySmall)
            }
            selected.forEachIndexed { n, col ->
                val st = data.stats(col)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(palette[n % palette.size]) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(data.columns[col], fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        st?.let { Text(tr("min %1\$s · avg %2\$s · max %3\$s", f(it.min), f(it.avg), f(it.max)), style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(data.valueAt(col, tCursor)?.let { f(it) } ?: "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            SectionCard(title = tr("Compare with another log")) {
                Text(tr("The other log is drawn faded on the same time axis - useful before and after a repair."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.filter { it.file.name != name }.take(12).forEach { other ->
                        FilterChip(selected = compareName == other.file.name, onClick = {
                            compareName = if (compareName == other.file.name) null else other.file.name
                        }, label = { Text(other.file.name.removePrefix("obd_").removeSuffix(".csv"), maxLines = 1) })
                    }
                }
            }
        }
    }
}

private fun f(v: Double) = if (kotlin.math.abs(v) >= 100) "%.0f".format(v) else "%.2f".format(v).trimEnd('0').trimEnd('.', ',')
