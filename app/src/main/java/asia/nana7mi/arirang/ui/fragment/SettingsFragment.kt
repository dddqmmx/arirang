package asia.nana7mi.arirang.ui.fragment

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.activity.LanguageSettingsActivity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.language_setting).setOnClickListener{
            val intent = Intent(requireContext(), LanguageSettingsActivity::class.java)
            startActivity(intent)
        }
    }

}