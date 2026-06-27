package asia.nana7mi.arirang.hook.wifi

import android.net.wifi.ScanResult
import java.lang.reflect.Method

internal fun Method.wrapScanResults(classLoader: ClassLoader, results: List<ScanResult>): Any? {
    return when {
        returnsParceledListSlice() -> parceledListSlice(classLoader, results)
        returnsList() -> results
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
