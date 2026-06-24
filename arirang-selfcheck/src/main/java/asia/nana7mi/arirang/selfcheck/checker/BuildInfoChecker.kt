package asia.nana7mi.arirang.selfcheck.checker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.util.CheckUtils
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.visibleListResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BuildInfoChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_build_title
    override val navChipId: Int = R.id.navBuildChip

    @SuppressLint("HardwareIds")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val fields = listOfNotNull(
            "Brand" to Build.BRAND,
            "Manufacturer" to Build.MANUFACTURER,
            "Model" to Build.MODEL,
            "Device" to Build.DEVICE,
            "Product" to Build.PRODUCT,
            "Board" to Build.BOARD,
            "Hardware" to Build.HARDWARE,
            "Bootloader" to Build.BOOTLOADER,
            "Display" to Build.DISPLAY,
            "Host" to Build.HOST,
            "ID" to Build.ID,
            "Tags" to Build.TAGS,
            "Type" to Build.TYPE,
            "User" to Build.USER,
            "Build time" to Build.TIME.takeIf { it > 0 }?.toString(),
            "Property gsm.sim.operator.iso-country" to CheckUtils.readSystemProperty("gsm.sim.operator.iso-country"),
            "Property gsm.sim.operator.numeric" to CheckUtils.readSystemProperty("gsm.sim.operator.numeric"),
            "Property gsm.sim.operator.alpha" to CheckUtils.readSystemProperty("gsm.sim.operator.alpha"),
            "Property gsm.operator.iso-country" to CheckUtils.readSystemProperty("gsm.operator.iso-country"),
            "Property gsm.operator.numeric" to CheckUtils.readSystemProperty("gsm.operator.numeric"),
            "Property gsm.operator.alpha" to CheckUtils.readSystemProperty("gsm.operator.alpha")
        ).filter { !it.second.isNullOrBlank() && it.second != Build.UNKNOWN }

        visibleListResult(
            fields.map { "${it.first}: ${it.second}" },
            context.getString(R.string.self_check_status_visible),
            context.getString(R.string.self_check_build_hidden),
            context
        )
    }
}
