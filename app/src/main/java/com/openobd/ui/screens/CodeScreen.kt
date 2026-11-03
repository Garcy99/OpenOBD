package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.diagnosis.DriveAdvice
import com.openobd.core.diagnosis.Evidence
import com.openobd.data.ChatTurn
import com.openobd.data.ClaudeBackend
import com.openobd.data.DiagnosisAssistant
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.openUrl
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import com.openobd.ui.trCategory
import kotlinx.coroutines.launch

private fun adviceColor(a: DriveAdvice) = when (a) { DriveAdvice.OK -> Good; DriveAdvice.CAUTION -> Warn; DriveAdvice.STOP -> Bad }

private fun evidenceText(e: Evidence) = tr(e.text, *e.args.toTypedArray())

/** Full explanation of one trouble code: can I drive, ranked causes with the car's own evidence, next steps. */
@Composable
fun CodeScreen(code: String, onBack: () -> Unit, onAssistant: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val context = LocalContext.current
    val vehicle = connectedVehicle()
    // Recompute when the data behind the evidence arrives.
    val ff by session.freezeFrame.collectAsStateWithLifecycle()
    val tests by session.monitorTests.collectAsStateWithLifecycle()
    val desc = remember(code) { session.describe(code) }
    val explanation = remember(code, ff, tests) { session.diagnosis.explain(code) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(code, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(if (desc.known) desc.description else trCategory(desc.description), style = MaterialTheme.typography.titleLarge)
                Text(trCategory(desc.category), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!desc.known) Text(tr("This code is not in the generic database. Manufacturer-specific meanings vary by brand; use the web search below."),
                    style = MaterialTheme.typography.bodySmall, color = Warn, modifier = Modifier.padding(top = 6.dp))
            }

            val color = adviceColor(explanation.advice)
            Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text(tr("Can I keep driving?"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr(explanation.advice.label), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
                Text(tr(explanation.adviceReason), style = MaterialTheme.typography.bodyMedium)
            }

            val causes = explanation.causes.filter { it.cause.isNotEmpty() }
            if (causes.isNotEmpty()) SectionCard(title = tr("Most likely causes")) {
                causes.forEachIndexed { i, c ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr(c.cause), style = MaterialTheme.typography.bodyLarge, fontWeight = if (c.evidence.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal)
                            c.evidence.forEach { e -> Text("↳ " + evidenceText(e), style = MaterialTheme.typography.bodySmall, color = if (e.supports) Good else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            explanation.causes.firstOrNull { it.cause.isEmpty() }?.let { general ->
                SectionCard(title = tr("What the car shows")) {
                    general.evidence.forEach { e -> Text("• " + evidenceText(e), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
            if (vehicle != null && (ff !is Op.Done || (tests !is Op.Done && vehicle.elm.protocol.isCan))) {
                val busy = ff is Op.Loading || tests is Op.Loading
                OutlinedButton(onClick = session.diagnosis::readMoreData, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(tr("Read freeze frame and on-board tests for better evidence"))
                }
            }

            SectionCard(title = tr("What to do next")) {
                desc.knowledge?.advice?.let { Text("• " + tr(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                explanation.nextSteps.forEach { Text("• " + tr(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            }

            Button(onClick = onAssistant, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(tr("Ask the AI assistant"))
            }
            TextButton(onClick = { openUrl(context, "https://www.google.com/search?q=" + android.net.Uri.encode("$code ${desc.description}")) }) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text(tr("Search web"))
            }
            Text(tr("The explanation uses simple rules on your car's own data. It helps you decide, it does not replace a mechanic."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Chat with Claude about one code, with the car's data as context. Needs the user's own API key. */
@Composable
fun AssistantScreen(code: String, onBack: () -> Unit, onSettings: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val settings = appSettings()
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<ChatTurn>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showContext by remember { mutableStateOf(false) }
    val contextText = remember(code) { session.diagnosis.assistantContext(code) }
    val assistant = remember(settings.claudeApiKey, settings.language) {
        settings.claudeApiKey.takeIf { it.isNotBlank() }?.let { DiagnosisAssistant(app.assistantBackend(it), com.openobd.ui.I18n.language) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    fun send(question: String) {
        val a = assistant ?: return
        if (question.isBlank() || busy) return
        error = null; busy = true
        val first = turns.isEmpty()
        turns += ChatTurn(true, question)
        input = ""
        scope.launch {
            try {
                val reply = if (first) a.start(contextText, question) else a.ask(question)
                turns += ChatTurn(false, reply)
            } catch (e: Exception) {
                turns.removeAt(turns.lastIndex)
                input = question
                error = tr(e.message ?: "Failed")
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(tr("AI assistant") + " · " + code, onBack)
        if (assistant == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("The AI assistant uses Claude, by Anthropic, with your own API key. Add the key in Settings to use it."), style = MaterialTheme.typography.bodyLarge)
                Text(tr("Only the codes, sensor values and car model are sent - never the VIN or your location."), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onSettings) { Text(tr("Open settings")) }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column {
                    Text(tr("Ask about this fault. The assistant sees the data below, read from your car."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showContext = !showContext }) { Text(if (showContext) tr("Hide the data sent") else tr("Show the data sent")) }
                    if (showContext) Text(contextText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(turns) { t -> Bubble(t) }
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(tr("Thinking…")) } }
            error?.let { e -> item { ErrorText(e) } }
        }
        if (turns.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tr("Explain this fault and what I should do"), tr("Can I fix it myself?")).forEach { q ->
                    OutlinedButton(onClick = { send(q) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(q, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(tr("Your question")) }, enabled = !busy)
            IconButton(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send")) }
        }
    }
}

@Composable
private fun Bubble(t: ChatTurn) {
    val bg = if (t.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = if (t.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(t.text, Modifier.widthIn(max = 520.dp).background(bg, RoundedCornerShape(14.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
