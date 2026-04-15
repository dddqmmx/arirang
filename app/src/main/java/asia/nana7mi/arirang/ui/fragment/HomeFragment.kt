package asia.nana7mi.arirang.ui.fragment

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.ClipboardConfigActivity
import asia.nana7mi.arirang.ui.DeviceInfoConfigActivity
import asia.nana7mi.arirang.ui.LocationConfigActivity
import asia.nana7mi.arirang.ui.PackageListConfigActivity
import asia.nana7mi.arirang.ui.SimConfigActivity
import asia.nana7mi.arirang.view.FeatureItemView
import android.content.Intent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.ui.TestActivity
import android.widget.Toast
import asia.nana7mi.arirang.BuildConfig
import com.google.android.material.color.MaterialColors

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
        statusCard = view.findViewById<CardView>(R.id.status_card)
        statusText = view.findViewById<TextView>(R.id.status_text)
        if (isXposedActivation()){
            context?.let {
                val color = MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorPrimaryContainer)
                statusCard.setCardBackgroundColor(color)
            }
            statusText.setText(R.string.status_activated)
        }

        // Only clipboard is enabled in release build
        setupFeature(R.id.clipboard_setting_bottom, ClipboardConfigActivity::class.java, true)

        // These are disabled in release build
        setupFeature(R.id.sim_info_setting_bottom, SimConfigActivity::class.java, false)
        setupFeature(R.id.location_setting_bottom, LocationConfigActivity::class.java, false)
        setupFeature(R.id.device_info_setting_bottom, DeviceInfoConfigActivity::class.java, false)
        setupFeature(R.id.package_list_bottom, PackageListConfigActivity::class.java, false)
        setupFeature(R.id.test_view, TestActivity::class.java, false)

        // Features that are not yet implemented in the fragment logic but present in XML
        setupFeature(R.id.sensor_info_setting_bottom, null, false)
        setupFeature(R.id.bluetooth_list_bottom, null, false)
        setupFeature(R.id.wifi_setting_bottom, null, false)
    }

    private fun setupFeature(viewId: Int, activityClass: Class<*>?, isReleased: Boolean) {
        val featureView = view?.findViewById<FeatureItemView>(viewId) ?: return
        
        val isAvailable = BuildConfig.DEBUG || isReleased
        
        if (!isAvailable) {
            val originalName = featureView.featureName ?: ""
            featureView.setFeatureName("$originalName (${getString(R.string.feature_not_available)})")
            featureView.alpha = 0.5f
            featureView.setOnClickListener {
                Toast.makeText(requireContext(), R.string.feature_not_available, Toast.LENGTH_SHORT).show()
            }
        } else if (activityClass != null) {
            featureView.setOnClickListener {
                startActivity(Intent(requireContext(), activityClass))
            }
        }
    }

    fun isXposedActivation(): Boolean {
        return false;
    }

}