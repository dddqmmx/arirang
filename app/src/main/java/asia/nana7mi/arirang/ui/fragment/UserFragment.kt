package asia.nana7mi.arirang.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.AppPreferences

class UserFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_user, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRegionDisplay(view)
    }

    private fun setupRegionDisplay(view: View) {
        val tvCurrentRegion = view.findViewById<TextView>(R.id.tv_current_region)
        val regionCode = AppPreferences.getRegion(requireContext()) ?: "US"
        
        val regionCodes = resources.getStringArray(R.array.region_codes)
        val regionNames = resources.getStringArray(R.array.region_names)
        
        val index = regionCodes.indexOf(regionCode)
        val displayName = if (index != -1) regionNames[index] else regionCode
        
        tvCurrentRegion.text = displayName
    }
}
