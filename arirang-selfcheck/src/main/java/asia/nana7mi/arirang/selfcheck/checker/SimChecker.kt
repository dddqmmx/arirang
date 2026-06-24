package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskForPhoneDiag
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.maskMiddle
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SimChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_sim_title
    override val navChipId: Int = R.id.navSimChip

    @SuppressLint("MissingPermission")
    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_phone_permission_hint)
            )
        }

        try {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            val subscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()
            Log.d(
                CheckDefinitions.PHONE_DIAG_TAG,
                "readSimInfo subscriptions size=${subscriptions.size} " +
                    subscriptions.joinToString(prefix = "[", postfix = "]") {
                        "subId=${it.subscriptionId},slot=${it.simSlotIndex},getNumber=${it.number.maskForPhoneDiag()}"
                    }
            )
            val values = subscriptions.take(6).map { sub ->
                listOfNotNull(
                    "Slot ${sub.simSlotIndex}",
                    sub.displayName?.toString()?.takeIf { it.isNotBlank() }?.let { "Display: $it" },
                    sub.carrierName?.toString()?.takeIf { it.isNotBlank() }?.let { "Carrier: $it" },
                    sub.number?.takeIf { it.isNotBlank() }?.let { "Number: ${it.maskMiddle()}" },
                    "MCC/MNC: ${sub.mccString}/${sub.mncString}",
                    sub.countryIso?.takeIf { it.isNotBlank() }?.let { "Country: $it" },
                    "Card ID: ${sub.cardId}",
                    sub.groupUuid?.toString()?.takeIf { it.isNotBlank() }?.let { "Group: ${it.maskMiddle()}" },
                    "Carrier ID: ${sub.carrierId}"
                ).joinToString("\n")
            }

            CheckResult(
                if (values.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                context.resources.getQuantityString(R.plurals.self_check_sim_status, subscriptions.size, subscriptions.size),
                if (values.isEmpty()) context.getString(R.string.self_check_sim_hidden) else values.joinToString("\n\n")
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
