package com.openobd.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openobd.BuildConfig
import com.openobd.ui.openUrl
import com.openobd.ui.theme.Bad
import com.openobd.ui.tr

/** Optional donations: never required, never a pop-up, only a button where people look for it. */
object Donate {
    /** Donation page from the build; empty in Google Play builds. Replaceable in tests. */
    var url: String = BuildConfig.DONATE_URL
    val enabled: Boolean get() = url.isNotBlank()
}

/** "Support OpenOBD" card: a short thank-you text and a button that opens the donation page. */
@Composable
fun DonateCard(modifier: Modifier = Modifier) {
    if (!Donate.enabled) return
    val context = LocalContext.current
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Favorite, Bad, 40.dp)
            Spacer(Modifier.width(14.dp))
            Text(tr("Support OpenOBD"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(tr("OpenOBD is free and open source, with no ads and nothing locked. If it saved you a trip to the garage, you can support its development. Completely optional: everything keeps working either way."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        SoftButton(tr("Donate"), { openUrl(context, Donate.url) }, Modifier.fillMaxWidth(), icon = Icons.Default.Favorite, color = Bad)
    }
}
