package asia.nana7mi.arirang.selfcheck.model

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import asia.nana7mi.arirang.selfcheck.R

enum class CheckState {
    VISIBLE,
    BLOCKED,
    LEAKED
}

data class CheckResult(
    val state: CheckState,
    val status: String,
    val content: String
)

class CheckSectionView(val root: View) {
    private val icon: ImageView = root.findViewById(R.id.sectionStatusIcon)
    private val title: TextView = root.findViewById(R.id.sectionTitle)
    private val status: TextView = root.findViewById(R.id.sectionStatus)
    private val content: TextView = root.findViewById(R.id.sectionContent)

    fun bindTitle(value: String) {
        title.text = value
    }

    fun bindResult(result: CheckResult) {
        icon.setImageResource(
            when (result.state) {
                CheckState.VISIBLE -> R.drawable.ic_status_enabled
                CheckState.BLOCKED -> R.drawable.ic_status_disabled
                CheckState.LEAKED -> R.drawable.ic_status_leak
            }
        )
        status.text = result.status
        content.text = result.content
    }
}
