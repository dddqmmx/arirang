package asia.nana7mi.arirang.service

import android.content.Context
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import asia.nana7mi.arirang.data.datastore.PackageVisibilityPrefs

class ConfigProvider(private val context: Context) {

    companion object {
        private const val CONFIG_SIM = "sim"
        private const val CONFIG_UNIQUE_IDENTIFIER = "unique_identifier"
        private const val CONFIG_HOOK_LOG = "hook_log"
        private const val CONFIG_WIFI = "wifi"
        private const val CONFIG_BLUETOOTH = "bluetooth"
        private const val CONFIG_LOCATION = "location"
        private const val CONFIG_PACKAGE_LIST = "package_list"
    }

    private data class HookConfigSource(
        val lastModified: (Context) -> Long,
        val snapshot: (Context) -> String
    )

    private val hookConfigSources = mapOf(
        CONFIG_SIM to HookConfigSource(SimConfigPrefs::lastModified, SimConfigPrefs::buildHookSnapshot),
        CONFIG_UNIQUE_IDENTIFIER to HookConfigSource(
            UniqueIdentifierPrefs::lastModified,
            UniqueIdentifierPrefs::buildHookSnapshot
        ),
        CONFIG_HOOK_LOG to HookConfigSource(HookLogSettings::lastModified, HookLogSettings::buildHookSnapshot),
        CONFIG_WIFI to HookConfigSource(WifiConfigPrefs::lastModified, WifiConfigPrefs::buildHookSnapshot),
        CONFIG_BLUETOOTH to HookConfigSource(BluetoothConfigPrefs::lastModified, BluetoothConfigPrefs::buildHookSnapshot),
        CONFIG_LOCATION to HookConfigSource(LocationConfigPrefs::lastModified, LocationConfigPrefs::buildHookSnapshot),
        CONFIG_PACKAGE_LIST to HookConfigSource(PackageVisibilityPrefs::lastModified, PackageVisibilityPrefs::buildHookSnapshot)
    )

    fun readConfigVersion(configName: String): Long {
        return hookConfigSources[configName]?.lastModified?.invoke(context) ?: 0L
    }

    fun readConfigSnapshot(configName: String): String {
        return hookConfigSources[configName]?.snapshot?.invoke(context).orEmpty()
    }
}
