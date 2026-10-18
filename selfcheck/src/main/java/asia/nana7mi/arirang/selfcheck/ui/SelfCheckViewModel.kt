package asia.nana7mi.arirang.selfcheck.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.checker.Check
import asia.nana7mi.arirang.selfcheck.checker.CheckRegistry
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.run.CheckSummary
import asia.nana7mi.arirang.selfcheck.run.CheckUpdate
import asia.nana7mi.arirang.selfcheck.run.CheckWording
import asia.nana7mi.arirang.selfcheck.run.SelfCheckRunner
import asia.nana7mi.arirang.selfcheck.run.results
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one section shows. */
data class SectionState(
    val check: Check,
    val result: CheckResult? = null,
    val live: Boolean = false,
)

/**
 * What the whole screen shows.
 *
 * [summary] is null exactly until the first run starts, which is how the screen knows to show the
 * idle line rather than a verdict; [running] is whether a run is still in flight.
 */
data class SelfCheckUiState(
    val sections: List<SectionState> = emptyList(),
    val running: Boolean = false,
    val summary: CheckSummary? = null,
)

/**
 * The state of a self check, and the only thing that starts one.
 *
 * Holds the results rather than the view holding them, for three reasons that all come from the
 * same one: the work outlives the activity. A run takes as long as its slowest check and is no
 * longer allowed to hold the screen back, so it must survive a rotation that recreates the screen
 * under it; a live reading is meant to keep reading while the user looks elsewhere; and a check
 * that is still running when the user leaves must be cancelled rather than left holding sensor
 * listeners and location updates.
 *
 * Results are applied per check as they arrive — see [SelfCheckRunner] — so a section shows its
 * verdict the moment that check has one, and the summary line is whatever the last update counted.
 */
class SelfCheckViewModel(
    checks: List<Check> = CheckRegistry.checks,
) : ViewModel() {

    private val runner = SelfCheckRunner(checks)

    private val _state = MutableStateFlow(SelfCheckUiState(sections = checks.map { SectionState(it) }))
    val state: StateFlow<SelfCheckUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private val liveJobs = mutableMapOf<Check, Job>()

    /** Runs the checks if this screen has not run them yet; opening the app is a run. */
    fun ensureChecked(context: Context) {
        if (_state.value.summary == null) rerun(context)
    }

    /**
     * Runs the checks again from scratch, replacing every section.
     *
     * Whatever was in flight is cancelled first, so the results on screen are always one run's and
     * never a mix of two.
     */
    fun rerun(context: Context) {
        val appContext = context.applicationContext
        stopLive()
        _state.update { current ->
            current.copy(
                sections = current.sections.map { it.copy(result = null, live = false) },
                running = true,
                summary = CheckSummary.pending(current.sections.size)
            )
        }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            runner.run(appContext, wording(appContext)).collect { update -> applyUpdate(update) }
            _state.update { it.copy(running = false) }
        }
    }

    /**
     * Starts or stops the live reading of a check that streams.
     *
     * A live result refines its own section only: it is the same question being watched, so it
     * does not re-tally a run that has already counted that check.
     */
    fun toggleLive(check: Check, context: Context) {
        if (liveJobs.remove(check) != null) {
            setLive(check, false)
            return
        }

        setLive(check, true)
        val appContext = context.applicationContext
        liveJobs[check] = viewModelScope.launch {
            check.results(appContext, wording(appContext)).collect { result -> setResult(check, result) }
        }
    }

    private fun wording(context: Context) = CheckWording(
        failed = context.getString(R.string.self_check_status_failed),
        noResult = context.getString(R.string.self_check_no_result),
    )

    private fun applyUpdate(update: CheckUpdate) {
        setResult(update.check, update.result)
        _state.update { it.copy(summary = update.summary) }
    }

    private fun setResult(check: Check, result: CheckResult) {
        _state.update { current ->
            current.copy(
                sections = current.sections.map { section ->
                    if (section.check === check) section.copy(result = result) else section
                }
            )
        }
    }

    private fun setLive(check: Check, live: Boolean) {
        _state.update { current ->
            current.copy(
                sections = current.sections.map { section ->
                    if (section.check === check) section.copy(live = live) else section
                }
            )
        }
    }

    /**
     * Stops every live reading.
     *
     * A run asks for this because the two are the same measurement by different means — a live
     * reading left on while a run starts would register a second set of listeners for a sensor
     * the run is already sampling.
     */
    private fun stopLive() {
        liveJobs.keys.toList().forEach { setLive(it, false) }
        liveJobs.values.forEach { it.cancel() }
        liveJobs.clear()
    }
}
