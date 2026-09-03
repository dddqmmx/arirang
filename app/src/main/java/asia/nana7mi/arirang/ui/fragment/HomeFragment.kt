package asia.nana7mi.arirang.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.ui.screen.home.HomeScreen
import asia.nana7mi.arirang.ui.theme.ArirangTheme

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
                        submoduleVersion = submoduleVersion(),
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
        return asia.nana7mi.arirang.data.sync.RemotePreferencesSyncManager.isServiceConnected
    }

    fun submoduleVersion(): String? {
        System.getenv("ARIRANG_SUBMODULE_VERSION")?.let { return it }
        // ART snapshots the process environment in the zygote before fork, so
        // the marker the module sets during specialize never reaches Java's
        // getenv. Fall back to the module-managed config file in our own
        // device-protected storage: it exists only when the root-side
        // submodule infrastructure has provisioned this app. Submodule and
        // app are versioned together, so report our own versionName.
        return try {
            val dataDir = requireContext().applicationInfo.dataDir.removeSuffix("/")
            // /data/user/0/<pkg> -> /data/user_de/0/<pkg>/files
            val deFiles = if (dataDir.contains("/user/0/")) {
                dataDir.replace("/user/0/", "/user_de/0/") + "/files"
            } else {
                requireContext().createDeviceProtectedStorageContext()
                    .applicationInfo.dataDir + "/files"
            }
            val config = java.io.File(deFiles, "arirang-submodule/config.json")
            if (config.exists() && config.length() > 0) {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
