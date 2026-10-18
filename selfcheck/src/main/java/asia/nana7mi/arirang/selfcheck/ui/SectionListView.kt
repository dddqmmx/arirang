package asia.nana7mi.arirang.selfcheck.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.checker.Check
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * The section list of a run: one card and one navigation chip per check, both built from the list
 * it is handed and in its order.
 *
 * The layout this replaces held nineteen chips and nineteen included cards, each with an id that a
 * check had to name in order to appear at all — so a check could not be added, removed, reordered
 * or renamed without editing XML in two places and a literal list in the activity, and the sensor
 * section had to be told which card was its own before it could put a button in it. Here the list
 * *is* the registry: cards and chips are inflated from it in one pass, no check owns a view id, and
 * what the screen shows is exactly what the build runs.
 */
class SectionListView(
    private val scrollView: ScrollView,
    private val cardContainer: LinearLayout,
    private val chipGroup: ChipGroup,
    private val onToggleLive: (Check) -> Unit,
) {

    private val context = cardContainer.context
    private val cards = LinkedHashMap<Check, CheckSectionView>()

    /** Builds the list. Called once, with the checks this screen shows, in their order. */
    fun bind(checks: List<Check>) {
        val inflater = LayoutInflater.from(context)
        checks.forEachIndexed { index, check ->
            val card = inflater.inflate(R.layout.item_self_check_section, cardContainer, false)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.resources.getDimensionPixelSize(
                    if (index == 0) R.dimen.self_check_summary_gap else R.dimen.self_check_section_gap
                )
            }
            cardContainer.addView(card)

            cards[check] = CheckSectionView(card).apply {
                bindTitle(context.getString(check.titleRes))
                setToggle(visible = check.continuous) { onToggleLive(check) }
            }
            val chip = inflater.inflate(R.layout.item_self_check_chip, chipGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = context.getString(check.navLabelRes)
            chip.isChecked = index == 0
            chip.setOnClickListener { scrollTo(check) }
            chipGroup.addView(chip)
        }
    }

    /** Shows [state]: every section's verdict, and which toggle is on. */
    fun render(state: SelfCheckUiState) {
        state.sections.forEach { section ->
            val card = cards[section.check] ?: return@forEach
            card.bindResult(section.result, state.running)
            if (section.check.continuous) {
                card.bindToggleLabel(
                    context.getString(
                        if (section.live) R.string.self_check_monitor_stop else R.string.self_check_monitor_start
                    )
                )
            }
        }
    }

    private fun scrollTo(check: Check) {
        val card = cards[check]?.root ?: return
        scrollView.post {
            val offset = context.resources.getDimensionPixelSize(R.dimen.self_check_scroll_target_offset)
            scrollView.smoothScrollTo(0, (card.top - offset).coerceAtLeast(0))
        }
    }
}
