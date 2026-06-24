package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.visibleListResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class NetworkChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_network_title
    override val navChipId: Int = R.id.navNetworkChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val values = interfaces.take(10).mapNotNull { item ->
                val addresses = item.inetAddresses?.toList().orEmpty()
                    .mapNotNull { it.hostAddress }
                    .filterNot { it.isBlank() }
                    .take(4)
                if (addresses.isEmpty()) {
                    null
                } else {
                    listOfNotNull(
                        item.name,
                        addresses.takeIf { it.isNotEmpty() }?.joinToString(prefix = "IP: ")
                    ).joinToString("\n")
                }
            }

            visibleListResult(values, context.getString(R.string.self_check_status_visible), context.getString(R.string.self_check_network_hidden), context)
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
