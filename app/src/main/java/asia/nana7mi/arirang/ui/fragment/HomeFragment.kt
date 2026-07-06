package asia.nana7mi.arirang.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.ui.component.home.HomeScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme

class HomeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArirangTheme {
                    HomeScreen(
                        activated = isXposedActivation(),
                        onFeatureClick = ::openFeature
                    )
                }
            }
        }
    }

    private fun openFeature(activityClass: Class<*>?) {
        activityClass ?: return
        startActivity(Intent(requireContext(), activityClass))
    }

    fun isXposedActivation(): Boolean {
        return false
    }
}
