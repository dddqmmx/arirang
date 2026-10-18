package asia.nana7mi.arirang.selfcheck.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import asia.nana7mi.arirang.selfcheck.R
import asia.nana7mi.arirang.selfcheck.model.CheckResult
import asia.nana7mi.arirang.selfcheck.model.CheckState
import com.google.android.material.button.MaterialButton

/**
 * One section card: the views inside `item_self_check_section.xml`, and the rules for what they
 * show.
 *
 * Holds no state — the state belongs to [SelfCheckViewModel] — so what the card says is a
 * function of what it is handed, and a card that is handed a later verdict for the same check
 * simply says that instead.
 *
 * The live toggle is set once, when the card is built (whether a card has one is structure) and
 * labelled on every render (what it says is state).
 */
class CheckSectionView(val root: View) {

    private val icon: ImageView = root.findViewById(R.id.sectionStatusIcon)
    private val title: TextView = root.findViewById(R.id.sectionTitle)
    private val status: TextView = root.findViewById(R.id.sectionStatus)
    private val content: TextView = root.findViewById(R.id.sectionContent)
    private val action: MaterialButton = root.findViewById(R.id.sectionAction)

    private val context get() = root.context

    fun bindTitle(text: CharSequence) {
        title.text = text
    }

    /** Adds the live toggle to this card, or leaves it out, for the whole life of the card. */
    fun setToggle(visible: Boolean, onClick: () -> Unit) {
        action.visibility = if (visible) View.VISIBLE else View.GONE
        action.setOnClickListener { onClick() }
    }

    /** What the toggle offers: starting the live reading, or stopping one that is running. */
    fun bindToggleLabel(text: CharSequence) {
        action.text = text
    }

    /**
     * The card for a check that has [result], or for one that has not answered yet: [running]
     * separates "still being checked" from "not checked in this run".
     */
    fun bindResult(result: CheckResult?, running: Boolean) {
        icon.setImageResource(
            when (result?.state) {
                CheckState.VISIBLE -> R.drawable.ic_status_enabled
                CheckState.LEAKED -> R.drawable.ic_status_leak
                CheckState.BLOCKED, null -> R.drawable.ic_status_disabled
            }
        )
        status.text = result?.status ?: context.getString(
            if (running) R.string.self_check_status_checking else R.string.self_check_waiting
        )
        content.text = result?.content
            ?: if (running) context.getString(R.string.self_check_waiting) else ""
    }
}
