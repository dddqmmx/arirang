package asia.nana7mi.arirang.ui.activity

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.theme.ArirangTheme

/**
 * Hosts one Compose config screen.
 *
 * Every `*ConfigActivity` does the same four things — go edge-to-edge, read the
 * current config, hand it to a screen taking `(initialConfig, onBack, onSave)`,
 * then persist and confirm on save — and each had its own copy of that wiring,
 * differing only in which Prefs object and screen it named.
 *
 * @param load reads the config to seed the screen with. Runs before composition,
 *   so a screen needing to seed defaults from the device (as the sensor list
 *   does) can do that here.
 * @param save persists the edited config.
 * @param rebootRequired whether the change only takes effect after a reboot.
 *   This chose the confirmation message and its duration, which previously had
 *   to be kept in step by hand at each call site.
 */
fun <C> ComponentActivity.setConfigScreenContent(
    load: () -> C,
    save: (C) -> Unit,
    rebootRequired: Boolean = false,
    screen: @Composable (initialConfig: C, onBack: () -> Unit, onSave: (C) -> Unit) -> Unit
) {
    enableEdgeToEdge()
    val initialConfig = load()

    setContent {
        ArirangTheme {
            screen(initialConfig, { finish() }) { config ->
                save(config)
                val message = if (rebootRequired) {
                    R.string.save_success_reboot_required
                } else {
                    R.string.save_success
                }
                val duration = if (rebootRequired) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, getString(message), duration).show()
            }
        }
    }
}
