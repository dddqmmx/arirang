package asia.nana7mi.arirang.data.datastore.schema

import org.junit.Assert.*
import org.junit.Test

class ConfigSchemaTest {

    @Test
    fun schemaVersion_isPresentInAllSchemas() {
        val schemas = listOf(
            SimConfigSchema().toJson(),
            LocationConfigSchema().toJson(),
            WifiConfigSchema().toJson(),
            BluetoothConfigSchema().toJson(),
            IdentifierConfigSchema().toJson(),
            GlobalConfigSchema().toJson(),
            SensorConfigSchema().toJson(),
            HookLogConfigSchema().toJson(),
            PackageListConfigSchema().toJson()
        )
        for (json in schemas) {
            val version = ConfigSchema.readSchemaVersion(json)
            assertTrue("Schema version should be >= 1, got $version in: $json", version >= 1)
        }
    }

    @Test
    fun lastModified_isAlwaysPresent() {
        val schemas = listOf(
            SimConfigSchema(lastModified = 12345L).toJson(),
            LocationConfigSchema(lastModified = 12345L).toJson(),
            WifiConfigSchema(lastModified = 12345L).toJson(),
            BluetoothConfigSchema(lastModified = 12345L).toJson(),
            IdentifierConfigSchema(lastModified = 12345L).toJson(),
            GlobalConfigSchema(lastModified = 12345L).toJson(),
            SensorConfigSchema(lastModified = 12345L).toJson(),
            HookLogConfigSchema(lastModified = 12345L).toJson(),
            PackageListConfigSchema(lastModified = 12345L).toJson()
        )
        for (json in schemas) {
            val lm = ConfigSchema.readLastModified(json)
            assertEquals("lastModified mismatch", 12345L, lm)
        }
    }

    @Test
    fun readSchemaVersion_handlesInvalidJson() {
        assertEquals(0, ConfigSchema.readSchemaVersion("not json"))
        assertEquals(0, ConfigSchema.readSchemaVersion("{}"))
    }

    @Test
    fun readLastModified_handlesInvalidJson() {
        assertEquals(0L, ConfigSchema.readLastModified("not json"))
        assertEquals(0L, ConfigSchema.readLastModified("{}"))
    }

    @Test
    fun simConfigSchema_roundTrip_preservesAllData() {
        val original = SimConfigSchema(
            enabled = true,
            hideSim = false,
            simProfiles = listOf(
                SimProfileSchema(
                    id = 1, iccId = "8900000000000000001", simSlotIndex = 0,
                    countryIso = "kp", mcc = "467", mnc = "05",
                    carrierName = "Koryolink", displayName = "SIM1"
                ),
                SimProfileSchema(
                    id = 2, iccId = "8900000000000000002", simSlotIndex = 1,
                    countryIso = "ru", mcc = "250", mnc = "01",
                    carrierName = "MTS", isEmbedded = true
                )
            ),
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = SimConfigSchema.fromJson(json)

        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.hideSim, restored.hideSim)
        assertEquals(original.lastModified, restored.lastModified)
        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(2, restored.simProfiles.size)
        assertEquals("8900000000000000001", restored.simProfiles[0].iccId)
        assertEquals("kp", restored.simProfiles[0].countryIso)
        assertEquals("467", restored.simProfiles[0].mcc)
        assertEquals("05", restored.simProfiles[0].mnc)
        assertEquals("Koryolink", restored.simProfiles[0].carrierName)
        assertEquals(true, restored.simProfiles[1].isEmbedded)
    }

    @Test
    fun simConfigSchema_roundTrip_emptyProfiles() {
        val original = SimConfigSchema(enabled = false, hideSim = true)
        val json = original.toJson()
        val restored = SimConfigSchema.fromJson(json)

        assertEquals(false, restored.enabled)
        assertEquals(true, restored.hideSim)
        assertTrue(restored.simProfiles.isEmpty())
    }

    @Test
    fun locationConfigSchema_roundTrip_preservesAllData() {
        val original = LocationConfigSchema(
            enabled = true,
            latitude = 39.019444,
            longitude = 125.738052,
            altitude = 27.0,
            accuracy = 5.0f,
            speed = 1.5f,
            bearing = 90.0f,
            satellites = 8,
            perPackage = mapOf(
                "com.test" to LocationProfileSchema(
                    enabled = true, latitude = 35.0, longitude = 135.0,
                    altitude = 100.0, accuracy = 3.0f, speed = 0.0f, bearing = 180.0f, satellites = 6
                )
            ),
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = LocationConfigSchema.fromJson(json)

        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.latitude, restored.latitude, 0.0001)
        assertEquals(original.longitude, restored.longitude, 0.0001)
        assertEquals(original.altitude, restored.altitude, 0.0001)
        assertEquals(original.accuracy, restored.accuracy)
        assertEquals(original.speed, restored.speed)
        assertEquals(original.bearing, restored.bearing)
        assertEquals(original.satellites, restored.satellites)
        assertEquals(1, restored.perPackage.size)
        val profile = restored.perPackage["com.test"]!!
        assertEquals(35.0, profile.latitude, 0.0001)
        assertEquals(135.0, profile.longitude, 0.0001)
        assertEquals(100.0, profile.altitude, 0.0001)
    }

    @Test
    fun locationConfigSchema_roundTrip_emptyPerPackage() {
        val original = LocationConfigSchema(enabled = false)
        val json = original.toJson()
        val restored = LocationConfigSchema.fromJson(json)

        assertEquals(false, restored.enabled)
        assertTrue(restored.perPackage.isEmpty())
    }

    @Test
    fun wifiConfigSchema_roundTrip_preservesAllData() {
        val original = WifiConfigSchema(
            enabled = true,
            currentSsid = "MyWiFi",
            currentBssid = "02:00:00:AA:BB:CC",
            ipAddress = "10.0.0.42",
            gateway = "10.0.0.1",
            dns1 = "10.0.0.1",
            dns2 = "1.1.1.1",
            hideScanResults = false,
            scanResults = listOf(
                WifiScanNetworkSchema("Neighbor1", "02:00:00:DD:EE:FF"),
                WifiScanNetworkSchema("Neighbor2", "02:00:00:11:22:33")
            ),
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = WifiConfigSchema.fromJson(json)

        assertEquals(original.enabled, restored.enabled)
        assertEquals("MyWiFi", restored.currentSsid)
        assertEquals("02:00:00:AA:BB:CC", restored.currentBssid)
        assertEquals("10.0.0.42", restored.ipAddress)
        assertEquals("10.0.0.1", restored.gateway)
        assertEquals("10.0.0.1", restored.dns1)
        assertEquals("1.1.1.1", restored.dns2)
        assertEquals(false, restored.hideScanResults)
        assertEquals(2, restored.scanResults.size)
        assertEquals("Neighbor1", restored.scanResults[0].ssid)
        assertEquals("02:00:00:DD:EE:FF", restored.scanResults[0].bssid)
    }

    @Test
    fun wifiConfigSchema_roundTrip_emptyScanResults() {
        val original = WifiConfigSchema(enabled = false)
        val json = original.toJson()
        val restored = WifiConfigSchema.fromJson(json)
        assertTrue(restored.scanResults.isEmpty())
    }

    @Test
    fun bluetoothConfigSchema_roundTrip_preservesAllData() {
        val original = BluetoothConfigSchema(
            enabled = true,
            deviceName = "Arirang-BT",
            connectedDevices = listOf(
                BluetoothDeviceSchema("Device1", "AA:BB:CC:DD:EE:FF")
            ),
            hideConnectedDevices = false,
            hideScanResults = true,
            scanResults = listOf(
                BluetoothDeviceSchema("Nearby", "02:00:00:11:22:33")
            ),
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = BluetoothConfigSchema.fromJson(json)

        assertEquals(original.enabled, restored.enabled)
        assertEquals("Arirang-BT", restored.deviceName)
        assertEquals(1, restored.connectedDevices.size)
        assertEquals("Device1", restored.connectedDevices[0].name)
        assertEquals("AA:BB:CC:DD:EE:FF", restored.connectedDevices[0].address)
        assertEquals(false, restored.hideConnectedDevices)
        assertEquals(true, restored.hideScanResults)
        assertEquals(1, restored.scanResults.size)
        assertEquals("Nearby", restored.scanResults[0].name)
    }

    @Test
    fun identifierConfigSchema_roundTrip_preservesAllData() {
        val original = IdentifierConfigSchema(
            enabled = true,
            androidId = "abcdef0123456789",
            gaid = "00000000-0000-0000-0000-000000000000",
            widevineDrmId = "deadbeefcafebabe0123456789abcdef",
            appSetId = "11111111-1111-1111-1111-111111111111",
            serial = "ABCD1234EFGH",
            imeiBySlot = mapOf(0 to "356938031000001", 1 to "356938031000002"),
            tacBySlot = mapOf(0 to "35693803", 1 to "35693803"),
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = IdentifierConfigSchema.fromJson(json)

        assertEquals(original.enabled, restored.enabled)
        assertEquals("abcdef0123456789", restored.androidId)
        assertEquals("00000000-0000-0000-0000-000000000000", restored.gaid)
        assertEquals("deadbeefcafebabe0123456789abcdef", restored.widevineDrmId)
        assertEquals("11111111-1111-1111-1111-111111111111", restored.appSetId)
        assertEquals("ABCD1234EFGH", restored.serial)
        assertEquals(2, restored.imeiBySlot.size)
        assertEquals("356938031000001", restored.imeiBySlot[0])
        assertEquals("356938031000002", restored.imeiBySlot[1])
        assertEquals(2, restored.tacBySlot.size)
        assertEquals("35693803", restored.tacBySlot[0])
    }

    @Test
    fun identifierConfigSchema_roundTrip_emptyMaps() {
        val original = IdentifierConfigSchema(enabled = false)
        val json = original.toJson()
        val restored = IdentifierConfigSchema.fromJson(json)
        assertEquals(false, restored.enabled)
        assertTrue(restored.imeiBySlot.isEmpty())
        assertTrue(restored.tacBySlot.isEmpty())
    }

    @Test
    fun globalConfigSchema_roundTrip_preservesAllData() {
        val original = GlobalConfigSchema(
            restrictHotSwitching = true,
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = GlobalConfigSchema.fromJson(json)

        assertEquals(true, restored.restrictHotSwitching)
        assertEquals(1700000000000L, restored.lastModified)
        assertEquals(GlobalConfigSchema.SCHEMA_VERSION, restored.schemaVersion)
    }

    @Test
    fun globalConfigSchema_roundTrip_defaults() {
        val original = GlobalConfigSchema()
        val json = original.toJson()
        val restored = GlobalConfigSchema.fromJson(json)
        assertEquals(false, restored.restrictHotSwitching)
    }

    @Test
    fun sensorConfigSchema_roundTrip_preservesAllData() {
        val original = SensorConfigSchema(
            enabled = true,
            hideAll = false,
            blacklistSize = 5,
            injectionSize = 3,
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = SensorConfigSchema.fromJson(json)

        assertEquals(true, restored.enabled)
        assertEquals(false, restored.hideAll)
        assertEquals(5, restored.blacklistSize)
        assertEquals(3, restored.injectionSize)
        assertEquals(1700000000000L, restored.lastModified)
    }

    @Test
    fun hookLogConfigSchema_roundTrip_preservesAllData() {
        val original = HookLogConfigSchema(
            core = true, clipboard = false, gms = true,
            location = false, packageList = true, settings = false,
            sim = true, wifi = false, bluetooth = true,
            uniqueId = false, notify = true,
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = HookLogConfigSchema.fromJson(json)

        assertEquals(true, restored.core)
        assertEquals(false, restored.clipboard)
        assertEquals(true, restored.gms)
        assertEquals(false, restored.location)
        assertEquals(true, restored.packageList)
        assertEquals(false, restored.settings)
        assertEquals(true, restored.sim)
        assertEquals(false, restored.wifi)
        assertEquals(true, restored.bluetooth)
        assertEquals(false, restored.uniqueId)
        assertEquals(true, restored.notify)
        assertEquals(1700000000000L, restored.lastModified)
    }

    @Test
    fun hookLogConfigSchema_roundTrip_allDefaults() {
        val original = HookLogConfigSchema()
        val json = original.toJson()
        val restored = HookLogConfigSchema.fromJson(json)

        val fields = listOf(
            restored.core, restored.clipboard, restored.gms,
            restored.location, restored.packageList, restored.settings,
            restored.sim, restored.wifi, restored.bluetooth,
            restored.uniqueId, restored.notify
        )
        assertTrue("All defaults should be true", fields.all { it })
    }

    @Test
    fun packageListConfigSchema_roundTrip_preservesAllData() {
        val original = PackageListConfigSchema(
            enabled = true,
            defaultMode = "TEMPLATE",
            defaultTemplateId = "t1",
            templates = listOf(
                PackageListTemplateSchema(
                    id = "t1", name = "Base",
                    visiblePackages = listOf("com.a", "com.b"),
                    listMode = "WHITELIST"
                ),
                PackageListTemplateSchema(
                    id = "t2", name = "Extended", parentId = "t1",
                    visiblePackages = listOf("com.c"),
                    listMode = "BLACKLIST"
                )
            ),
            appRules = listOf(
                PackageListAppRuleSchema(
                    packageName = "com.test", mode = "ALL_HIDDEN"
                ),
                PackageListAppRuleSchema(
                    packageName = "com.example", mode = "TEMPLATE",
                    templateId = "t2"
                )
            ),
            lastModified = 1700000000000L
        )
        val json = original.toJson()
        val restored = PackageListConfigSchema.fromJson(json)

        assertEquals(true, restored.enabled)
        assertEquals("TEMPLATE", restored.defaultMode)
        assertEquals("t1", restored.defaultTemplateId)
        assertEquals(2, restored.templates.size)
        assertEquals("t1", restored.templates[0].id)
        assertEquals("Base", restored.templates[0].name)
        assertEquals(null, restored.templates[0].parentId)
        assertEquals(listOf("com.a", "com.b"), restored.templates[0].visiblePackages)
        assertEquals("WHITELIST", restored.templates[0].listMode)
        assertEquals("t2", restored.templates[1].id)
        assertEquals("t1", restored.templates[1].parentId)
        assertEquals("BLACKLIST", restored.templates[1].listMode)
        assertEquals(2, restored.appRules.size)
        assertEquals("com.test", restored.appRules[0].packageName)
        assertEquals("ALL_HIDDEN", restored.appRules[0].mode)
        assertEquals(null, restored.appRules[0].templateId)
        assertEquals("com.example", restored.appRules[1].packageName)
        assertEquals("t2", restored.appRules[1].templateId)
    }

    @Test
    fun packageListConfigSchema_roundTrip_nullTemplateId() {
        val original = PackageListConfigSchema(
            enabled = true,
            defaultMode = "ALL_VISIBLE",
            defaultTemplateId = null
        )
        val json = original.toJson()
        val restored = PackageListConfigSchema.fromJson(json)

        assertEquals("ALL_VISIBLE", restored.defaultMode)
        assertNull(restored.defaultTemplateId)
    }

    @Test
    fun masterConfigSchema_toJson_hasAllRequiredFields() {
        val schema = MasterConfigSchema(
            lastModified = 1700000000000L,
            enabled = true,
            globalConfigVersion = 1L,
            globalConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"restrictHotSwitching":false}""",
            deviceInfoEnabled = true,
            buildBrand = "google",
            buildModel = "Pixel 9 Pro",
            buildFingerprint = "google/caiman/caiman:15/test:user/release-keys",
            buildTime = 1700000000000L,
            uniqueIdentifierEnabled = true,
            androidId = "abc123",
            gaid = "guid-123",
            widevineDrmId = "drm-456",
            appSetId = "appset-789",
            serial = "SERIAL01",
            imeiBySlot = mapOf(0 to "111111111111111"),
            tacBySlot = mapOf(0 to "11111111"),
            uniqueIdentifierConfigVersion = 1L,
            uniqueIdentifierConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":true}""",
            gsmSimOperatorIsoCountry = "kp,ru",
            gsmOperatorIsoCountry = "kp,ru",
            gsmSimOperatorNumeric = "46705,25001",
            gsmOperatorNumeric = "46705,25001",
            gsmSimOperatorAlpha = "Koryolink,Beeline",
            gsmOperatorAlpha = "Koryolink,Beeline",
            simConfigVersion = 1L,
            simConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":true,"hideSim":false}""",
            hookLogConfigVersion = 1L,
            hookLogConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"core":true}""",
            wifiConfigVersion = 1L,
            wifiConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":true}""",
            bluetoothConfigVersion = 1L,
            bluetoothConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":false}""",
            locationConfigVersion = 1L,
            locationConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":true}""",
            packageListConfigVersion = 1L,
            packageListConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":true}""",
            sensorConfigEnabled = true,
            sensorHideAll = false,
            sensorGlobalVendorReplacement = "GenericSensor",
            sensorVendorKeywords = listOf("keyword1", "keyword2"),
            sensorBlacklist = listOf(SensorBlacklistEntrySchema(type = 1, nameContains = "sensor", vendorContains = "vendor")),
            sensorOverrides = listOf(SensorOverrideEntrySchema(matchType = 1, newName = "renamed")),
            sensorInjections = listOf(SensorInjectionEntrySchema(name = "Custom", vendor = "Corp", type = 1)),
            sensorPrecisionRules = listOf(SensorPrecisionRuleSchema(type = 1, level = 2)),
            sensorConfigVersion = 1L,
            sensorConfigSnapshot = """{"schemaVersion":1,"lastModified":1,"enabled":true,"hideAll":false}"""
        )

        val json = schema.toJson()
        val parsed = org.json.JSONObject(json)

        assertEquals(1700000000000L, parsed.getLong("version"))
        assertEquals(1, parsed.getInt("schemaVersion"))
        assertEquals(true, parsed.getBoolean("enabled"))

        assertFalse(parsed.getString("globalConfigSnapshot").isBlank())
        assertFalse(parsed.getString("simConfigSnapshot").isBlank())
        assertFalse(parsed.getString("wifiConfigSnapshot").isBlank())
        assertFalse(parsed.getString("bluetoothConfigSnapshot").isBlank())
        assertFalse(parsed.getString("locationConfigSnapshot").isBlank())
        assertFalse(parsed.getString("packageListConfigSnapshot").isBlank())
        assertFalse(parsed.getString("sensorConfigSnapshot").isBlank())
        assertFalse(parsed.getString("uniqueIdentifierConfigSnapshot").isBlank())
        assertFalse(parsed.getString("hookLogConfigSnapshot").isBlank())

        assertEquals("google", parsed.getString("buildBrand"))
        assertEquals("Pixel 9 Pro", parsed.getString("buildModel"))

        val imeiObj = parsed.getJSONObject("imeiBySlot")
        assertEquals("111111111111111", imeiObj.getString("0"))
        assertEquals("11111111", parsed.getJSONObject("tacBySlot").getString("0"))

        assertEquals("kp,ru", parsed.getString("gsmSimOperatorIsoCountry"))
        assertEquals("46705,25001", parsed.getString("gsmSimOperatorNumeric"))

        assertTrue(parsed.getBoolean("sensorConfigEnabled"))
        assertEquals(2, parsed.getJSONArray("sensorVendorKeywords").length())

        val rules = parsed.getJSONArray("sensorPrecisionRules")
        assertEquals(1, rules.length())
        assertEquals(2, rules.getJSONObject(0).getInt("level"))
    }

    @Test
    fun masterConfigSchema_noSensorFields_whenDisabled() {
        val schema = MasterConfigSchema(sensorConfigEnabled = false)
        val json = schema.toJson()
        val parsed = org.json.JSONObject(json)

        assertEquals(false, parsed.getBoolean("sensorConfigEnabled"))
        assertEquals(false, parsed.getBoolean("sensorHideAll"))
        assertTrue(parsed.getJSONArray("sensorBlacklist").length() == 0)
        assertTrue(parsed.getJSONArray("sensorOverrides").length() == 0)
        assertTrue(parsed.getJSONArray("sensorInjections").length() == 0)
        assertTrue(parsed.getJSONArray("sensorPrecisionRules").length() == 0)
    }

    @Test
    fun allSchemas_useConsistentBooleanType() {
        val schemas = listOf(
            SimConfigSchema(enabled = true).toJson(),
            LocationConfigSchema(enabled = true).toJson(),
            WifiConfigSchema(enabled = true).toJson(),
            BluetoothConfigSchema(enabled = true).toJson(),
            IdentifierConfigSchema(enabled = true).toJson(),
            SensorConfigSchema(enabled = true).toJson()
        )
        for (json in schemas) {
            val parsed = org.json.JSONObject(json)
            assertTrue("enabled should be JSON boolean, got: $json", parsed.get("enabled") is Boolean)
        }
    }

    @Test
    fun allSchemas_useConsistentVersionKey() {
        val schemas = listOf(
            SimConfigSchema(lastModified = 42L).toJson(),
            LocationConfigSchema(lastModified = 42L).toJson(),
            WifiConfigSchema(lastModified = 42L).toJson(),
            BluetoothConfigSchema(lastModified = 42L).toJson(),
            IdentifierConfigSchema(lastModified = 42L).toJson(),
            GlobalConfigSchema(lastModified = 42L).toJson(),
            SensorConfigSchema(lastModified = 42L).toJson(),
            HookLogConfigSchema(lastModified = 42L).toJson(),
            PackageListConfigSchema(lastModified = 42L).toJson()
        )
        for (json in schemas) {
            val parsed = org.json.JSONObject(json)
            assertTrue("Should have lastModified key: $json", parsed.has("lastModified"))
            assertEquals("lastModified should be 42: $json", 42L, parsed.getLong("lastModified"))
        }
    }

    @Test
    fun noDoubleEncoding_inNestedObjects() {
        val schema = SimConfigSchema(
            enabled = true,
            simProfiles = listOf(SimProfileSchema(id = 1, iccId = "8900"))
        )
        val json = schema.toJson()
        val parsed = org.json.JSONObject(json)
        val profiles = parsed.getJSONArray("simProfiles")
        assertTrue("simProfiles should be JSON array, not string", profiles is org.json.JSONArray)
        val profile = profiles.getJSONObject(0)
        assertEquals(1, profile.getInt("id"))
        assertEquals("8900", profile.getString("iccId"))
    }

    @Test
    fun identifierConfig_noDoubleEncoding_inSlots() {
        val schema = IdentifierConfigSchema(
            imeiBySlot = mapOf(0 to "123"),
            tacBySlot = mapOf(0 to "456")
        )
        val json = schema.toJson()
        val parsed = org.json.JSONObject(json)
        val imei = parsed.getJSONObject("imeiBySlot")
        assertTrue("imeiBySlot should be JSON object, not string", imei is org.json.JSONObject)
        assertEquals("123", imei.getString("0"))
        val tac = parsed.getJSONObject("tacBySlot")
        assertEquals("456", tac.getString("0"))
    }

    @Test
    fun packageListConfig_noDoubleEncoding_inTemplates() {
        val schema = PackageListConfigSchema(
            templates = listOf(PackageListTemplateSchema(id = "t1", name = "Test"))
        )
        val json = schema.toJson()
        val parsed = org.json.JSONObject(json)
        val templates = parsed.getJSONArray("templates")
        assertTrue("templates should be JSON array, not string", templates is org.json.JSONArray)
        assertEquals("t1", templates.getJSONObject(0).getString("id"))
    }
}
