package asia.nana7mi.arirang.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
        setupSponsorButtons(view)
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

    private fun setupSponsorButtons(view: View) {

        view.findViewById<View>(R.id.btn_sponsor_github).setOnClickListener {
            showSponsorToast(getString(R.string.user_sponsor_github))
        }
    }

    private fun showSponsorToast(method: String) {
        Toast.makeText(requireContext(), "Thank you for supporting via $method!", Toast.LENGTH_SHORT).show()
    }
}