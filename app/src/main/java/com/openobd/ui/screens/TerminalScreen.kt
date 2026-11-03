package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.theme.Bad
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val session = LocalApp.current.session
    val lines by session.console.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    fun send(cmd: String) { if (cmd.isNotBlank()) session.sendRaw(cmd) }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(tr("Terminal"), onBack, actions = {
            IconButton(onClick = session::clearConsole) { Icon(Icons.Default.DeleteSweep, tr("Clear")) }
        })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp)) {
            items(lines) { l ->
                Text(
                    "${fmt.format(l.time)} ${l.direction} ${l.text}",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = when (l.direction) { '>' -> MaterialTheme.colorScheme.primary; '!' -> Bad; else -> MaterialTheme.colorScheme.onSurface },
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ATI", "ATRV", "ATDP", "ATDPN", "0100", "010C", "03", "07", "0902", "STI").forEach { c ->
                AssistChip(onClick = { send(c) }, label = { Text(c, fontFamily = FontFamily.Monospace) })
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it.uppercase() }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(tr("AT command or OBD request, e.g. 010D")) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
            )
            IconButton(onClick = { send(input); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send")) }
        }
        Text(tr("Live data polling pauses while a command runs. Commands that change adapter settings may affect the app until you reconnect."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
