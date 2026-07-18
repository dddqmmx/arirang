package asia.nana7mi.arirang.selfcheck.checker

import android.content.Context
import asia.nana7mi.arirang.selfcheck.model.CheckResult

interface SelfChecker {
    val titleRes: Int
    val navChipId: Int
    suspend fun check(context: Context): CheckResult
}
