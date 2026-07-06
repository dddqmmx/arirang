package asia.nana7mi.arirang.ui.component.home

import androidx.compose.ui.graphics.vector.ImageVector

data class FeatureItem(
    val titleRes: Int,
    val icon: ImageVector,
    val activityClass: Class<*>?,
    val isReleased: Boolean
)
