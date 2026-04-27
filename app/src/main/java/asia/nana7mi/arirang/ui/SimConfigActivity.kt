package asia.nana7mi.arirang.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.SimInfo
import asia.nana7mi.arirang.ui.item.SimInfoSlotView
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.telephony.SubscriptionManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

class SimConfigActivity : AppCompatActivity() {

    private val PREFS_NAME = "sim_config_prefs"
    private val ENABLED_KEY = "enabled"
    private val LAST_MODIFIED_KEY = "last_modified"
    private val SIM_INFO_LIST_KEY = "sim_info_list"
    private val gson = Gson()

    private lateinit var featureStatusIcon: ImageView
    private lateinit var saveIcon: ImageView
    private lateinit var simScopeSettingBottom: LinearLayout

    private val simSlotViews = mutableListOf<SimInfoSlotView>()

    private lateinit var prefs: SharedPreferences
    private var enabled = false
    private lateinit var simInfoList: List<SimInfo>

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContentView(R.layout.activity_sim_config)

        featureStatusIcon = findViewById<ImageView>(R.id.featureStatusIcon)
        saveIcon = findViewById<ImageView>(R.id.saveIcon)
        simScopeSettingBottom = findViewById<LinearLayout>(R.id.sim_scope_setting_bottom)

        loadSimInfoConfig()
        if (simInfoList.isEmpty()){
            Toast.makeText(this, "配置文件中没有配置文件，尝试获取手机内真实SIM卡信息", Toast.LENGTH_SHORT).show()
            simInfoList = getSystemSimInfoList()
        }
        val container = findViewById<LinearLayout>(R.id.sim_slot_container)
        val slotCount = simInfoList.size

        repeat(slotCount) { index ->
            val slotView = SimInfoSlotView(this)
            slotView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            slotView.setTitle(index)
            slotView.setSimInfo(simInfoList[index])
            container.addView(slotView)
            simSlotViews.add(slotView)
        }
        setupListeners();
    }

    private fun setupListeners() {
        saveIcon.setOnClickListener {
            saveSimInfoConfig()
        }
        featureStatusIcon.setOnClickListener {
            enabled = !enabled
            featureStatusIcon.setImageResource(
                if (enabled) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
            )
            saveSimInfoConfig()
        }
        simScopeSettingBottom.setOnClickListener {
            val intent = Intent(this, SimScopeActivity::class.java)
            startActivity(intent)
        }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getSystemSimInfoList(): List<SimInfo> {
        val simInfoList = mutableListOf<SimInfo>()

        val subscriptionManager = getSystemService(SubscriptionManager::class.java)

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
                        icon = null
                    )
                )
            }
        }

        return simInfoList
    }

    private fun saveSimInfoConfig() {
        simInfoList = simSlotViews.map { it.getSimInfo() }
        val json = gson.toJson(simInfoList)
        prefs.edit().apply {
            putBoolean(ENABLED_KEY, enabled)
            putLong(LAST_MODIFIED_KEY, Date().time)
            putString(SIM_INFO_LIST_KEY, json)
            apply()
        }
        Toast.makeText(this, "保存完成", Toast.LENGTH_SHORT).show()
    }

    private fun loadSimInfoConfig() {
        enabled = prefs.getBoolean(ENABLED_KEY, false)
        featureStatusIcon.setImageResource(
            if (enabled) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
        )
        val json = prefs.getString(SIM_INFO_LIST_KEY, null)
        simInfoList = if (json != null) {
            val type = object : TypeToken<List<SimInfo>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList()
        }
    }

}
