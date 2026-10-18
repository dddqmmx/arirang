package asia.nana7mi.arirang.selfcheck.run

import android.content.Context
import android.util.Log
import asia.nana7mi.arirang.selfcheck.checker.Check
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import asia.nana7mi.arirang.selfcheck.util.CheckUtils.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The one way this app reads a check, so that "a check cannot take the screen down" is a property
 * of reading one rather than of each reader remembering to arrange it.
 *
 * Two things are settled here. A check that misbehaves — one that throws instead of reporting —
 * becomes a failed result addressed to whoever is reading, because a check is device probing and
 * is expected to fail; and a check that ends without reporting at all becomes a result saying so,
 * because a reader that waits for a verdict will otherwise wait forever.
 *
 * Both readers of a check go through this: the run, which takes the first result, and a live
 * section, which watches all of them.
 *
 * Cancellation is not a failure and is passed on: it means the reader has stopped listening, which
 * is how a check's registered callbacks get released.
 */
fun Check.results(context: Context, wording: CheckWording): Flow<CheckResult> = flow {
    var reported = false
    try {
        run(context).collect { result ->
            reported = true
            emit(result)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "check ${javaClass.simpleName} failed", e)
        emit(CheckResult(CheckState.BLOCKED, wording.failed, e.readableMessage()))
        return@flow
    }

    if (!reported) {
        emit(CheckResult(CheckState.BLOCKED, wording.failed, wording.noResult))
    }
}

private const val TAG = CheckDefinitions.PHONE_DIAG_TAG
