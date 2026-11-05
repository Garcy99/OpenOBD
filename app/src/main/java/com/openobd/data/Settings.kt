package com.openobd.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.EcuModule
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.obd.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ConnectionType(val label: String) { BLUETOOTH("Bluetooth"), BLE("Bluetooth LE"), WIFI("Wi-Fi"), USB("USB"), DEMO("Demo") }

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class FuelKind(val label: String, val afr: Double, val densityGPerL: Double) {
    AUTO("Auto-detect", 14.7, 745.0),
    GASOLINE("Gasoline / petrol", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0),
    E85("Ethanol E85", 9.8, 785.0),
    LPG("LPG", 15.5, 540.0),
    CNG("CNG", 17.2, 180.0),
}

@Serializable
data class AlertRule(
    val id: Long,
    val sensorKey: String,
    val above: Boolean,
    val threshold: Double,
    val enabled: Boolean = true,
)

@Serializable
data class AppSettings(
    val connectionType: ConnectionType = ConnectionType.BLUETOOTH,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    /** USB cable chosen ("VID:PID") and the serial speed that worked with it. */
    val usbDevice: String? = null,
    val usbBaud: Int? = null,
    val protocol: ObdProtocol = ObdProtocol.AUTO,
    val adaptiveTiming: Int = 1,
    val elmTimeoutMs: Int = 0,
    val fastMode: Boolean = true,
    val batching: Boolean = true,
    val units: UnitSystem = UnitSystem.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = true,
    val autoConnect: Boolean = false,
    val fuel: FuelKind = FuelKind.AUTO,
    val displacementL: Float = 2.0f,
    val dashboard: List<String> = DEFAULT_DASHBOARD,
    val alerts: List<AlertRule> = emptyList(),
    val shiftLightRpm: Int = 0,
    val fuelPricePerL: Float = 0f,
    /** Manually chosen brand id; null = auto-detect from the VIN. */
    val brandId: String? = null,
    val customPids: List<CustomPid> = emptyList(),
    /** Modules found with "Discover modules" or added by hand. */
    val customModules: List<EcuModule> = emptyList(),
    /** Adapter has a manual HS-CAN / MS-CAN switch (common "ELM327 switch" adapters for Ford / Mazda). */
    val msCanSwitch: Boolean = false,
    /** Record a GPS track for saved trips (needs location permission). */
    val tripGps: Boolean = true,
    /** The first-run setup was completed or skipped. */
    val onboarded: Boolean = false,
    /** Protocol spoken by the simulated car in demo mode. */
    val demoProtocol: ObdProtocol = ObdProtocol.ISO_15765_4_CAN_11_500,
    /** Demo mode simulates an unreliable adapter (fragmented replies, noise, errors, lost prompts). */
    val demoFaults: Boolean = false,
    /** "system", "en" or "es". */
    val language: String = "system",
    /** Country used for vehicle lookups (ISO code); empty = from the phone's region. */
    val specsCountry: String = "",
    /** Optional DVLA Vehicle Enquiry Service key for UK plate lookups. */
    val dvlaApiKey: String = "",
    /** Optional Claude API key for the AI diagnosis assistant (stays on the phone, only sent to Anthropic). */
    val claudeApiKey: String = "",
    /** Speak alerts (thresholds, overheating, not charging, particulate filter cleaning). */
    val voiceAlerts: Boolean = false,
) {
    companion object {
        val DEFAULT_DASHBOARD = listOf("010C", "010D", "0105", "0104", "0111", "CALC_FUEL", "0142", "CALC_BOOST")

        /** Protocols the demo simulator can speak. */
        val DEMO_PROTOCOLS = listOf(
            ObdProtocol.ISO_15765_4_CAN_11_500, ObdProtocol.ISO_15765_4_CAN_29_500, ObdProtocol.ISO_14230_4_KWP_FAST,
            ObdProtocol.ISO_14230_4_KWP_5BAUD, ObdProtocol.ISO_9141_2, ObdProtocol.J1850_VPW, ObdProtocol.J1850_PWM,
        )
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val connectionType = stringPreferencesKey("connection_type")
        val deviceAddress = stringPreferencesKey("device_address")
        val deviceName = stringPreferencesKey("device_name")
        val wifiHost = stringPreferencesKey("wifi_host")
        val wifiPort = intPreferencesKey("wifi_port")
        val usbDevice = stringPreferencesKey("usb_device")
        val usbBaud = intPreferencesKey("usb_baud")
        val protocol = stringPreferencesKey("protocol")
        val adaptive = intPreferencesKey("adaptive_timing")
        val timeout = intPreferencesKey("elm_timeout")
        val fastMode = booleanPreferencesKey("fast_mode")
        val batching = booleanPreferencesKey("batching")
        val units = stringPreferencesKey("units")
        val theme = stringPreferencesKey("theme")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val fuel = stringPreferencesKey("fuel")
        val displacement = floatPreferencesKey("displacement")
        val dashboard = stringPreferencesKey("dashboard")
        val alerts = stringPreferencesKey("alerts")
        val shiftLight = intPreferencesKey("shift_light")
        val fuelPrice = floatPreferencesKey("fuel_price")
        val brand = stringPreferencesKey("brand")
        val customPids = stringPreferencesKey("custom_pids")
        val customModules = stringPreferencesKey("custom_modules")
        val msCanSwitch = booleanPreferencesKey("ms_can_switch")
        val tripGps = booleanPreferencesKey("trip_gps")
        val onboarded = booleanPreferencesKey("onboarded")
        val demoProtocol = stringPreferencesKey("demo_protocol")
        val demoFaults = booleanPreferencesKey("demo_faults")
        val language = stringPreferencesKey("language")
        val specsCountry = stringPreferencesKey("specs_country")
        val dvlaKey = stringPreferencesKey("dvla_key")
        val claudeKey = stringPreferencesKey("claude_key")
        val voiceAlerts = booleanPreferencesKey("voice_alerts")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T : Enum<T>> enumOf(v: String?, d: T): T = v?.let { s -> enumValues<T>().firstOrNull { it.name == s } } ?: d

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            connectionType = enumOf(p[K.connectionType], d.connectionType),
            deviceAddress = p[K.deviceAddress],
            deviceName = p[K.deviceName],
            wifiHost = p[K.wifiHost] ?: d.wifiHost,
            wifiPort = p[K.wifiPort] ?: d.wifiPort,
            usbDevice = p[K.usbDevice],
            usbBaud = p[K.usbBaud],
            protocol = enumOf(p[K.protocol], d.protocol),
            adaptiveTiming = p[K.adaptive] ?: d.adaptiveTiming,
            elmTimeoutMs = p[K.timeout] ?: d.elmTimeoutMs,
            fastMode = p[K.fastMode] ?: d.fastMode,
            batching = p[K.batching] ?: d.batching,
            units = enumOf(p[K.units], d.units),
            theme = enumOf(p[K.theme], d.theme),
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            autoConnect = p[K.autoConnect] ?: d.autoConnect,
            fuel = enumOf(p[K.fuel], d.fuel),
            displacementL = p[K.displacement] ?: d.displacementL,
            dashboard = p[K.dashboard]?.split(',')?.filter { it.isNotBlank() } ?: d.dashboard,
            alerts = p[K.alerts]?.let { runCatching { json.decodeFromString<List<AlertRule>>(it) }.getOrNull() } ?: d.alerts,
            shiftLightRpm = p[K.shiftLight] ?: d.shiftLightRpm,
            fuelPricePerL = p[K.fuelPrice] ?: d.fuelPricePerL,
            brandId = p[K.brand],
            customPids = p[K.customPids]?.let { runCatching { json.decodeFromString<List<CustomPid>>(it) }.getOrNull() } ?: d.customPids,
            customModules = p[K.customModules]?.let { runCatching { json.decodeFromString<List<EcuModule>>(it) }.getOrNull() } ?: d.customModules,
            msCanSwitch = p[K.msCanSwitch] ?: d.msCanSwitch,
            tripGps = p[K.tripGps] ?: d.tripGps,
            onboarded = p[K.onboarded] ?: d.onboarded,
            demoProtocol = enumOf(p[K.demoProtocol], d.demoProtocol).takeIf { it in AppSettings.DEMO_PROTOCOLS } ?: d.demoProtocol,
            demoFaults = p[K.demoFaults] ?: d.demoFaults,
            language = p[K.language] ?: d.language,
            specsCountry = p[K.specsCountry] ?: d.specsCountry,
            dvlaApiKey = p[K.dvlaKey] ?: d.dvlaApiKey,
            claudeApiKey = p[K.claudeKey] ?: d.claudeApiKey,
            voiceAlerts = p[K.voiceAlerts] ?: d.voiceAlerts,
        )
    }

    /** Reads the stored settings once (used for one-time migrations before the state flow is warm). */
    suspend fun readOnce(): AppSettings = read(context.dataStore.data.first())

    val settings: StateFlow<AppSettings> = context.dataStore.data.map(::read)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(read(p))
            p[K.connectionType] = s.connectionType.name
            s.deviceAddress?.let { p[K.deviceAddress] = it } ?: p.remove(K.deviceAddress)
            s.deviceName?.let { p[K.deviceName] = it } ?: p.remove(K.deviceName)
            p[K.wifiHost] = s.wifiHost
            p[K.wifiPort] = s.wifiPort
            s.usbDevice?.let { p[K.usbDevice] = it } ?: p.remove(K.usbDevice)
            s.usbBaud?.let { p[K.usbBaud] = it } ?: p.remove(K.usbBaud)
            p[K.protocol] = s.protocol.name
            p[K.adaptive] = s.adaptiveTiming
            p[K.timeout] = s.elmTimeoutMs
            p[K.fastMode] = s.fastMode
            p[K.batching] = s.batching
            p[K.units] = s.units.name
            p[K.theme] = s.theme.name
            p[K.keepScreenOn] = s.keepScreenOn
            p[K.autoConnect] = s.autoConnect
            p[K.fuel] = s.fuel.name
            p[K.displacement] = s.displacementL
            p[K.dashboard] = s.dashboard.joinToString(",")
            p[K.alerts] = json.encodeToString(s.alerts)
            p[K.shiftLight] = s.shiftLightRpm
            p[K.fuelPrice] = s.fuelPricePerL
            s.brandId?.let { p[K.brand] = it } ?: p.remove(K.brand)
            p[K.customPids] = json.encodeToString(s.customPids)
            p[K.customModules] = json.encodeToString(s.customModules)
            p[K.msCanSwitch] = s.msCanSwitch
            p[K.tripGps] = s.tripGps
            p[K.onboarded] = s.onboarded
            p[K.demoProtocol] = s.demoProtocol.name
            p[K.demoFaults] = s.demoFaults
            p[K.language] = s.language
            p[K.specsCountry] = s.specsCountry
            p[K.dvlaKey] = s.dvlaApiKey
            p[K.claudeKey] = s.claudeApiKey
            p[K.voiceAlerts] = s.voiceAlerts
        }
    }
}
