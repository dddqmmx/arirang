package asia.nana7mi.arirang.model

data class DevicePreset(
    val id: String,
    val label: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val display: String,
    val host: String,
    val buildId: String,
    val tags: String,
    val type: String,
    val user: String,
    val fingerprint: String,
    val time: Long
)

object DevicePresetCatalog {
    const val CUSTOM_ID = "custom"

    private val defaultPreset = DevicePreset(
        id = "arirang_ap121",
        label = "Arirang AP121",
        brand = "Arirang",
        manufacturer = "May 11 Factory",
        model = "AP121",
        device = "AP121",
        product = "AP121",
        board = "AP121",
        hardware = "mt6589",
        display = "AP121-user 4.2.1 JOP40D release-keys",
        host = "android-build",
        buildId = "JOP40D",
        tags = "release-keys",
        type = "user",
        user = "android-build",
        fingerprint = "Arirang/AP121/AP121:4.2.1/JOP40D/20140827:user/release-keys",
        time = 1409097600000L
    )

    val ALL: List<DevicePreset> = listOf(
        defaultPreset,
        DevicePreset(
            id = "pixel_9_pro",
            label = "Google Pixel 9 Pro",
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 9 Pro",
            device = "caiman",
            product = "caiman",
            board = "caiman",
            hardware = "caiman",
            display = "BP4A.251205.006 release-keys",
            host = "android-build",
            buildId = "BP4A.251205.006",
            tags = "release-keys",
            type = "user",
            user = "android-build",
            fingerprint = "google/caiman/caiman:15/BP4A.251205.006/1234567:user/release-keys",
            time = 1764892800000L
        ),
        DevicePreset(
            id = "pixel_8_pro",
            label = "Google Pixel 8 Pro",
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            device = "husky",
            product = "husky",
            board = "husky",
            hardware = "husky",
            display = "AP2A.240805.005 release-keys",
            host = "android-build",
            buildId = "AP2A.240805.005",
            tags = "release-keys",
            type = "user",
            user = "android-build",
            fingerprint = "google/husky/husky:14/AP2A.240805.005/12025142:user/release-keys",
            time = 1722816000000L
        ),
        DevicePreset(
            id = "galaxy_s24_ultra",
            label = "Samsung Galaxy S24 Ultra",
            brand = "samsung",
            manufacturer = "samsung",
            model = "SM-S928B",
            device = "e3q",
            product = "e3qxxx",
            board = "pineapple",
            hardware = "qcom",
            display = "UP1A.231005.007.S928BXXS4AXI3",
            host = "SWDK6316",
            buildId = "UP1A.231005.007",
            tags = "release-keys",
            type = "user",
            user = "android-build",
            fingerprint = "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXS4AXI3:user/release-keys",
            time = 1726531200000L
        ),
        DevicePreset(
            id = "xiaomi_14",
            label = "Xiaomi 14",
            brand = "Xiaomi",
            manufacturer = "Xiaomi",
            model = "23127PN0CC",
            device = "houji",
            product = "houji",
            board = "pineapple",
            hardware = "qcom",
            display = "UKQ1.230804.001 release-keys",
            host = "builder-miui",
            buildId = "UKQ1.230804.001",
            tags = "release-keys",
            type = "user",
            user = "builder",
            fingerprint = "Xiaomi/houji/houji:14/UKQ1.230804.001/V816.0.1.0.UNCCNXM:user/release-keys",
            time = 1719792000000L
        ),
        DevicePreset(
            id = "oneplus_12",
            label = "OnePlus 12",
            brand = "OnePlus",
            manufacturer = "OnePlus",
            model = "CPH2581",
            device = "OP5D83L1",
            product = "OP5D83L1",
            board = "pineapple",
            hardware = "qcom",
            display = "CPH2581_14.0.0.850(EX01)",
            host = "ubuntu-121-114",
            buildId = "UKQ1.230924.001",
            tags = "release-keys",
            type = "user",
            user = "root",
            fingerprint = "OnePlus/CPH2581/OP5D83L1:14/UKQ1.230924.001/1735665600000:user/release-keys",
            time = 1735665600000L
        )
    )

    fun defaultPreset(): DevicePreset = defaultPreset
}
