package asia.nana7mi.arirang.hook.location

internal object LocationDebugConfig {
    const val DEBUG_HARDCODED_CONFIG = false
    const val DEBUG_PACKAGE_NAME = "asia.nana7mi.arirang.selfcheck"
    const val DEBUG_LATITUDE = 39.019444
    const val DEBUG_LONGITUDE = 125.738052
    const val DEBUG_PACKAGE_LATITUDE = 35.681236
    const val DEBUG_PACKAGE_LONGITUDE = 139.767125

    val debugConfig = LocationHookConfig(
        enabled = true,
        defaultProfile = LocationProfile(
            latitude = DEBUG_LATITUDE,
            longitude = DEBUG_LONGITUDE
        ),
        perPackage = mapOf(
            DEBUG_PACKAGE_NAME to LocationProfile(
                latitude = DEBUG_PACKAGE_LATITUDE,
                longitude = DEBUG_PACKAGE_LONGITUDE
            )
        )
    )
}
