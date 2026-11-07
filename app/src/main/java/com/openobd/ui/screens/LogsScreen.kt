package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.SensorCatalog
import com.openobd.ui.LocalApp
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SensorPickerDialog
import com.openobd.ui.appSettings
import com.openobd.ui.connectedVehicle
import com.openobd.ui.shareFile
import com.openobd.ui.theme.Bad
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LogsScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
    val app = LocalApp.current
    val session = app.session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by app.logs.files.collectAsStateWithLifecycle()
    val recording by session.recording.collectAsStateWithLifecycle()
    val vehicle = connectedVehicle()
    var picking by remember { mutableStateOf(false) }
    val settings = appSettings()
    LaunchedEffect(Unit) { app.logs.refresh() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Data logs"), onBack)
        if (vehicle != null) {
            Button(
                onClick = { if (recording) session.stopRecording() else picking = true },
                modifier = Modifier.padding(16.dp),
            ) { Text(if (recording) tr("■ Stop recording") else tr("● Start new recording")) }
        } else {
            Text(tr("Connect to record. Recordings continue in the background."), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recording) Text(tr("Recording…"), Modifier.padding(horizontal = 16.dp), color = Bad)
        if (files.isEmpty()) {
            CenteredMessage(Icons.Default.Description, tr("No logs yet. Recordings are saved as CSV files you can open in any spreadsheet."))
        } else LazyColumn {
            items(files, key = { it.file.path }) { f ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(f.file.name) },
                    headlineContent = { Text(f.file.name) },
                    supportingContent = { Text(DateFormat.getDateTimeInstance().format(Date(f.modified)) + " · " + "%.1f KB".format(f.sizeBytes / 1024.0)) },
                    leadingContent = { Icon(Icons.Default.Description, null) },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { shareFile(context, f.file) }) { Icon(Icons.Default.Share, tr("Share")) }
                            IconButton(onClick = { scope.launch { app.logs.delete(f.file) } }) { Icon(Icons.Default.Delete, tr("Delete")) }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (picking && vehicle != null) {
        val available = SensorCatalog.available(vehicle.supportedPids).filter { it.labeler == null }
        SensorPickerDialog(
            sensors = available,
            title = tr("Sensors to record"),
            onDismiss = { picking = false },
            selected = settings.dashboard.map { com.openobd.ui.components.GaugeSpec.parse(it).key }.filter { k -> available.any { it.key == k } }.toSet(),
            onConfirm = { keys -> picking = false; if (keys.isNotEmpty()) session.startRecording(keys.toList()) },
        )
    }
}
