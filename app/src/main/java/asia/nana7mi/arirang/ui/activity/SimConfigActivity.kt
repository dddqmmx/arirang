package asia.nana7mi.arirang.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.model.SimInfo
import asia.nana7mi.arirang.model.SimPresetCatalog
import asia.nana7mi.arirang.ui.screen.sim.SimConfigScreen
import asia.nana7mi.arirang.ui.theme.ArirangTheme

class SimConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialConfig = SimConfigPrefs.loadConfig(this)
        val slotLimit = UniqueIdentifierPrefs.configuredSlotCount(this)

        setContent {
            ArirangTheme {
                SimConfigScreen(
                    onBack = { finish() },
                    onSave = { enabled, hideSim, list ->
                        saveSimInfoConfig(enabled, hideSim, list)
                    },
                    initialEnabled = initialConfig.enabled,
                    initialHideSim = initialConfig.hideSim,
                    initialSimList = initialConfig.simInfoList,
                    slotLimit = slotLimit,
                    onCreateDefaultSim = { index -> SimPresetCatalog.randomSimInfo(index) },
                    onRandomSim = { index -> SimPresetCatalog.randomSimInfo(index) }
                )
            }
        }
    }

    private fun saveSimInfoConfig(enabled: Boolean, hideSim: Boolean, simInfoList: List<SimInfo>) {
        SimConfigPrefs.saveConfig(
            this,
            SimConfigPrefs.Config.fromList(
                enabled = enabled,
                hideSim = hideSim,
                simInfoList = simInfoList
            )
        )
        Toast.makeText(this, getString(R.string.save_success_reboot_required), Toast.LENGTH_LONG).show()
    }
}
