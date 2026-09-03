package asia.nana7mi.arirang.data.sync

import android.content.Context
import android.util.Log
import asia.nana7mi.arirang.data.datastore.BluetoothConfigPrefs
import asia.nana7mi.arirang.data.datastore.DeviceInfoPrefs
import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs
import asia.nana7mi.arirang.data.datastore.HookLogSettings
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.SensorConfigPrefs
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.data.datastore.VpnStatusPrefs
import asia.nana7mi.arirang.data.datastore.WifiConfigPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Synchronizes local SharedPreferences to libxposed RemotePreferences
 * via XposedServiceHelper so hooked system processes can access configurations
 * even without direct file access to /data/user/0.
 */
object RemotePreferencesSyncManager : XposedServiceHelper.OnServiceListener {
    private const val TAG = "RemotePrefsSync"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var xposedService: XposedService? = null

    val isServiceConnected: Boolean
        get() = xposedService != null

    val PREFS_GROUPS = listOf(
        GlobalConfigPrefs.PREFS_NAME,
        DeviceInfoPrefs.PREFS_NAME,
        UniqueIdentifierPrefs.PREFS_NAME,
        SimConfigPrefs.PREFS_NAME,
        HookLogSettings.PREFS_NAME,
        WifiConfigPrefs.PREFS_NAME,
        BluetoothConfigPrefs.PREFS_NAME,
        LocationConfigPrefs.PREFS_NAME,
        "clipboard_visibility_prefs",
        SensorConfigPrefs.PREFS_NAME,
        SystemSettingPrefs.PREFS_NAME,
        VpnStatusPrefs.PREFS_NAME
    )

    fun init(context: Context) {
        this.appContext = context.applicationContext
        runCatching {
            XposedServiceHelper.registerListener(this)
            Log.i(TAG, "Registered XposedServiceHelper listener")
        }.onFailure {
            Log.w(TAG, "Failed to register XposedServiceHelper listener: ${it.message}")
        }
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "XposedService bound: ${service.frameworkName} ${service.frameworkVersion} api=${service.apiVersion}")
        xposedService = service
        appContext?.let { syncAll(it) }
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "XposedService died")
        if (xposedService === service) {
            xposedService = null
        }
    }

    fun syncAll(context: Context) {
        val service = xposedService ?: return
        PREFS_GROUPS.forEach { group ->
            syncGroup(context, service, group)
        }
    }

    fun syncGroup(context: Context, group: String) {
        val service = xposedService ?: return
        syncGroup(context, service, group)
    }

    private fun syncGroup(context: Context, service: XposedService, group: String) {
        runCatching {
            val localPrefs = context.getSharedPreferences(group, Context.MODE_PRIVATE)
            val remotePrefs = service.getRemotePreferences(group)
            val allLocal = localPrefs.all

            val editor = remotePrefs.edit()
            editor.clear()
            allLocal.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as? Set<String>)
                    }
                }
            }
            editor.apply()
            Log.d(TAG, "Synced group $group (${allLocal.size} keys) to RemotePreferences")
        }.onFailure {
            Log.w(TAG, "Failed to sync group $group to RemotePreferences: ${it.message}", it)
        }
    }
}
