package asia.nana7mi.arirang.model

import android.graphics.drawable.Drawable

data class PackageCustomListItem(
    val appName: String,
    val packageName: String,
    var icon: Drawable? = null,
    val isSelected: Boolean = false
)
