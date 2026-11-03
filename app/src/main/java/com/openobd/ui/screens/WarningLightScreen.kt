package com.openobd.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openobd.data.ChatTurn
import com.openobd.data.WarningLightAssistant
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Decodes a photo and shrinks it to at most [maxSide] pixels: enough to read the symbols, small to upload. */
fun loadPhotoJpeg(context: Context, uri: Uri, maxSide: Int = 1280): Pair<Bitmap, ByteArray>? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null
    val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    bmp to out.toByteArray()
}.getOrNull()

/** Photo of the instrument cluster -> which lights are on, how urgent, what to do. */
@Composable
fun WarningLightScreen(onBack: () -> Unit, onSettings: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val settings = appSettings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Pair<Bitmap, ByteArray>?>(null) }
    var note by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val turns = remember { mutableStateListOf<ChatTurn>() }
    val assistant = remember(settings.claudeApiKey, settings.language) {
        settings.claudeApiKey.takeIf { it.isNotBlank() }?.let { WarningLightAssistant(app.assistantBackend(it), com.openobd.ui.I18n.language) }
    }
    val cameraFile = remember { File(File(context.cacheDir, "reports").apply { mkdirs() }, "warning-light.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile) }

    fun usePhoto(uri: Uri) {
        scope.launch {
            val p = withContext(Dispatchers.IO) { loadPhotoJpeg(context, uri) }
            if (p == null) error = tr("Could not read the photo.") else { photo = p; turns.clear(); error = null }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) usePhoto(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::usePhoto) }

    /** Car model, warning light state and the codes stored right now, sent along with the photo. */
    fun carContext(): String = buildString {
        appendLine("Car data read by OpenOBD:")
        appendLine("- Car: " + (session.diagnosis.carDescription().description ?: "unknown model"))
        val scan = (session.dtcScan.value as? Op.Done)?.value
        if (scan == null) appendLine("- Trouble codes: not read (the car is not connected)")
        else {
            scan.readiness?.let { appendLine("- Engine warning light (MIL) reported by the engine computer: " + if (it.milOn) "ON" else "OFF") }
            appendLine("- Trouble codes: " + scan.codes.distinctBy { it.code }.joinToString("; ") { "${it.code} ${session.describe(it.code).description} (${it.type.label})" }.ifEmpty { "none" })
        }
        (session.modules.moduleScan.value as? Op.Done)?.value?.filter { it.dtcs.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { m ->
            appendLine("- Other modules: " + m.joinToString("; ") { r -> r.module.shortName + " " + r.dtcs.joinToString(", ") { it.code } })
        }
    }.trim()

    fun send(text: String, first: Boolean) {
        val a = assistant ?: return
        val p = photo ?: return
        busy = true; error = null
        turns += ChatTurn(true, if (first) text.ifBlank { tr("Which warning lights are on and what should I do?") } else text)
        scope.launch {
            try {
                val reply = if (first) a.start(p.second, carContext(), text) else a.ask(text)
                turns += ChatTurn(false, reply)
                input = ""
            } catch (e: Exception) {
                turns.removeAt(turns.lastIndex)
                error = tr(e.message ?: "Failed")
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(tr("Identify a warning light"), onBack)
        if (assistant == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Take a photo of the instrument cluster and the AI assistant tells you which lights are on, how urgent they are and what to do."),
                    style = MaterialTheme.typography.bodyLarge)
                Text(tr("It uses Claude, by Anthropic, with your own API key. Add the key in Settings to use it."), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onSettings) { Text(tr("Open settings")) }
            }
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Photograph the cluster with the ignition on, close enough to see the symbols. The photo, the car model and the stored codes are sent - never the VIN or your location."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { takePhoto.launch(cameraUri) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text(tr("Take a photo"))
                }
                OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(tr("Gallery"))
                }
            }
            photo?.let { (bmp, _) ->
                Image(bmp.asImageBitmap(), tr("Photo of the instrument cluster"), Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit)
                if (turns.isEmpty()) {
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(tr("Anything to add? (optional, e.g. it flashes)")) })
                    Button(onClick = { send(note, first = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Identify the lights")) }
                }
            }
            turns.forEach { t ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (t.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(t.text, Modifier.widthIn(max = 520.dp).background(
                        if (t.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp),
                    ).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(tr("Thinking…"))
            }
            error?.let { ErrorText(it) }
        }
        if (turns.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(tr("Your question")) }, enabled = !busy)
            IconButton(onClick = { send(input, first = false) }, enabled = !busy && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send")) }
        }
    }
}
