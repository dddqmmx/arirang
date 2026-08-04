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
            "globalConfigVersion",
            "deviceInfoEnabled", "devicePresetId",
            "buildBrand", "buildManufacturer", "buildModel", "buildDevice",
            "buildProduct", "buildBoard", "buildHardware", "buildDisplay",
            "buildHost", "buildId", "buildTags", "buildType", "buildUser",
            "buildFingerprint", "buildTime",
            "uniqueIdentifierEnabled",
            "androidId", "gaid", "widevineDrmId", "appSetId", "serial",
            "imeiBySlot", "tacBySlot",
            "uniqueIdentifierConfigVersion",
            "gsmSimOperatorIsoCountry", "gsmOperatorIsoCountry",
            "gsmSimOperatorNumeric", "gsmOperatorNumeric",
            "gsmSimOperatorAlpha", "gsmOperatorAlpha",
            "simConfigVersion",
            "hookLogConfigVersion",
            "wifiConfigVersion",
            "bluetoothConfigVersion",
            "locationConfigVersion",
            "packageListConfigVersion",
            "sensorConfigEnabled", "sensorHideAll",
            "sensorGlobalVendorReplacement", "sensorVendorKeywords",
            "sensorBlacklist", "sensorOverrides", "sensorInjections",
            "sensorPrecisionRules",
            "sensorConfigVersion",
            "systemSettingEnabled", "timeZoneGlobal", "timeZoneByPackage",
            "systemSettingConfigVersion"
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

    // write() keeps a config's last written version when that config currently
    // fails validation, looking the key up via CONFIG_KEY_PREFIX. If the map
    // drifts from the keys write() emits, that salvage silently stops working.
    @Test
    fun configKeyPrefixes_matchTheKeysWriteEmits() {
        val json = buildMinimalConfigJson()
        for ((configId, prefix) in SubmoduleConfigFiles.CONFIG_KEY_PREFIX) {
            assertTrue(
                "CONFIG_KEY_PREFIX['$configId'] = '$prefix' but ${prefix}Version is not written",
                json.has("${prefix}Version")
            )
        }
    }

    @Test
    fun configKeyPrefixes_coverEveryVersionKeyWritten() {
        val json = buildMinimalConfigJson()
        val emitted = json.keys().asSequence()
            .filter { it.endsWith("ConfigVersion") }
            .map { it.removeSuffix("Version") }
            .toSet()
        assertEquals(
            "every <prefix>Version key must have a CONFIG_KEY_PREFIX entry so it can be salvaged",
            emitted,
            SubmoduleConfigFiles.CONFIG_KEY_PREFIX.values.toSet()
        )
    }

    // The nine embedded config snapshots were removed: no consumer read them.
    // Each *_config_snapshot field in submodule_config.cpp had exactly one
    // reference, the read_string() that filled it, and the shell layer reads
    // only scalar keys. They were also the dominant contributor to the 64 KiB
    // size ceiling both consumers enforce, above which they ignore the file
    // entirely and fall back to defaults that disable spoofing.
    @Test
    fun configJson_carriesNoEmbeddedSnapshotPayloads() {
        val json = buildMinimalConfigJson()
        val snapshotKeys = json.keys().asSequence().filter { it.endsWith("Snapshot") }.toList()
        assertEquals("no consumer reads these; they only consume the size budget", emptyList<String>(), snapshotKeys)
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
            "systemSettingConfigVersion",
            "globalConfigVersion"
        )
        for (key in versionKeys) {
            assertTrue("$key should be a number", json.optLong(key) != null || !json.has(key))
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

    @Test
    fun timeZoneByPackage_foldsOverridesIntoNativeSemantics() {
        val config = SystemSettingPrefs.Config(
            enabled = true,
            timeZoneId = "Asia/Shanghai",
            perPackage = mapOf(
                // Differs from global -> must be spelled out.
                "com.example.explicit" to SystemSettingPrefs.Override(
                    enabled = true, timeZoneId = "Europe/London"
                ),
                // Empty override follows the global -> omitted (native falls back).
                "com.example.follows" to SystemSettingPrefs.Override(enabled = true),
                // Same as global -> redundant -> omitted.
                "com.example.redundant" to SystemSettingPrefs.Override(
                    enabled = true, timeZoneId = "Asia/Shanghai"
                ),
                // Explicitly disabled while a global is set -> exempt marker.
                "com.example.exempt" to SystemSettingPrefs.Override(enabled = false)
            )
        )

        val map = SubmoduleConfigFiles.buildTimeZoneByPackage(config)
        assertEquals("Europe/London", map.getString("com.example.explicit"))
        assertFalse(map.has("com.example.follows"))
        assertFalse(map.has("com.example.redundant"))
        assertEquals("", map.getString("com.example.exempt"))
    }

    @Test
    fun timeZoneByPackage_disabledConfigEmitsEmptyMap() {
        val config = SystemSettingPrefs.Config(
            enabled = false,
            timeZoneId = "Asia/Shanghai",
            perPackage = mapOf(
                "com.example.explicit" to SystemSettingPrefs.Override(
                    enabled = true, timeZoneId = "Europe/London"
                ),
                "com.example.exempt" to SystemSettingPrefs.Override(enabled = false)
            )
        )
        assertEquals(0, SubmoduleConfigFiles.buildTimeZoneByPackage(config).length())
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
            .put("gsmSimOperatorIsoCountry", simConfig.countryIsoList.joinToString(","))
            .put("gsmOperatorIsoCountry", simConfig.countryIsoList.joinToString(","))
            .put("gsmSimOperatorNumeric", simConfig.operatorNumericList.joinToString(","))
            .put("gsmOperatorNumeric", simConfig.operatorNumericList.joinToString(","))
            .put("gsmSimOperatorAlpha", simConfig.profilesBySlot.toSortedMap().values.joinToString(",") { it.alphaLong })
            .put("gsmOperatorAlpha", simConfig.profilesBySlot.toSortedMap().values.joinToString(",") { it.alphaLong })
            .put("simConfigVersion", 1L)
            .put("hookLogConfigVersion", 1L)
            .put("wifiConfigVersion", 1L)
            .put("bluetoothConfigVersion", 1L)
            .put("locationConfigVersion", 1L)
            .put("packageListConfigVersion", 1L)
            .put("sensorConfigEnabled", sensorEnabled)
            .put("sensorHideAll", false)
            .put("sensorGlobalVendorReplacement", "")
            .put("sensorVendorKeywords", org.json.JSONArray(listOf("vendor1")))
            .put("sensorBlacklist", org.json.JSONArray())
            .put("sensorOverrides", org.json.JSONArray())
            .put("sensorInjections", org.json.JSONArray())
            .put("sensorPrecisionRules", org.json.JSONArray())
            .put("sensorConfigVersion", 1L)
            .put("systemSettingEnabled", false)
            .put("timeZoneGlobal", "")
            .put("timeZoneByPackage", org.json.JSONObject())
            .put("systemSettingConfigVersion", 1L)
    }
}
