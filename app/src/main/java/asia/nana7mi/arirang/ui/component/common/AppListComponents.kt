package asia.nana7mi.arirang.ui.component.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import asia.nana7mi.arirang.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pieces every "pick from the installed apps" screen needs.
 *
 * These lived inside `ui.component.location` and were reachable only from the
 * location screen; the package-visibility screens need the same list, search
 * box, type filter and icon rendering, so they moved here rather than being
 * copied. Nothing about the location screen's behaviour changed in the move.
 */

internal data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystemApp: Boolean,
    val icon: Drawable? = null
)

internal enum class AppTypeFilter {
    USER,
    SYSTEM,
    ALL
}

/**
 * Reads every installed application off the main thread.
 *
 * Callers load this once from a `LaunchedEffect(Unit)`; it is deliberately not
 * cached across screens, since a package can be installed or removed while the
 * manager is open.
 */
internal suspend fun loadInstalledApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val packageManager = context.packageManager
    packageManager.getInstalledApplications(PackageManager.GET_META_DATA).map { app ->
        AppEntry(
            label = packageManager.getApplicationLabel(app).toString(),
            packageName = app.packageName,
            isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            icon = runCatching { packageManager.getApplicationIcon(app) }.getOrNull()
        )
    }
}

/** Applies the type filter and a free-text query over both label and package name. */
internal fun List<AppEntry>.matching(type: AppTypeFilter, query: String): List<AppEntry> {
    val normalizedQuery = query.trim().lowercase()
    return asSequence()
        .filter { app ->
            when (type) {
                AppTypeFilter.USER -> !app.isSystemApp
                AppTypeFilter.SYSTEM -> app.isSystemApp
                AppTypeFilter.ALL -> true
            }
        }
        .filter { app ->
            normalizedQuery.isEmpty() ||
                app.label.lowercase().contains(normalizedQuery) ||
                app.packageName.lowercase().contains(normalizedQuery)
        }
        .toList()
}

@Composable
internal fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent
        )
    )
}

@Composable
internal fun AppTypeFilterChips(
    selected: AppTypeFilter,
    onSelect: (AppTypeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == AppTypeFilter.USER,
            onClick = { onSelect(AppTypeFilter.USER) },
            label = { Text(stringResource(R.string.filter_user_apps)) }
        )
        FilterChip(
            selected = selected == AppTypeFilter.SYSTEM,
            onClick = { onSelect(AppTypeFilter.SYSTEM) },
            label = { Text(stringResource(R.string.filter_system_apps)) }
        )
        FilterChip(
            selected = selected == AppTypeFilter.ALL,
            onClick = { onSelect(AppTypeFilter.ALL) },
            label = { Text(stringResource(R.string.filter_all_apps)) }
        )
    }
}

/**
 * Renders an app icon, rasterised at the size it is drawn rather than the
 * drawable's intrinsic size. A device with several hundred apps would otherwise
 * hold full-resolution bitmaps for every row the list has scrolled past.
 */
@Composable
internal fun AppIcon(entry: AppEntry, size: Dp = 40.dp) {
    val density = LocalDensity.current
    val bitmap = remember(entry.packageName, size) {
        val sizePx = with(density) { size.roundToPx() }
        entry.icon?.let { icon -> runCatching { icon.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull() }
    }
    val shape = RoundedCornerShape(8.dp)
    if (bitmap == null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape)
        )
    }
}

/** Shown in place of an empty list, so "nothing here" is distinguishable from "still loading". */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}
