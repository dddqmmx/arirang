package asia.nana7mi.arirang.hook.wifi

import android.net.wifi.ScanResult
import asia.nana7mi.arirang.hook.core.HookBridge
import java.lang.reflect.Method

internal fun Method.wrapScanResults(results: List<ScanResult>): Any? {
    return when {
        returnsParceledListSlice() -> runCatching {
            HookBridge.newInstance(returnType, results)
        }.getOrNull()
        returnType.isAssignableFrom(ArrayList::class.java) -> ArrayList(results)
        returnsList() -> runCatching {
            HookBridge.newInstance(returnType, results)
        }.getOrNull()
        else -> null
    }
}

internal fun Method.returnsWifiInfo(): Boolean {
    return returnType.name == "android.net.wifi.WifiInfo"
}

internal fun Method.returnsScanResults(): Boolean {
    return returnsParceledListSlice() || returnsList()
}

private fun Method.returnsParceledListSlice(): Boolean {
    return returnType.name == "com.android.modules.utils.ParceledListSlice" ||
        returnType.name == "android.content.pm.ParceledListSlice"
}

internal fun Method.returnsList(): Boolean {
    return List::class.java.isAssignableFrom(returnType)
}

internal fun Method.signature(): String {
    return "${returnType.name} ${declaringClass.name}.$name(${parameterTypes.joinToString { it.name }})"
}
