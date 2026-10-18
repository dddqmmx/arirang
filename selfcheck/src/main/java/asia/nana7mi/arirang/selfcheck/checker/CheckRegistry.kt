package asia.nana7mi.arirang.selfcheck.checker

/**
 * The checks this build runs, in the order the sections appear.
 *
 * The only place that knows the set of them. The section list, the navigation strip, the summary
 * and the export dialog all read this list, so adding or removing a check is one line here plus
 * the check itself — there is no layout to edit and no chip to name, which is what used to make a
 * new check a two-file XML change and a literal in the activity.
 */
object CheckRegistry {

    val checks: List<Check> = listOf(
        UniqueIdentifiersChecker(),
        BuildInfoChecker(),
        SystemPropertiesChecker(),
        TimeZoneChecker(),
        LocaleChecker(),
        RawKernelFilesChecker(),
        TelephonyChecker(),
        SimChecker(),
        DrmChecker(),
        SettingsChecker(),
        SensorsChecker(),
        SensorPrecisionChecker(),
        InstalledAppsChecker(),
        LocationChecker(),
        WifiChecker(),
        AccountsChecker(),
        NetworkChecker(),
        VpnChecker(),
        BluetoothChecker(),
    )
}
