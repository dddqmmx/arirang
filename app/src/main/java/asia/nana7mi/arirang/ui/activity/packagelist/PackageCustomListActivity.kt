package asia.nana7mi.arirang.ui.activity.packagelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.ui.activity.BaseActivity
import asia.nana7mi.arirang.ui.screen.packagelist.PackageCustomListScreen
import asia.nana7mi.arirang.ui.theme.ArirangTheme

class PackageCustomListActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val targetType = TargetType.valueOf(
            intent.getStringExtra(EXTRA_TARGET_TYPE) ?: TargetType.TEMPLATE.name
        )
        val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
        val targetPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.template_edit_list)

        setContent {
            ArirangTheme {
                PackageCustomListScreen(
                    targetType = targetType,
                    templateId = templateId,
                    targetPackageName = targetPackageName,
                    title = title,
                    onBack = { finish() },
                    onSaved = {
                        setResult(RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    enum class TargetType {
        TEMPLATE,
        APP
    }

    companion object {
        private const val EXTRA_TARGET_TYPE = "target_type"
        private const val EXTRA_TEMPLATE_ID = "template_id"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_TITLE = "title"

        fun forTemplate(context: Context, templateId: String, title: String): Intent {
            return Intent(context, PackageCustomListActivity::class.java)
                .putExtra(EXTRA_TARGET_TYPE, TargetType.TEMPLATE.name)
                .putExtra(EXTRA_TEMPLATE_ID, templateId)
                .putExtra(EXTRA_TITLE, title)
        }

        fun forApp(context: Context, packageName: String, title: String): Intent {
            return Intent(context, PackageCustomListActivity::class.java)
                .putExtra(EXTRA_TARGET_TYPE, TargetType.APP.name)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_TITLE, title)
        }
    }
}
