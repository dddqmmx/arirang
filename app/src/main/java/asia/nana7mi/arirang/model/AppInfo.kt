package asia.nana7mi.arirang.model

import android.graphics.drawable.Drawable
import asia.nana7mi.arirang.data.datastore.ClipboardPromptPrefs

data class AppInfo(
    val appName: String,
    val packageName: String,
    var icon: Drawable?,
    var permissionState: ClipboardPromptPrefs.Policy,
    val isSystemApp: Boolean,
    var isConfigured: Boolean = false
)
