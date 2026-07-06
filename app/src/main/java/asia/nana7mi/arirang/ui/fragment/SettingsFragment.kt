package asia.nana7mi.arirang.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.ui.activity.AdvancedSettingsActivity
import asia.nana7mi.arirang.ui.component.SettingsScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArirangTheme {
                    SettingsScreen(
                        onNavigateToAdvanced = {
                            startActivity(Intent(requireContext(), AdvancedSettingsActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}
