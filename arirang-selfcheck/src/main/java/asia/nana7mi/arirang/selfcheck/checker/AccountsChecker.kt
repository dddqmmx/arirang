package asia.nana7mi.arirang.selfcheck.checker

import android.Manifest
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccountsChecker : SelfChecker {
    override val titleRes: Int = R.string.self_check_accounts_title
    override val navChipId: Int = R.id.navAccountsChip

    override suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext CheckResult(
                CheckState.BLOCKED,
                context.getString(R.string.self_check_permission_needed),
                context.getString(R.string.self_check_accounts_permission_hint)
            )
        }

        try {
            val accounts = AccountManager.get(context).accounts.orEmpty()
            val samples = accounts.take(8).joinToString("\n") { account ->
                "${account.name}\n${account.type}"
            }
            CheckResult(
                if (accounts.isEmpty()) CheckState.BLOCKED else CheckState.VISIBLE,
                context.resources.getQuantityString(R.plurals.self_check_accounts_status, accounts.size, accounts.size),
                if (samples.isBlank()) context.getString(R.string.self_check_accounts_hidden) else samples
            )
        } catch (e: Exception) {
            CheckResult(CheckState.BLOCKED, context.getString(R.string.self_check_status_not_visible), e.readableMessage())
        }
    }
}
