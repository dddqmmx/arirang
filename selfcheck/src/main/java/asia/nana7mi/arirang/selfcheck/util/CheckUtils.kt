package asia.nana7mi.arirang.selfcheck.util

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object CheckUtils {
    private const val TAG = "Arirang/CheckUtils"

    fun runGetprop(key: String): String? {
        return runCatching {
            val process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() }
            process.waitFor(1500, TimeUnit.MILLISECONDS)
            output?.trim()?.takeUnless { it.isBlank() }
        }.getOrNull()
    }

    fun readRawFile(path: String): String? {
        return runCatching {
            val file = File(path)
            if (!file.canRead()) null else file.readText()
        }.getOrNull()
    }

    fun readSystemProperty(key: String): String? {
        return runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            systemPropertiesClass
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String
        }.getOrNull()
    }

    fun String.maskMiddle(): String {
        return if (length <= 8) this else "${take(4)}****${takeLast(4)}"
    }

    fun String?.maskForPhoneDiag(): String {
        if (this == null) return "null"
        if (isBlank()) return "blank(len=$length)"
        return if (length <= 4) "len=$length,value=$this" else "len=$length,tail=${takeLast(4)}"
    }

    fun visibleListResult(
        values: List<String>,
        visibleStatus: String,
        emptyText: String,
        context: android.content.Context
    ): asia.nana7mi.arirang.selfcheck.model.CheckResult {
        val filtered = values.filter { it.isNotBlank() }
        return asia.nana7mi.arirang.selfcheck.model.CheckResult(
            if (filtered.isEmpty()) asia.nana7mi.arirang.selfcheck.model.CheckState.BLOCKED else asia.nana7mi.arirang.selfcheck.model.CheckState.VISIBLE,
            if (filtered.isEmpty()) context.getString(asia.nana7mi.arirang.selfcheck.R.string.self_check_status_not_visible) else visibleStatus,
            if (filtered.isEmpty()) emptyText else filtered.joinToString("\n")
        )
    }

    fun Exception.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }
}
