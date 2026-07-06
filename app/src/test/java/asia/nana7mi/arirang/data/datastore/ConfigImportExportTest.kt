package asia.nana7mi.arirang.data.datastore

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigImportExportTest {

    @Test
    fun submoduleConfigJson_includesAllModuleSnapshots() {
        val json = buildFullConfigJson()
        val snapshotKeys = listOf(
            "simConfigSnapshot",
            "uniqueIdentifierConfigSnapshot",
            "hookLogConfigSnapshot",
            "wifiConfigSnapshot",
            "bluetoothConfigSnapshot",
            "locationConfigSnapshot",
            "packageListConfigSnapshot",
            "sensorConfigSnapshot",
            "globalConfigSnapshot"
        )
        for (key in snapshotKeys) {
            assertTrue("Missing snapshot key: $key", json.has(key))
            val value = json.getString(key)
            assertFalse("Snapshot $key should not be empty", value.isBlank())
        }
    }

    @Test
    fun submoduleConfigJson_includesAllModuleVersions() {
        val json = buildFullConfigJson()
        val versionKeys = listOf(
            "simConfigVersion",
            "uniqueIdentifierConfigVersion",
            "hookLogConfigVersion",
            "wifiConfigVersion",
            "bluetoothConfigVersion",
            "locationConfigVersion",
            "packageListConfigVersion",
            "sensorConfigVersion",
            "globalConfigVersion"
        )
        for (key in versionKeys) {
            assertTrue("Missing version key: $key", json.has(key))
            assertTrue("Version $key should be non-negative", json.getLong(key) >= 0L)
        }
    }

    @Test
    fun packageVisibilitySnapshot_hasCompleteStructure() {
        val snapshot = buildPackageVisibilitySnapshot(
            enabled = true,
            defaultMode = "ALL_VISIBLE",
            defaultTemplateId = null,
            templatesJson = "[{\"id\":\"t1\",\"name\":\"Test\",\"listMode\":\"WHITELIST\"}]",
            appRulesJson = "[{\"packageName\":\"com.test\",\"mode\":\"ALL_HIDDEN\"}]"
        )
        assertEquals(true, snapshot.get("enabled"))
        assertTrue(snapshot.has("last_modified"))
        assertEquals("ALL_VISIBLE", snapshot.getString("default_display_mode"))
        assertFalse(snapshot.has("default_template_id"))
        assertNotNull(snapshot.getString("visibility_templates"))
        assertNotNull(snapshot.getString("visibility_app_rules"))
    }

    @Test
    fun packageVisibilitySnapshot_defaultTemplateSet() {
        val snapshot = buildPackageVisibilitySnapshot(
            enabled = false,
            defaultMode = "TEMPLATE",
            defaultTemplateId = "template_123",
            templatesJson = null,
            appRulesJson = null
        )
        assertEquals(false, snapshot.get("enabled"))
        assertEquals("TEMPLATE", snapshot.getString("default_display_mode"))
        assertEquals("template_123", snapshot.getString("default_template_id"))
    }

    @Test
    fun simSnapshot_hasSlotBasedStructure() {
        val snapshot = buildSimSnapshot(
            enabled = true,
            hideSim = false,
            simInfoMapJson = """{"0":{"iccId":"8900000000000000001","countryIso":"kp"},"1":{"iccId":"8900000000000000002","countryIso":"ru"}}"""
        )
        assertEquals("true", snapshot.getString("enabled"))
        assertEquals("false", snapshot.getString("hide_sim"))
        assertTrue(snapshot.has("last_modified"))
        assertNotNull(snapshot.getString("sim_info_map"))
    }

    @Test
    fun uniqueIdentifierSnapshot_hasAllIdFields() {
        val snapshot = buildUniqueIdentifierSnapshot(
            enabled = true,
            androidId = "abcdef0123456789",
            gaid = "00000000-0000-0000-0000-000000000000",
            widevineDrmId = "bbbbccccddddeeeeffff000011112222",
            appSetId = "11111111-1111-1111-1111-111111111111",
            serial = "ABCD1234EFGH",
            imeiBySlotJson = """{"0":"111111111111111","1":"222222222222222"}""",
            tacBySlotJson = """{"0":"11111111","1":"22222222"}"""
        )
        assertEquals("true", snapshot.getString("enabled"))
        assertEquals("abcdef0123456789", snapshot.getString("android_id"))
        assertEquals("00000000-0000-0000-0000-000000000000", snapshot.getString("gaid"))
        assertEquals("bbbbccccddddeeeeffff000011112222", snapshot.getString("widevine_drm_id"))
        assertEquals("11111111-1111-1111-1111-111111111111", snapshot.getString("app_set_id"))
        assertEquals("ABCD1234EFGH", snapshot.getString("serial"))
        assertNotNull(snapshot.getString("imei_by_slot"))
        assertNotNull(snapshot.getString("tac_by_slot"))
    }

    @Test
    fun wifiSnapshot_hasScanResultsArray() {
        val snapshot = buildWifiSnapshot(
            enabled = true,
            currentSsid = "MyWiFi",
            currentBssid = "02:00:00:AA:BB:CC",
            hideScanResults = false,
            scanResultsJson = """[{"ssid":"Neighbor1","bssid":"02:00:00:DD:EE:FF"}]"""
        )
        assertEquals(true, snapshot.get("enabled"))
        assertEquals("MyWiFi", snapshot.getString("current_ssid"))
        assertEquals("02:00:00:AA:BB:CC", snapshot.getString("current_bssid"))
        assertEquals(false, snapshot.get("hide_scan_results"))
    }

    @Test
    fun bluetoothSnapshot_hasDeviceArrays() {
        val snapshot = buildBluetoothSnapshot(
            enabled = false,
            deviceName = "Arirang-BT",
            hideConnected = true,
            hideScan = true,
            connectedDevicesJson = """[{"name":"Device1","address":"AA:BB:CC:DD:EE:FF"}]""",
            scanResultsJson = "[]"
        )
        assertEquals(false, snapshot.get("enabled"))
        assertEquals("Arirang-BT", snapshot.getString("device_name"))
        assertEquals(true, snapshot.get("hide_connected_devices"))
        assertEquals(true, snapshot.get("hide_scan_results"))
    }

    @Test
    fun locationSnapshot_hasCoordinateFields() {
        val snapshot = buildLocationSnapshot(
            enabled = true,
            latitude = 39.019444,
            longitude = 125.738052,
            altitude = 27.0,
            accuracy = 5.0f,
            speed = 0.0f,
            bearing = 0.0f,
            satellites = 12,
            perPackageJson = "{}"
        )
        assertEquals(true, snapshot.get("enabled"))
        assertEquals(39.019444, snapshot.getDouble("latitude"), 0.0001)
        assertEquals(125.738052, snapshot.getDouble("longitude"), 0.0001)
        assertEquals(27.0, snapshot.getDouble("altitude"), 0.0001)
        assertEquals(12, snapshot.getInt("satellites"))
    }

    @Test
    fun hookLogSnapshot_hasAllModuleKeys() {
        val hookLogKeys = listOf(
            "core", "clipboard", "gms", "location", "package_list",
            "settings", "sim", "wifi", "bluetooth", "unique_id", "notify"
        )
        val snapshot = JSONObject()
        snapshot.put("version", 1000L)
        hookLogKeys.forEach { snapshot.put(it, true) }
        val jsonStr = snapshot.toString()

        val parsed = JSONObject(jsonStr)
        assertEquals(1000L, parsed.getLong("version"))
        for (key in hookLogKeys) {
            assertTrue("Missing log key: $key", parsed.has(key))
            assertTrue("Log key $key should be boolean", parsed.get(key) is Boolean)
        }
    }

    @Test
    fun globalConfigSnapshot_hasRequiredKeys() {
        val snapshot = JSONObject()
        snapshot.put("version", 500L)
        snapshot.put("restrict_hot_switching", false)
        val jsonStr = snapshot.toString()

        val parsed = JSONObject(jsonStr)
        assertEquals(500L, parsed.getLong("version"))
        assertEquals(false, parsed.getBoolean("restrict_hot_switching"))
    }

    @Test
    fun sensorSnapshot_hasSummaryCounts() {
        val snapshot = JSONObject()
        snapshot.put("version", 300L)
        snapshot.put("enabled", true)
        snapshot.put("hide_all", false)
        snapshot.put("blacklistSize", 5)
        snapshot.put("injectionSize", 3)
        val jsonStr = snapshot.toString()

        val parsed = JSONObject(jsonStr)
        assertEquals(300L, parsed.getLong("version"))
        assertEquals(true, parsed.getBoolean("enabled"))
        assertEquals(false, parsed.getBoolean("hide_all"))
        assertEquals(5, parsed.getInt("blacklistSize"))
        assertEquals(3, parsed.getInt("injectionSize"))
    }

    @Test
    fun fullConfigJson_imeiAndTacBySlot_areNonEmptyObjects() {
        val json = buildFullConfigJson()
        val imei = json.getJSONObject("imeiBySlot")
        val tac = json.getJSONObject("tacBySlot")
        assertTrue("imeiBySlot should have entries", imei.length() > 0)
        assertTrue("tacBySlot should have entries", tac.length() > 0)
    }

    @Test
    fun fullConfigJson_sensorPrecisionRules_hasCorrectStructure() {
        val json = buildFullConfigJson()
        val rules = json.getJSONArray("sensorPrecisionRules")
        if (rules.length() > 0) {
            val rule = rules.getJSONObject(0)
            assertTrue(rule.has("type"))
            assertTrue(rule.has("level"))
            val level = rule.getInt("level")
            assertTrue("Precision level should be 1-3, got $level", level in 1..3)
        }
    }

    @Test
    fun fullConfigJson_buildInfoFields_areNonEmpty() {
        val json = buildFullConfigJson()
        val buildFields = listOf(
            "buildBrand", "buildModel", "buildDevice",
            "buildFingerprint", "buildManufacturer"
        )
        for (field in buildFields) {
            assertTrue("Missing field: $field", json.has(field))
            assertFalse("Field $field should not be empty", json.getString(field).isBlank())
        }
    }

    @Test
    fun fullConfigJson_deviceInfoEnabledIsConsistentWithBuild() {
        val enabledJson = buildFullConfigJson(deviceEnabled = true)
        val disabledJson = buildFullConfigJson(deviceEnabled = false)
        assertEquals(true, enabledJson.getBoolean("deviceInfoEnabled"))
        assertEquals(false, disabledJson.getBoolean("deviceInfoEnabled"))
    }

    @Test
    fun snapshotRoundTrip_packageVisibility_dataPreserved() {
        val original = buildPackageVisibilitySnapshot(
            enabled = true,
            defaultMode = "ALL_VISIBLE",
            defaultTemplateId = null,
            templatesJson = "[{\"id\":\"t1\",\"name\":\"My Template\",\"listMode\":\"WHITELIST\"}]",
            appRulesJson = "[{\"packageName\":\"com.example\",\"mode\":\"ALL_HIDDEN\"}]"
        )
        val jsonStr = original.toString()
        val restored = JSONObject(jsonStr)

        assertEquals(original.getBoolean("enabled"), restored.getBoolean("enabled"))
        assertEquals(original.getString("default_display_mode"), restored.getString("default_display_mode"))
        assertEquals(
            original.optString("visibility_templates"),
            restored.optString("visibility_templates")
        )
        assertEquals(
            original.optString("visibility_app_rules"),
            restored.optString("visibility_app_rules")
        )
    }

    @Test
    fun snapshotRoundTrip_simConfig_dataPreserved() {
        val simInfoMap = """{"0":{"iccId":"8900000000000000001","countryIso":"kp","mcc":"467","mnc":"05"}}"""
        val original = buildSimSnapshot(true, false, simInfoMap)
        val restored = JSONObject(original.toString())

        assertEquals(original.getString("enabled"), restored.getString("enabled"))
        assertEquals(original.getString("hide_sim"), restored.getString("hide_sim"))
        assertEquals(original.getString("sim_info_map"), restored.getString("sim_info_map"))
    }

    @Test
    fun snapshotRoundTrip_uniqueIdentifier_dataPreserved() {
        val original = buildUniqueIdentifierSnapshot(
            enabled = true,
            androidId = "abcdef01",
            gaid = "gaid-1234",
            widevineDrmId = "drm-5678",
            appSetId = "appset-9012",
            serial = "SERIAL01",
            imeiBySlotJson = """{"0":"111111111111111"}""",
            tacBySlotJson = """{"0":"11111111"}"""
        )
        val restored = JSONObject(original.toString())

        assertEquals(original.getString("enabled"), restored.getString("enabled"))
        assertEquals(original.getString("android_id"), restored.getString("android_id"))
        assertEquals(original.getString("gaid"), restored.getString("gaid"))
        assertEquals(original.getString("widevine_drm_id"), restored.getString("widevine_drm_id"))
        assertEquals(original.getString("app_set_id"), restored.getString("app_set_id"))
        assertEquals(original.getString("serial"), restored.getString("serial"))
        assertEquals(original.getString("imei_by_slot"), restored.getString("imei_by_slot"))
        assertEquals(original.getString("tac_by_slot"), restored.getString("tac_by_slot"))
    }

    @Test
    fun snapshotRoundTrip_wifiConfig_dataPreserved() {
        val original = buildWifiSnapshot(
            enabled = true,
            currentSsid = "WiFi-Name",
            currentBssid = "AA:BB:CC:DD:EE:FF",
            hideScanResults = false,
            scanResultsJson = """[{"ssid":"S1","bssid":"11:22:33:44:55:66"}]"""
        )
        val restored = JSONObject(original.toString())

        assertEquals(original.getBoolean("enabled"), restored.getBoolean("enabled"))
        assertEquals(original.getString("current_ssid"), restored.getString("current_ssid"))
        assertEquals(original.getString("current_bssid"), restored.getString("current_bssid"))
    }

    @Test
    fun snapshotRoundTrip_bluetoothConfig_dataPreserved() {
        val original = buildBluetoothSnapshot(
            enabled = false,
            deviceName = "BT-Device",
            hideConnected = true,
            hideScan = true,
            connectedDevicesJson = """[{"name":"Dev","address":"00:11:22:33:44:55"}]""",
            scanResultsJson = "[]"
        )
        val restored = JSONObject(original.toString())

        assertEquals(original.getBoolean("enabled"), restored.getBoolean("enabled"))
        assertEquals(original.getString("device_name"), restored.getString("device_name"))
        assertEquals(original.getBoolean("hide_connected_devices"), restored.getBoolean("hide_connected_devices"))
        assertEquals(original.getBoolean("hide_scan_results"), restored.getBoolean("hide_scan_results"))
    }

    @Test
    fun snapshotRoundTrip_locationConfig_dataPreserved() {
        val original = buildLocationSnapshot(
            enabled = true,
            latitude = 37.5665,
            longitude = 126.9780,
            altitude = 38.0,
            accuracy = 3.0f,
            speed = 1.5f,
            bearing = 90.0f,
            satellites = 8,
            perPackageJson = """{"com.test":{"enabled":true,"latitude":35.0,"longitude":135.0}}"""
        )
        val restored = JSONObject(original.toString())

        assertEquals(original.getBoolean("enabled"), restored.getBoolean("enabled"))
        assertEquals(original.getDouble("latitude"), restored.getDouble("latitude"), 0.0001)
        assertEquals(original.getDouble("longitude"), restored.getDouble("longitude"), 0.0001)
        assertEquals(original.getInt("satellites"), restored.getInt("satellites"))
    }

    @Test
    fun fullConfigJson_allSnapshotsAreValidJsonStrings() {
        val json = buildFullConfigJson()
        val snapshotKeys = listOf(
            "simConfigSnapshot", "uniqueIdentifierConfigSnapshot",
            "hookLogConfigSnapshot", "wifiConfigSnapshot",
            "bluetoothConfigSnapshot", "locationConfigSnapshot",
            "packageListConfigSnapshot", "sensorConfigSnapshot",
            "globalConfigSnapshot"
        )
        for (key in snapshotKeys) {
            val snapshotStr = json.getString(key)
            assertFalse("Snapshot $key is empty", snapshotStr.isBlank())
            val parsed = JSONObject(snapshotStr)
            assertTrue("Snapshot $key should have keys", parsed.keys().hasNext())
        }
    }

    @Test
    fun fullConfigJson_simOperatorFields_areCommaSeparated() {
        val json = buildFullConfigJson()
        val simOperatorFields = listOf(
            "gsmSimOperatorIsoCountry", "gsmOperatorIsoCountry",
            "gsmSimOperatorNumeric", "gsmOperatorNumeric",
            "gsmSimOperatorAlpha", "gsmOperatorAlpha"
        )
        for (field in simOperatorFields) {
            assertTrue("Missing SIM operator field: $field", json.has(field))
            val value = json.getString(field)
            assertTrue("SIM operator field $field should be non-empty", value.isNotBlank())
            assertTrue(
                "SIM operator field $field should be comma-separated: $value",
                value.contains(",")
            )
        }
    }

    @Test
    fun fullConfigJson_uniqueIdentifierFields_present() {
        val json = buildFullConfigJson()
        val idFields = listOf(
            "uniqueIdentifierEnabled",
            "androidId", "gaid", "widevineDrmId",
            "appSetId", "serial"
        )
        for (field in idFields) {
            assertTrue("Missing unique identifier field: $field", json.has(field))
        }
        assertEquals(true, json.getBoolean("uniqueIdentifierEnabled"))
        assertFalse("widevineDrmId should not be empty", json.getString("widevineDrmId").isBlank())
    }

    @Test
    fun fullConfigJson_sensorFields_present() {
        val json = buildFullConfigJson(enabled = true, sensorEnabled = true)
        val sensorFields = listOf(
            "sensorConfigEnabled", "sensorHideAll",
            "sensorGlobalVendorReplacement", "sensorVendorKeywords",
            "sensorBlacklist", "sensorOverrides", "sensorInjections",
            "sensorPrecisionRules"
        )
        for (field in sensorFields) {
            assertTrue("Missing sensor field: $field", json.has(field))
        }
        assertEquals(true, json.getBoolean("sensorConfigEnabled"))
        assertFalse(json.getBoolean("sensorHideAll"))
        assertTrue(json.getJSONArray("sensorVendorKeywords").length() >= 1)
    }

    // -- Helper builders for snapshots --

    private fun buildPackageVisibilitySnapshot(
        enabled: Boolean,
        defaultMode: String,
        defaultTemplateId: String?,
        templatesJson: String?,
        appRulesJson: String?
    ): JSONObject {
        val json = JSONObject()
        json.put("enabled", enabled)
        json.put("last_modified", 1000000L)
        json.put("default_display_mode", defaultMode)
        if (defaultTemplateId != null) {
            json.put("default_template_id", defaultTemplateId)
        }
        if (templatesJson != null) {
            json.put("visibility_templates", templatesJson)
        }
        if (appRulesJson != null) {
            json.put("visibility_app_rules", appRulesJson)
        }
        return json
    }

    private fun buildSimSnapshot(
        enabled: Boolean,
        hideSim: Boolean,
        simInfoMapJson: String
    ): JSONObject {
        val json = JSONObject()
        json.put("enabled", enabled.toString())
        json.put("hide_sim", hideSim.toString())
        json.put("last_modified", "2000000")
        json.put("sim_info_map", simInfoMapJson)
        return json
    }

    private fun buildUniqueIdentifierSnapshot(
        enabled: Boolean,
        androidId: String,
        gaid: String,
        widevineDrmId: String,
        appSetId: String,
        serial: String,
        imeiBySlotJson: String,
        tacBySlotJson: String
    ): JSONObject {
        val json = JSONObject()
        json.put("enabled", enabled.toString())
        json.put("last_modified", "3000000")
        json.put("android_id", androidId)
        json.put("gaid", gaid)
        json.put("widevine_drm_id", widevineDrmId)
        json.put("app_set_id", appSetId)
        json.put("serial", serial)
        json.put("imei_by_slot", imeiBySlotJson)
        json.put("tac_by_slot", tacBySlotJson)
        return json
    }

    private fun buildWifiSnapshot(
        enabled: Boolean,
        currentSsid: String,
        currentBssid: String,
        hideScanResults: Boolean,
        scanResultsJson: String
    ): JSONObject {
        val json = JSONObject()
        json.put("enabled", enabled)
        json.put("last_modified", 4000000L)
        json.put("current_ssid", currentSsid)
        json.put("current_bssid", currentBssid)
        json.put("hide_scan_results", hideScanResults)
        json.put("scan_results", JSONArray(scanResultsJson))
        return json
    }

    private fun buildBluetoothSnapshot(
        enabled: Boolean,
        deviceName: String,
        hideConnected: Boolean,
        hideScan: Boolean,
        connectedDevicesJson: String,
        scanResultsJson: String
    ): JSONObject {
        val json = JSONObject()
        json.put("enabled", enabled)
        json.put("last_modified", 5000000L)
        json.put("device_name", deviceName)
        json.put("connected_devices", JSONArray(connectedDevicesJson))
        json.put("hide_connected_devices", hideConnected)
        json.put("hide_scan_results", hideScan)
        json.put("scan_results", JSONArray(scanResultsJson))
        return json
    }

    private fun buildLocationSnapshot(
        enabled: Boolean,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        speed: Float,
        bearing: Float,
        satellites: Int,
        perPackageJson: String
    ): JSONObject {
        val json = JSONObject()
        json.put("enabled", enabled)
        json.put("last_modified", 6000000L)
        json.put("latitude", latitude)
        json.put("longitude", longitude)
        json.put("altitude", altitude)
        json.put("accuracy", accuracy.toDouble())
        json.put("speed", speed.toDouble())
        json.put("bearing", bearing.toDouble())
        json.put("satellites", satellites)
        json.put("per_package", JSONObject(perPackageJson))
        return json
    }

    private fun buildFullConfigJson(
        enabled: Boolean = true,
        deviceEnabled: Boolean = true,
        sensorEnabled: Boolean = false
    ): JSONObject {
        return JSONObject()
            .put("version", 1L)
            .put("enabled", enabled)
            .put("globalConfigVersion", 1L)
            .put("globalConfigSnapshot", """{"version":1,"restrict_hot_switching":false}""")
            .put("deviceInfoEnabled", deviceEnabled)
            .put("devicePresetId", "pixel8a")
            .put("buildBrand", "google")
            .put("buildManufacturer", "Google")
            .put("buildModel", "Pixel 8a")
            .put("buildDevice", "akita")
            .put("buildProduct", "akita")
            .put("buildBoard", "akita")
            .put("buildHardware", "akita")
            .put("buildDisplay", "display")
            .put("buildHost", "host")
            .put("buildId", "AP4A.250105.002")
            .put("buildTags", "release-keys")
            .put("buildType", "user")
            .put("buildUser", "android-build")
            .put("buildFingerprint", "google/akita/akita:15/AP4A/123:user/release-keys")
            .put("buildTime", 1700000000000L)
            .put("uniqueIdentifierEnabled", true)
            .put("androidId", "a1b2c3d4e5f67890")
            .put("gaid", "12345678-1234-1234-1234-123456789abc")
            .put("widevineDrmId", "deadbeefcafebabe0123456789abcdef")
            .put("appSetId", "abcdef01-2345-6789-abcd-ef0123456789")
            .put("serial", "ABC123DEF456")
            .put("imeiBySlot", JSONObject("""{"0":"111111111111111","1":"222222222222222"}"""))
            .put("tacBySlot", JSONObject("""{"0":"11111111","1":"22222222"}"""))
            .put("uniqueIdentifierConfigVersion", 1L)
            .put(
                "uniqueIdentifierConfigSnapshot",
                """{"enabled":"true","last_modified":"1","android_id":"a1b2c3d4e5f67890","gaid":"12345678-1234-1234-1234-123456789abc","widevine_drm_id":"deadbeefcafebabe0123456789abcdef","app_set_id":"abcdef01-2345-6789-abcd-ef0123456789","serial":"ABC123DEF456","imei_by_slot":"{}","tac_by_slot":"{}"}"""
            )
            .put("gsmSimOperatorIsoCountry", "kp,ru")
            .put("gsmOperatorIsoCountry", "kp,ru")
            .put("gsmSimOperatorNumeric", "46705,25001")
            .put("gsmOperatorNumeric", "46705,25001")
            .put("gsmSimOperatorAlpha", "Koryolink,Beeline")
            .put("gsmOperatorAlpha", "Koryolink,Beeline")
            .put("simConfigVersion", 1L)
            .put(
                "simConfigSnapshot",
                """{"enabled":"true","hide_sim":"false","last_modified":"1","sim_info_map":"{}"}"""
            )
            .put("hookLogConfigVersion", 1L)
            .put(
                "hookLogConfigSnapshot",
                """{"version":1,"core":true,"clipboard":true,"gms":true,"location":true,"package_list":true,"settings":true,"sim":true,"wifi":true,"bluetooth":true,"unique_id":true,"notify":true}"""
            )
            .put("wifiConfigVersion", 1L)
            .put(
                "wifiConfigSnapshot",
                """{"enabled":true,"last_modified":1,"current_ssid":"MyWiFi","current_bssid":"02:00:00:11:45:14","hide_scan_results":false,"scan_results":[{"ssid":"Neighbor","bssid":"02:00:00:DD:EE:FF"}]}"""
            )
            .put("bluetoothConfigVersion", 1L)
            .put(
                "bluetoothConfigSnapshot",
                """{"enabled":false,"last_modified":1,"device_name":"Arirang","connected_devices":[{"name":"Device1","address":"AA:BB:CC:DD:EE:FF"}],"hide_connected_devices":false,"hide_scan_results":false,"scan_results":[{"name":"Nearby","address":"02:00:00:DD:EE:FF"}]}"""
            )
            .put("locationConfigVersion", 1L)
            .put(
                "locationConfigSnapshot",
                """{"enabled":true,"last_modified":1,"latitude":39.019444,"longitude":125.738052,"altitude":27,"accuracy":5,"speed":0,"bearing":0,"satellites":12,"per_package":{}}"""
            )
            .put("packageListConfigVersion", 1L)
            .put(
                "packageListConfigSnapshot",
                """{"enabled":true,"last_modified":1,"default_display_mode":"ALL_VISIBLE","visibility_templates":"[]","visibility_app_rules":"[]"}"""
            )
            .put("sensorConfigEnabled", sensorEnabled)
            .put("sensorHideAll", false)
            .put("sensorGlobalVendorReplacement", "GenericSensor")
            .put("sensorVendorKeywords", JSONArray(listOf("xiaomi", "samsung")))
            .put("sensorBlacklist", JSONArray())
            .put("sensorOverrides", JSONArray())
            .put("sensorInjections", JSONArray())
            .put(
                "sensorPrecisionRules",
                JSONArray("""[{"type":1,"level":2},{"type":4,"level":3}]""")
            )
            .put("sensorConfigVersion", 1L)
            .put(
                "sensorConfigSnapshot",
                """{"version":1,"enabled":false,"hide_all":false,"blacklistSize":0,"injectionSize":0}"""
            )
    }
}
