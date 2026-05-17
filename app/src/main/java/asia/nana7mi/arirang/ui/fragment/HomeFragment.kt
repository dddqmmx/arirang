package asia.nana7mi.arirang.ui.fragment

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.activity.ClipboardConfigActivity
import asia.nana7mi.arirang.ui.activity.DeviceInfoConfigActivity
import asia.nana7mi.arirang.ui.activity.LocationConfigActivity
import asia.nana7mi.arirang.ui.activity.PackageListConfigActivity
import asia.nana7mi.arirang.ui.activity.SelfCheckActivity
import asia.nana7mi.arirang.ui.activity.SimConfigActivity
import asia.nana7mi.arirang.view.FeatureItemView
import android.content.Intent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.BuildConfig
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeFragment : Fragment() {

    private lateinit var statusCard: CardView
    private lateinit var statusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusCard = view.findViewById(R.id.status_card)
        statusText = view.findViewById(R.id.status_text)
        if (isXposedActivation()){
            context?.let {
                val color = MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorPrimaryContainer)
                statusCard.setCardBackgroundColor(color)
            }
            statusText.setText(R.string.status_activated)
        }

        setupFeature(R.id.clipboard_setting_bottom, ClipboardConfigActivity::class.java, isReleased = true)
        setupFeature(R.id.sim_info_setting_bottom, SimConfigActivity::class.java, isReleased = true)
        setupFeature(R.id.device_info_setting_bottom, DeviceInfoConfigActivity::class.java, isReleased = true)

        // These are disabled in release build
        setupFeature(R.id.location_setting_bottom, LocationConfigActivity::class.java, isReleased = false)
        setupFeature(R.id.package_list_bottom, PackageListConfigActivity::class.java, isReleased = false)
        setupFeature(R.id.test_view, SelfCheckActivity::class.java, isReleased = true)

        // Features that are not yet implemented in the fragment logic but present in XML
        setupFeature(R.id.sensor_info_setting_bottom, null, isReleased = false)
        setupFeature(R.id.bluetooth_list_bottom, null, isReleased = false)
        setupFeature(R.id.wifi_setting_bottom, null, isReleased = false)
    }

    private fun setupFeature(viewId: Int, activityClass: Class<*>?, isReleased: Boolean) {
        val featureView = view?.findViewById<FeatureItemView>(viewId) ?: return
        
        val isAvailable = BuildConfig.DEBUG || isReleased
        
        if (!isAvailable) {
            featureView.alpha = 0.5f
            featureView.setOnClickListener {
                showFeatureNotAvailableDialog()
            }
        } else if (activityClass != null) {
            featureView.setOnClickListener {
                startActivity(Intent(requireContext(), activityClass))
            }
        }
    }

    private fun showFeatureNotAvailableDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.feature_not_available)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun isXposedActivation(): Boolean {
        return false
    }

}
