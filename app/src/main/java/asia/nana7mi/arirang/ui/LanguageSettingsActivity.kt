package asia.nana7mi.arirang.ui

import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.model.LanguageItem
import asia.nana7mi.arirang.ui.adapter.LanguageAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import asia.nana7mi.arirang.data.datastore.AppPreferences

class LanguageSettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_language_settings)

        // 加载语言显示名和对应的代码
        val names = resources.getStringArray(R.array.language_names)
        val codes = resources.getStringArray(R.array.language_codes)

        // 合并成 LanguageItem 列表
        val savedLang = AppPreferences.getLanguage(this)

        val currentLang = if (savedLang == null || savedLang == "null") null else savedLang

        val languageList = names.zip(codes).map { (name, code) ->
            val normalizedCode = if (code == "null") null else code
            LanguageItem(name, normalizedCode ?: "null", isSelected = normalizedCode == currentLang)
        }

        // 初始化 RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.languageRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = LanguageAdapter(languageList) { selected ->

            val currentLang = AppPreferences.getLanguage(this)
            if (currentLang == selected.code) return@LanguageAdapter

            AppPreferences.setLanguage(this, selected.code)
            applyLanguage(selected.code)
        }
    }
    fun applyLanguage(code: String) {
        val localeList = if (code == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(code)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
