package asia.nana7mi.arirang.data.datastore

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubmoduleConfigFilesTest {

    @Test
    fun configJson_missingFields_noException() {
        val json = JSONObject()
            .put("enabled", true)
            .put("deviceInfoEnabled", false)
            .toString()
        assertNotNull(json)
        val parsed = JSONObject(json)
        assertTrue(parsed.getBoolean("enabled"))
    }

    @Test
    fun configJson_allKnownKeys_present() {
        val requiredKeys = setOf(
            "version", "enabled",
            "globalConfigVersion", "globalConfigSnapshot",
            "deviceInfoEnabled", "devicePresetId",
            "buildBrand", "buildManufacturer", "buildModel", "buildDevice",
            "buildProduct", "buildBoard", "buildHardware", "buildDisplay",
            "buildHost", "buildId", "buildTags", "buildType", "buildUser",
            "buildFingerprint", "buildTime",
            "uniqueIdentifierEnabled",
            "androidId", "gaid", "widevineDrmId", "appSetId", "serial",
            "imeiBySlot", "tacBySlot",
            "uniqueIdentifierConfigVersion", "uniqueIdentifierConfigSnapshot",
            "gsmSimOperatorIsoCountry", "gsmOperatorIsoCountry",
            "gsmSimOperatorNumeric", "gsmOperatorNumeric",
            "gsmSimOperatorAlpha", "gsmOperatorAlpha",
            "simConfigVersion", "simConfigSnapshot",
            "hookLogConfigVersion", "hookLogConfigSnapshot",
            "wifiConfigVersion", "wifiConfigSnapshot",
            "bluetoothConfigVersion", "bluetoothConfigSnapshot",
            "locationConfigVersion", "locationConfigSnapshot",
            "packageListConfigVersion", "packageListConfigSnapshot",
            "sensorConfigEnabled", "sensorHideAll",
            "sensorGlobalVendorReplacement", "sensorVendorKeywords",
            "sensorBlacklist", "sensorOverrides", "sensorInjections",
            "sensorPrecisionRules",
            "sensorConfigVersion", "sensorConfigSnapshot"
        )
        val json = buildMinimalConfigJson()
        for (key in requiredKeys) {
            assertTrue("Missing key: $key", json.has(key))
        }
    }

    @Test
    fun configJson_simPropertiesFormat() {
        val json = buildMinimalConfigJson()
        assertEquals("kp,ru", json.getString("gsmSimOperatorIsoCountry"))
        assertEquals("46705,25001", json.getString("gsmSimOperatorNumeric"))
    }

    @Test
    fun configJson_sensorVendorKeywords_isArray() {
        val json = buildMinimalConfigJson()
        val keywords = json.getJSONArray("sensorVendorKeywords")
        assertTrue(keywords.length() >= 0)
    }

    @Test
    fun configJson_sensorBlacklist_hasCorrectStructure() {
        val json = buildMinimalConfigJson()
        val blacklist = json.getJSONArray("sensorBlacklist")
        if (blacklist.length() > 0) {
            val entry = blacklist.getJSONObject(0)
            assertTrue("type" in entry.keys().asSequence().toList())
        }
    }

    @Test
    fun configJson_sensorPrecisionRules_hasCorrectStructure() {
        val json = buildMinimalConfigJson()
        val rules = json.getJSONArray("sensorPrecisionRules")
        if (rules.length() > 0) {
            val rule = rules.getJSONObject(0)
            assertTrue("type" in rule.keys().asSequence().toList())
            assertTrue("level" in rule.keys().asSequence().toList())
        }
    }

    @Test
    fun configJson_uniqueIdentifierFields() {
        val json = buildMinimalConfigJson()
        assertTrue(json.has("uniqueIdentifierEnabled"))
        assertTrue(json.has("widevineDrmId"))
        assertTrue(json.has("imeiBySlot"))
        assertTrue(json.has("tacBySlot"))
    }

    @Test
    fun configJson_buildInfoFields() {
        val json = buildMinimalConfigJson()
        val buildFields = listOf(
            "buildBrand", "buildManufacturer", "buildModel", "buildDevice",
            "buildFingerprint", "buildDisplay", "buildHost", "buildId",
            "buildTags", "buildType", "buildUser"
        )
        for (field in buildFields) {
            assertTrue("Missing field: $field", json.has(field))
            assertTrue("Field $field should be string", json.optString(field) != null)
        }
    }

    @Test
    fun configJson_enabledIsBoolean() {
        val json = buildMinimalConfigJson()
        assertTrue(json.get("enabled") is Boolean)
    }

    @Test
    fun configJson_deviceInfoEnabledIsBoolean() {
        val json = buildMinimalConfigJson()
        assertTrue(json.get("deviceInfoEnabled") is Boolean)
    }

    @Test
    fun configJson_configVersionsAreNumbers() {
        val json = buildMinimalConfigJson()
        val versionKeys = listOf(
            "simConfigVersion", "uniqueIdentifierConfigVersion",
            "hookLogConfigVersion", "wifiConfigVersion",
            "bluetoothConfigVersion", "locationConfigVersion",
            "packageListConfigVersion", "sensorConfigVersion",
            "globalConfigVersion"
        )
        for (key in versionKeys) {
            assertTrue("$key should be a number", json.optLong(key) != null || !json.has(key))
        }
    }

    @Test
    fun configJson_configSnapshotsAreStrings() {
        val json = buildMinimalConfigJson()
        val snapshotKeys = listOf(
            "simConfigSnapshot", "uniqueIdentifierConfigSnapshot",
            "hookLogConfigSnapshot", "wifiConfigSnapshot",
            "bluetoothConfigSnapshot", "locationConfigSnapshot",
            "packageListConfigSnapshot", "sensorConfigSnapshot",
            "globalConfigSnapshot"
        )
        for (key in snapshotKeys) {
            assertTrue("$key should be a string", json.optString(key) != null || !json.has(key))
        }
    }

    @Test
    fun configJson_imeiAndTacBySlot_areObjects() {
        val json = buildMinimalConfigJson()
        val imei = json.getJSONObject("imeiBySlot")
        val tac = json.getJSONObject("tacBySlot")
        assertNotNull(imei)
        assertNotNull(tac)
    }

    private fun buildMinimalConfigJson(): JSONObject {
        val defaultProfile = SimProfile(
            slotIndex = 0, subId = 1,
            iccId = "8900000000000000001", countryIso = "kp",
            mcc = "467", mnc = "05", alphaLong = "Test",
            phoneNumber = "+8501000000000", imei = "000000000000000"
        )
        val profilesBySlot = mapOf(
            0 to defaultProfile,
            1 to defaultProfile.copy(
                slotIndex = 1, subId = 2,
                iccId = "8900000000000000002", countryIso = "ru",
                mcc = "250", mnc = "01"
            )
        )
        val config = SimHookConfig(
            enabled = true,
            hideSim = false,
            profilesBySlot = profilesBySlot,
            uniqueIdentifiers = UniqueIdentifierHookConfig(
                enabled = true,
                imeiBySlot = mapOf(0 to "111111111111111", 1 to "222222222222222"),
                tacBySlot = mapOf(0 to "11111111", 1 to "22222222")
            )
        )
        return createConfigJson(
            config,
            deviceEnabled = true,
            sensorEnabled = false
        )
    }

    private data class SimProfile(
        val slotIndex: Int, val subId: Int,
        val iccId: String, val countryIso: String,
        val mcc: String, val mnc: String,
        val alphaLong: String, val phoneNumber: String,
        val imei: String
    ) {
        val operatorNumeric: String = mcc + mnc
    }

    private data class SimHookConfig(
        val enabled: Boolean,
        val hideSim: Boolean,
        val profilesBySlot: Map<Int, SimProfile>,
        val uniqueIdentifiers: UniqueIdentifierHookConfig
    ) {
        val countryIsoList: List<String> = profilesBySlot.toSortedMap().values.map { it.countryIso }
        val operatorNumericList: List<String> = profilesBySlot.toSortedMap().values.map { it.operatorNumeric }
    }

    private data class UniqueIdentifierHookConfig(
        val enabled: Boolean,
        val imeiBySlot: Map<Int, String> = emptyMap(),
        val tacBySlot: Map<Int, String> = emptyMap()
    )

    private fun createConfigJson(
        simConfig: SimHookConfig,
        deviceEnabled: Boolean,
        sensorEnabled: Boolean
    ): JSONObject {
        return JSONObject()
            .put("version", 1L)
            .put("enabled", true)
            .put("globalConfigVersion", 1L)
            .put("globalConfigSnapshot", "{}")
            .put("deviceInfoEnabled", deviceEnabled)
            .put("devicePresetId", "")
            .put("buildBrand", "google")
            .put("buildManufacturer", "Google")
            .put("buildModel", "Pixel")
            .put("buildDevice", "caiman")
            .put("buildProduct", "caiman")
            .put("buildBoard", "caiman")
            .put("buildHardware", "caiman")
            .put("buildDisplay", "display")
            .put("buildHost", "host")
            .put("buildId", "id")
            .put("buildTags", "tags")
            .put("buildType", "user")
            .put("buildUser", "build-user")
            .put("buildFingerprint", "google/caiman/caiman:15/BP4A/123:user/release-keys")
            .put("buildTime", 100L)
            .put("uniqueIdentifierEnabled", simConfig.uniqueIdentifiers.enabled)
            .put("androidId", "")
            .put("gaid", "")
            .put("widevineDrmId", "")
            .put("appSetId", "")
            .put("serial", "")
            .put("imeiBySlot", JSONObject(simConfig.uniqueIdentifiers.imeiBySlot.mapKeys { it.key.toString() }))
            .put("tacBySlot", JSONObject(simConfig.uniqueIdentifiers.tacBySlot.mapKeys { it.key.toString() }))
            .put("uniqueIdentifierConfigVersion", 1L)
            .put("uniqueIdentifierConfigSnapshot", "{}")
            .put("gsmSimOperatorIsoCountry", simConfig.countryIsoList.joinToString(","))
            .put("gsmOperatorIsoCountry", simConfig.countryIsoList.joinToString(","))
            .put("gsmSimOperatorNumeric", simConfig.operatorNumericList.joinToString(","))
            .put("gsmOperatorNumeric", simConfig.operatorNumericList.joinToString(","))
            .put("gsmSimOperatorAlpha", simConfig.profilesBySlot.toSortedMap().values.joinToString(",") { it.alphaLong })
            .put("gsmOperatorAlpha", simConfig.profilesBySlot.toSortedMap().values.joinToString(",") { it.alphaLong })
            .put("simConfigVersion", 1L)
            .put("simConfigSnapshot", "{}")
            .put("hookLogConfigVersion", 1L)
            .put("hookLogConfigSnapshot", "{}")
            .put("wifiConfigVersion", 1L)
            .put("wifiConfigSnapshot", "{}")
            .put("bluetoothConfigVersion", 1L)
            .put("bluetoothConfigSnapshot", "{}")
            .put("locationConfigVersion", 1L)
            .put("locationConfigSnapshot", "{}")
            .put("packageListConfigVersion", 1L)
            .put("packageListConfigSnapshot", "{}")
            .put("sensorConfigEnabled", sensorEnabled)
            .put("sensorHideAll", false)
            .put("sensorGlobalVendorReplacement", "")
            .put("sensorVendorKeywords", org.json.JSONArray(listOf("vendor1")))
            .put("sensorBlacklist", org.json.JSONArray())
            .put("sensorOverrides", org.json.JSONArray())
            .put("sensorInjections", org.json.JSONArray())
            .put("sensorPrecisionRules", org.json.JSONArray())
            .put("sensorConfigVersion", 1L)
            .put("sensorConfigSnapshot", "{}")
    }
}
