package asia.nana7mi.arirang.selfcheck.ui

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.checker.Check
import asia.nana7mi.arirang.selfcheck.model.CheckDefinitions
import asia.nana7mi.arirang.selfcheck.report.CheckReport
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The self check screen: the chrome, and the wiring between it and [SelfCheckViewModel].
 *
 * Knows no check by name — the section list builds itself from the sections the view model
 * publishes — and holds no result: it renders one state and forwards events, which is what makes
 * the difference between a run in flight and a run finished a property of the state rather than of
 * this class. What is left here is what genuinely belongs to an activity: a permission request, a
 * document picker, and a dialog, each of which needs one.
 */
class SelfCheckActivity : AppCompatActivity() {

    private val viewModel: SelfCheckViewModel by viewModels()

    private lateinit var summaryText: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var sections: SectionListView

    /** The picked sections, held between the dialog and the file the system picker returns. */
    private var pendingExport: List<Check> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.rerun(this)
    }

    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { writeReport(it, pendingExport) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_self_check, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export -> {
            exportCurrentState()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_self_check)
        setSupportActionBar(findViewById(R.id.toolbar))

        summaryText = findViewById(R.id.selfCheckSummary)
        runButton = findViewById(R.id.runSelfCheckButton)
        sections = SectionListView(
            scrollView = findViewById(R.id.selfCheckScrollView),
            cardContainer = findViewById(R.id.selfCheckSections),
            chipGroup = findViewById(R.id.selfCheckNavigation),
            onToggleLive = { viewModel.toggleLive(it, this) },
        )
        sections.bind(viewModel.state.value.sections.map { it.check })
        runButton.setOnClickListener { requestMissingPermissionsOrRun() }

        lifecycleScope.launch {
            viewModel.state.collect { state -> render(state) }
        }

        // A recreated screen renders the run that is already going: the check started it, not this.
        viewModel.ensureChecked(this)
    }

    /** Everything the screen shows, out of one state: the cards, the summary line, the button. */
    private fun render(state: SelfCheckUiState) {
        sections.render(state)
        summaryText.text = when {
            state.summary == null -> getString(R.string.self_check_summary_idle)
            !state.running -> resultSummary(state.summary)
            state.summary.settled == 0 -> getString(R.string.self_check_summary_running)
            else -> getString(
                R.string.self_check_summary_running_progress,
                state.summary.settled,
                state.summary.total
            )
        }
        runButton.isEnabled = !state.running
        runButton.setText(if (state.summary == null) R.string.self_check_run else R.string.self_check_run_again)
    }

    private fun resultSummary(summary: asia.nana7mi.arirang.selfcheck.run.CheckSummary): String =
        if (summary.leaked > 0) {
            getString(R.string.self_check_summary_result_leak, summary.visible, summary.blocked, summary.leaked)
        } else {
            getString(R.string.self_check_summary_result, summary.visible, summary.blocked)
        }

    /**
     * Asks for whatever the checks cannot read without and then runs them anyway — a dismissed or
     * refused request is an answer too, and each check says for itself what it could not read.
     */
    private fun requestMissingPermissionsOrRun() {
        val missing = CheckDefinitions.REQUIRED_PERMISSIONS.filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            viewModel.rerun(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Offers the sections that have a verdict, since a section a run has not answered has nothing
     * to write into the file.
     */
    private fun exportCurrentState() {
        val answered = viewModel.state.value.sections.filter { it.result != null }
        if (answered.isEmpty()) return

        val titles = answered.map { getString(it.check.titleRes) }.toTypedArray()
        val checked = BooleanArray(titles.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.export_select_sections)
            .setMultiChoiceItems(titles, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingExport = answered
                    .filterIndexed { index, _ -> checked[index] }
                    .map { it.check }
                if (pendingExport.isNotEmpty()) {
                    createFileLauncher.launch(getString(R.string.export_filename))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeReport(uri: Uri, selected: List<Check>) {
        if (selected.isEmpty()) return

        val answered = viewModel.state.value.sections.associateBy { it.check }
        val text = CheckReport.render(
            entries = selected.mapNotNull { check ->
                answered[check]?.result?.let { result -> CheckReport.Entry(getString(check.titleRes), result) }
            },
            statusLabel = getString(R.string.status_title),
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val failure = runCatching {
                val stream = contentResolver.openOutputStream(uri) ?: error("no output stream")
                stream.use { it.write(text.toByteArray()) }
            }.exceptionOrNull()
            if (failure != null) {
                Log.e(CheckDefinitions.PHONE_DIAG_TAG, "Failed to export", failure)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SelfCheckActivity,
                    if (failure == null) R.string.export_success else R.string.export_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
