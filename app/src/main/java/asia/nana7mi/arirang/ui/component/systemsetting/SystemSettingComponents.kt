package asia.nana7mi.arirang.ui.component.systemsetting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.SystemSettingPrefs
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.AppIcon
import asia.nana7mi.arirang.ui.component.common.PickerItem
import asia.nana7mi.arirang.ui.component.common.SearchablePickerDialog
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Time zones the platform knows about, as picker rows.
 *
 * Filtered by [SystemSettingPrefs.TIME_ZONE_ID] so every id offered here also
 * survives the store's own sanitisation — offering one that does not would let
 * the user pick a value that silently saves as empty.
 *
 * [emptyLabel] heads the list with the "no opinion" row (id `""`), which reads
 * as "leave unchanged" globally and "follow the default" in an override.
 */
internal fun timeZonePickerItems(emptyLabel: String): List<PickerItem> {
    val zones = TimeZone.getAvailableIDs()
        .filter { SystemSettingPrefs.TIME_ZONE_ID.matches(it) }
        .sorted()
        .map { id -> PickerItem(id = id, title = id, subtitle = utcOffsetLabel(id)) }
    return listOf(PickerItem(id = "", title = emptyLabel)) + zones
}

/** Locales the platform knows about, titled in their own language. */
internal fun languagePickerItems(emptyLabel: String): List<PickerItem> {
    val locales = Locale.getAvailableLocales()
        .asSequence()
        .filter { it.language.isNotEmpty() }
        .map { locale -> locale.toLanguageTag() to locale.getDisplayName(locale) }
        .filter { (tag, _) -> SystemSettingPrefs.LANGUAGE_TAG.matches(tag) }
        .distinctBy { (tag, _) -> tag }
        .sortedBy { (_, displayName) -> displayName.lowercase() }
        .map { (tag, displayName) -> PickerItem(id = tag, title = displayName, subtitle = tag) }
        .toList()
    return listOf(PickerItem(id = "", title = emptyLabel)) + locales
}

private fun utcOffsetLabel(zoneId: String): String {
    val offsetMinutes = TimeZone.getTimeZone(zoneId).rawOffset / 60_000
    val sign = if (offsetMinutes < 0) '-' else '+'
    val magnitude = abs(offsetMinutes)
    return "UTC%c%02d:%02d".format(sign, magnitude / 60, magnitude % 60)
}

/** One installed app, showing the time zone and language it will end up seeing. */
@Composable
internal fun AppOverrideRow(
    app: AppEntry,
    summary: String,
    isOverridden: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOverridden) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private enum class PickerTarget { TIME_ZONE, LANGUAGE }

/**
 * Per-app override editor.
 *
 * The two value rows open [SearchablePickerDialog] on top of this dialog rather
 * than using a dropdown: the time-zone list has several hundred entries and the
 * locale list more, which is unusable without search.
 */
@Composable
internal fun AppOverrideDialog(
    app: AppEntry,
    override: SystemSettingPrefs.Override?,
    timeZoneItems: List<PickerItem>,
    languageItems: List<PickerItem>,
    onSave: (SystemSettingPrefs.Override) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var enabled by remember(app.packageName) { mutableStateOf(override?.enabled ?: true) }
    var timeZoneId by remember(app.packageName) { mutableStateOf(override?.timeZoneId.orEmpty()) }
    var languageTag by remember(app.packageName) { mutableStateOf(override?.languageTag.orEmpty()) }
    var picker by remember(app.packageName) { mutableStateOf<PickerTarget?>(null) }

    val followDefault = stringResource(R.string.system_setting_follow_default)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.system_setting_override_title))
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = enabled,
                            onValueChange = { enabled = it },
                            role = Role.Switch
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.system_setting_override_enabled),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(checked = enabled, onCheckedChange = null)
                }

                ValueRow(
                    label = stringResource(R.string.system_setting_time_zone),
                    value = timeZoneId.ifEmpty { followDefault },
                    onClick = { picker = PickerTarget.TIME_ZONE }
                )
                ValueRow(
                    label = stringResource(R.string.system_setting_language),
                    value = languageTag.ifEmpty { followDefault },
                    onClick = { picker = PickerTarget.LANGUAGE }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        SystemSettingPrefs.Override(
                            enabled = enabled,
                            timeZoneId = timeZoneId,
                            languageTag = languageTag
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onClear != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.system_setting_clear_override))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )

    when (picker) {
        PickerTarget.TIME_ZONE -> SearchablePickerDialog(
            title = stringResource(R.string.system_setting_time_zone),
            items = timeZoneItems,
            selectedId = timeZoneId,
            searchPlaceholder = stringResource(R.string.system_setting_search_time_zone),
            onDismiss = { picker = null },
            onSelect = {
                timeZoneId = it
                picker = null
            }
        )

        PickerTarget.LANGUAGE -> SearchablePickerDialog(
            title = stringResource(R.string.system_setting_language),
            items = languageItems,
            selectedId = languageTag,
            searchPlaceholder = stringResource(R.string.system_setting_search_language),
            onDismiss = { picker = null },
            onSelect = {
                languageTag = it
                picker = null
            }
        )

        null -> Unit
    }
}

@Composable
internal fun ValueRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
