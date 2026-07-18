package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BluetoothChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_bluetooth_title
    override val navChipId: Int = R.id.navBluetoothChip

    @SuppressLint("MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_bluetooth_permission_hint)
            )
        }

        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null) {
                return@withContext CheckResult(
                    CheckState.BLOCKED,
                    context.getString(R.string.self_check_status_not_supported),
                    context.getString(R.string.self_check_bluetooth_not_supported)
                )
            }

            val deviceName = adapter.name ?: context.getString(R.string.self_check_unknown_name)
            val bondedDevices = adapter.bondedDevices.orEmpty()
            val samples = bondedDevices.take(8).joinToString("\n") { device ->
                val name = device.name ?: context.getString(R.string.self_check_unknown_name)
                "$name\n${device.address}"
            }

            val content = listOfNotNull(
                context.getString(R.string.self_check_bluetooth_name, deviceName),
                samples.takeIf { it.isNotBlank() }
            ).joinToString("\n\n")

            CheckResult(
                CheckState.VISIBLE,
                context.resources.getQuantityString(R.plurals.self_check_bluetooth_status, bondedDevices.size, bondedDevices.size),
                content.ifBlank { context.getString(R.string.self_check_bluetooth_hidden) }
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
