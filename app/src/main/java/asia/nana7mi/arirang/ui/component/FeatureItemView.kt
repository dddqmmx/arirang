package asia.nana7mi.arirang.ui.component

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import asia.nana7mi.arirang.R

class FeatureItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val featureNameTextView: TextView
    private val featureIconView: ImageView

    var featureName: String? = null
        set(value) {
            field = value
            featureNameTextView.text = value
        }

    var featureIcon: Int = R.drawable.ic_feature
        set(value) {
            field = value
            featureIconView.setImageResource(value)
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.item_feature, this, true)
        featureNameTextView = findViewById(R.id.feature_name)
        featureIconView = findViewById(R.id.feature_icon)

        attrs?.let {
            val a = context.theme.obtainStyledAttributes(
                it,
                R.styleable.FeatureItemView,
                0, 0
            )

            try {
                featureName = a.getString(R.styleable.FeatureItemView_featureName)
                featureIcon = a.getResourceId(
                    R.styleable.FeatureItemView_featureIcon,
                    R.drawable.ic_feature
                )
            } finally {
                a.recycle()
            }
        }
    }
}