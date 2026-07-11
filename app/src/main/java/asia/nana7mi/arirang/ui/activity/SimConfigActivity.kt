package asia.nana7mi.arirang.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.ActivityCompat
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SimConfigPrefs
import asia.nana7mi.arirang.data.datastore.UniqueIdentifierPrefs
import asia.nana7mi.arirang.model.SimInfo
import asia.nana7mi.arirang.ui.screen.sim.SimConfigScreen
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme
import java.security.SecureRandom

class SimConfigActivity : ComponentActivity() {
    private val iccidRandom = SecureRandom()

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
                    onImportSystemSims = { getSystemSimInfoList() },
                    onRequestPhoneStatePermission = {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.READ_PHONE_STATE),
                            1
                        )
                    },
                    onCreateDefaultSim = { index -> createDefaultSimInfo(index) }
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

    private fun createDefaultSimInfo(index: Int): SimInfo {
        return SimInfo(
            id = index + 1,
            iccId = randomIccidForDefault(),
            simSlotIndex = index,
            displayName = getString(R.string.sim_carrier_default),
            carrierName = getString(R.string.sim_carrier_default),
            nameSource = null,
            iconTint = null,
            number = "+12025550147",
            roaming = 0,
            icon = null,
            mcc = getString(R.string.sim_mcc_default),
            mnc = getString(R.string.sim_mnc_default),
            countryIso = getString(R.string.sim_country_default),
            isEmbedded = false,
            nativeAccessRules = null,
            cardString = "",
            cardId = index,
            isOpportunistic = false,
            groupUuid = null,
            isGroupDisabled = false,
            carrierId = -1,
            profileClass = null,
            subType = null,
            groupOwner = "",
            carrierConfigAccessRules = null,
            areUiccApplicationsEnabled = true,
            portIndex = 0,
            usageSetting = 0,
            isExpanded = true
        )
    }

    private fun randomIccidForDefault(): String {
        val body = buildString(18) {
            append("89860")
            while (length < 18) {
                append(iccidRandom.nextInt(10))
            }
        }
        return body + luhnCheckDigit(body)
    }

    private fun luhnCheckDigit(body: String): Int {
        val sum = body.reversed().mapIndexed { index, char ->
            val digit = char.digitToIntOrNull() ?: 0
            if (index % 2 == 0) {
                val doubled = digit * 2
                if (doubled > 9) doubled - 9 else doubled
            } else {
                digit
            }
        }.sum()
        return (10 - (sum % 10)) % 10
    }

    private fun getSystemSimInfoList(): List<SimInfo> {
        val simInfoList = mutableListOf<SimInfo>()
        val subscriptionManager = getSystemService(SubscriptionManager::class.java)
        val telephonyManager = getSystemService(TelephonyManager::class.java)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val subscriptionList = subscriptionManager.activeSubscriptionInfoList
        if (subscriptionList != null) {
            for (sub in subscriptionList) {
                simInfoList.add(
                    SimInfo(
                        id = sub.subscriptionId,
                        iccId = sub.iccId,
                        simSlotIndex = sub.simSlotIndex,
                        displayName = sub.displayName.toString(),
                        carrierName = sub.carrierName.toString(),
                        iconTint = sub.iconTint,
                        number = sub.number,
                        roaming = sub.dataRoaming,
                        mcc = sub.mccString,
                        mnc = sub.mncString,
                        countryIso = sub.countryIso,
                        isEmbedded = sub.isEmbedded,
                        cardId = sub.cardId,
                        isOpportunistic = sub.isOpportunistic,
                        groupUuid = sub.groupUuid?.toString(),
                        carrierId = sub.carrierId,
                        subType = sub.subscriptionType,
                        portIndex = sub.portIndex,
                        usageSetting = sub.usageSetting,
                        nativeAccessRules = null,
                        cardString = null,
                        isGroupDisabled = null,
                        profileClass = null,
                        groupOwner = null,
                        carrierConfigAccessRules = null,
                        areUiccApplicationsEnabled = null,
                        nameSource = null,
                        icon = null,
                        isExpanded = false
                    )
                )
            }
        }
        return simInfoList
    }

}
