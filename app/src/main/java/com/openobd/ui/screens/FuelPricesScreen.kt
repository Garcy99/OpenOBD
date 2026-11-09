package com.openobd.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openobd.core.fuel.FuelPricesEs
import com.openobd.core.fuel.PumpFuel
import com.openobd.core.fuel.StationNear
import com.openobd.data.CurrentLocation
import com.openobd.data.FuelPriceRepository
import com.openobd.ui.LocalApp
import com.openobd.ui.Permissions
import com.openobd.ui.appSettings
import com.openobd.ui.components.GradientButton
import com.openobd.ui.components.IconBadge
import com.openobd.ui.components.NoticeCard
import com.openobd.ui.components.ScreenColumn
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.SectionLabel
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Orange
import com.openobd.ui.tr
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What a lookup of the stations around the phone found. */
sealed interface FuelLookup {
    data object Loading : FuelLookup
    data object NoPermission : FuelLookup
    data object NoLocation : FuelLookup
    data object OutsideSpain : FuelLookup
    data object Offline : FuelLookup
    data class Found(val updatedAt: Long, val stations: List<StationNear>, val fuel: PumpFuel) : FuelLookup {
        val cheapest: StationNear? get() = stations.minByOrNull { it.price(fuel) ?: Double.MAX_VALUE }
        val average: Double? get() = stations.mapNotNull { it.price(fuel) }.takeIf { it.isNotEmpty() }?.average()
    }
}

/** Stations around the phone now, for [fuel] within [radiusKm]. */
@Composable
fun rememberFuelLookup(fuel: PumpFuel, radiusKm: Double, refresh: Int = 0): FuelLookup {
    val app = LocalApp.current
    val context = LocalContext.current
    var result by remember { mutableStateOf<FuelLookup>(FuelLookup.Loading) }
    LaunchedEffect(fuel, radiusKm, refresh) {
        if (!Permissions.granted(context, arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)) &&
            !Permissions.granted(context, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))) { result = FuelLookup.NoPermission; return@LaunchedEffect }
        if (result !is FuelLookup.Found) result = FuelLookup.Loading
        val here = CurrentLocation.get(context) ?: run { result = FuelLookup.NoLocation; return@LaunchedEffect }
        if (!FuelPricesEs.covers(here.latitude, here.longitude)) { result = FuelLookup.OutsideSpain; return@LaunchedEffect }
        val list = app.fuelPrices.around(here.latitude, here.longitude, radiusKm) ?: run { result = FuelLookup.Offline; return@LaunchedEffect }
        result = FuelLookup.Found(list.updatedAt, FuelPricesEs.near(list.stations, here.latitude, here.longitude, fuel, radiusKm), fuel)
    }
    return result
}

/** The pump fuel of these settings and this car. */
@Composable
fun currentPumpFuel(): PumpFuel {
    val app = LocalApp.current
    return FuelPriceRepository.fuelFor(appSettings(), app.session.diesel.isDiesel)
}

fun euros(v: Double, decimals: Int = 3) = "%.${decimals}f €".format(v)

/** Fuel prices around you: the cheapest stations, the usual price and how much filling up there saves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuelPricesScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fuel = currentPumpFuel()
    var radius by remember { mutableStateOf(10.0) }
    var byPrice by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val lookup = rememberFuelLookup(fuel, radius, refresh)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Fuel prices"), onBack, actions = {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, tr("Refresh")) }
        })
        ScreenColumn {
            Text(tr("Live prices of every station around you, from the government's open data. Trips are also priced with what fuel costs where you drive."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PumpFuel.entries.forEach { f ->
                    FilterChip(f == fuel, { scope.launch { app.settings.update { it.copy(pumpFuel = f.name) } } }, label = { Text(tr(f.label)) },
                        modifier = Modifier.testTag("pump_${f.name}"))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5.0, 10.0, 25.0).forEach { r ->
                    FilterChip(radius == r, { radius = r }, label = { Text(tr("Within %1\$s km", r.toInt())) })
                }
                FilterChip(byPrice, { byPrice = true }, label = { Text(tr("Cheapest")) })
                FilterChip(!byPrice, { byPrice = false }, label = { Text(tr("Nearest")) })
            }
            when (val l = lookup) {
                FuelLookup.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text(tr("Looking for stations near you…"))
                }
                FuelLookup.NoPermission -> NoticeCard(Icons.Default.LocalGasStation, tr("Allow location"),
                    tr("OpenOBD needs to know roughly where you are to show the stations around you. Only the province is sent, to download its prices."), Orange) {
                    ask.launch(Permissions.location())
                }
                FuelLookup.NoLocation -> NoticeCard(Icons.Default.LocalGasStation, tr("Where are you?"), tr("The phone has no location yet. Turn on location and try again."), Orange) { refresh++ }
                FuelLookup.OutsideSpain -> NoticeCard(Icons.Default.LocalGasStation, tr("Only in Spain for now"),
                    tr("Pump prices come from the Spanish government's open data, so they cover stations in Spain."), Orange)
                FuelLookup.Offline -> NoticeCard(Icons.Default.LocalGasStation, tr("Prices could not be downloaded"), tr("Check your internet connection and try again."), Bad) { refresh++ }
                is FuelLookup.Found -> {
                    FuelSummary(l, onDirections = { s -> FuelPriceRepository.directions(context, s.station) })
                    val list = if (byPrice) l.stations.sortedBy { it.price(l.fuel) } else l.stations
                    if (list.isEmpty()) Text(tr("No station sells this fuel within %1\$s km.", radius.toInt()))
                    else {
                        SectionLabel(tr("%1\$s stations", list.size))
                        val prices = list.mapNotNull { it.price(l.fuel) }.sorted()
                        list.take(60).forEach { s -> StationRow(s, l.fuel, prices) { FuelPriceRepository.directions(context, s.station) } }
                    }
                    Text(tr("Prices of %1\$s.", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.updatedAt))) + " " + tr(FuelPricesEs.ATTRIBUTION),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        }
    }
}

/** The cheapest station around, the usual price and the saving of filling up there. */
@Composable
private fun FuelSummary(l: FuelLookup.Found, onDirections: (StationNear) -> Unit) {
    val c = l.cheapest ?: return
    val price = c.price(l.fuel) ?: return
    val avg = l.average
    SectionCard(Modifier.testTag("fuelCheapest")) {
        Text(tr("Cheapest %1\$s near you", tr(l.fuel.label).lowercase()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(euros(price) + "/L", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Good)
        Text(c.station.brand + " · " + c.station.address + ", " + c.station.town, style = MaterialTheme.typography.bodyMedium)
        Text(tr("%1\$s km away", "%.1f".format(c.km)) + (c.station.hours.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (avg != null && avg - price > 0.005) {
            Spacer(Modifier.size(6.dp))
            Text(tr("Usual price here %1\$s/L: filling 50 L there saves %2\$s.", euros(avg), euros(FuelPricesEs.saving(50.0, price, avg), 2)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(10.dp))
        GradientButton(tr("Take me there"), { onDirections(c) }, Modifier.fillMaxWidth(), icon = Icons.Default.Directions)
    }
}

/** A station: brand, where it is, how far, and its price coloured against the others around. */
@Composable
private fun StationRow(s: StationNear, fuel: PumpFuel, sorted: List<Double>, onClick: () -> Unit) {
    val p = s.price(fuel) ?: return
    val rank = if (sorted.size < 2) 0.5 else sorted.indexOfFirst { it >= p }.coerceAtLeast(0).toDouble() / (sorted.size - 1)
    val tint = when { rank <= 0.25 -> Good; rank >= 0.75 -> Bad; else -> MaterialTheme.colorScheme.onSurface }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Default.LocalGasStation, if (tint == MaterialTheme.colorScheme.onSurface) Color.Gray else tint, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.station.brand, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("%.1f km · %s".format(s.km, s.station.address + ", " + s.station.town), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(euros(p), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
    }
}

/** Home: the cheapest station around and the saving, one tap from the list and the way there. */
@Composable
fun FuelNearbyCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    if (!settings.fuelPrices) return
    val fuel = currentPumpFuel()
    when (val l = rememberFuelLookup(fuel, 10.0)) {
        FuelLookup.NoPermission -> NoticeCard(Icons.Default.LocalGasStation, tr("Fuel prices near you"),
            tr("See the cheapest station around and what your trips really cost. Tap to set it up."), Orange, Modifier.testTag("fuelCard"), onClick = onOpen)
        is FuelLookup.Found -> {
            val c = l.cheapest ?: return
            val price = c.price(fuel) ?: return
            val avg = l.average
            NoticeCard(Icons.Default.LocalGasStation, tr("Cheapest %1\$s near you", tr(fuel.label).lowercase()) + ": " + euros(price) + "/L",
                listOfNotNull(c.station.brand + " · " + tr("%1\$s km away", "%.1f".format(c.km)),
                    avg?.takeIf { it - price > 0.005 }?.let { tr("Saves %1\$s on 50 L", euros(FuelPricesEs.saving(50.0, price, it), 2)) }).joinToString(" · "),
                Good, Modifier.testTag("fuelCard"),
                trailing = { IconButton(onClick = { FuelPriceRepository.directions(context, c.station) }) { Icon(Icons.Default.Directions, tr("Take me there"), tint = Good) } },
                onClick = onOpen,
            )
        }
        else -> {}
    }
}

/** Phrases of the fuel price screens, for translation coverage. */
val fuelPriceTexts = FuelPricesEs.texts
