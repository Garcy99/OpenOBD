package com.openobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.BuildConfig
import com.openobd.data.Updater
import kotlinx.coroutines.launch

/**
 * Pops up when a new version is out: what's new, then download and install it from here. Android asks once to
 * allow OpenOBD to install apps, and always shows its own install confirmation.
 */
@Composable
fun UpdateDialog() {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.updater.state.collectAsStateWithLifecycle()
    var askPermission by remember { mutableStateOf(false) }
    fun install(s: Updater.State.Ready) {
        if (app.updater.canInstall()) runCatching { context.startActivity(app.updater.installIntent(s.file)) } else askPermission = true
    }
    // Downloaded: straight on to Android's installer.
    LaunchedEffect(st) { (st as? Updater.State.Ready)?.let { install(it) } }
    val release = when (val s = st) {
        is Updater.State.Available -> s.release
        is Updater.State.Downloading -> s.release
        is Updater.State.Ready -> s.release
        is Updater.State.Failed -> s.release
        else -> null
    } ?: return
    val notes = release.notesFor(I18n.language)
    AlertDialog(
        onDismissRequest = { if (st !is Updater.State.Downloading) app.updater.later() },
        modifier = Modifier.testTag("updateDialog"),
        title = { Text(tr("New version available")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("OpenOBD %1\$s (you have %2\$s)", release.version, BuildConfig.VERSION_NAME), fontWeight = FontWeight.SemiBold)
                if (notes.isNotBlank()) Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(notes, style = MaterialTheme.typography.bodySmall)
                }
                when (val s = st) {
                    is Updater.State.Downloading -> {
                        LinearProgressIndicator(progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text(tr("Downloading… %1\$s of %2\$s MB", "%.1f".format(s.done / 1e6), "%.1f".format(s.total / 1e6)), style = MaterialTheme.typography.bodySmall)
                    }
                    is Updater.State.Ready -> Text(
                        if (askPermission) tr("Android needs your permission for OpenOBD to install its updates. Allow it on the next screen, come back and press Install.")
                        else tr("Downloaded. Android will ask you to confirm the installation; your data stays as it is."),
                        style = MaterialTheme.typography.bodySmall)
                    is Updater.State.Failed -> Text(tr(s.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else -> Text(tr("It installs over the current version: your cars, trips and settings stay."), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when (val s = st) {
                is Updater.State.Available -> Button(onClick = { scope.launch { app.updater.fetch(s.release) } }, modifier = Modifier.testTag("updateNow")) { Text(tr("Update")) }
                is Updater.State.Ready -> if (askPermission && !app.updater.canInstall()) {
                    Button(onClick = { runCatching { context.startActivity(app.updater.permissionIntent()) } }) { Text(tr("Allow")) }
                } else Button(onClick = { askPermission = false; install(s) }, modifier = Modifier.testTag("updateInstall")) { Text(tr("Install")) }
                is Updater.State.Failed -> Button(onClick = { openUrl(context, s.release?.page ?: "https://github.com/${Updater.REPO}/releases") }) { Text(tr("Open on GitHub")) }
                else -> {}
            }
        },
        dismissButton = {
            if (st !is Updater.State.Downloading) Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = { app.updater.later() }, modifier = Modifier.testTag("updateLater")) { Text(tr("Later")) }
                if (st is Updater.State.Available) TextButton(onClick = { app.updater.skip(release) }) { Text(tr("Skip this version")) }
            }
        },
    )
}

/** About → check for updates now. */
@Composable
fun UpdateCheckRow() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val st by app.updater.state.collectAsStateWithLifecycle()
    if (!app.updater.enabled) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.openobd.ui.components.SoftButton(tr("Check for updates"), { scope.launch { app.updater.check(manual = true) } },
            Modifier.fillMaxWidth().testTag("checkUpdates"), enabled = st !is Updater.State.Checking && st !is Updater.State.Downloading)
        when (st) {
            Updater.State.Checking -> Text(tr("Checking…"), style = MaterialTheme.typography.bodySmall)
            Updater.State.UpToDate -> Text(tr("You have the latest version."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("upToDate"))
            else -> {}
        }
    }
}

/** Phrases of the update screens, for translation coverage. */
val updateTexts = listOf("Install", "Allow", "Later", "Update")
