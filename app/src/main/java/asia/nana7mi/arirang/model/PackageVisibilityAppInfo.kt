package asia.nana7mi.arirang.model

import android.graphics.drawable.Drawable

data class PackageVisibilityAppInfo(
    val appName: String,
    val packageName: String,
    var icon: Drawable?,
    val isSystemApp: Boolean,
    val statusText: String,
    val isConfigured: Boolean
)
