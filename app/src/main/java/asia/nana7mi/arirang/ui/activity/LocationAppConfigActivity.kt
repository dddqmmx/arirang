package asia.nana7mi.arirang.ui.activity

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs.Profile
import asia.nana7mi.arirang.ui.ui.theme.ArirangTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocationAppConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ArirangTheme {
                LocationAppConfigScreen(onBack = { finish() })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LocationAppConfigScreen(onBack: () -> Unit) {
        val context = LocalContext.current
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
        var config by remember { mutableStateOf(LocationConfigPrefs.loadConfig(context)) }
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf(AppTypeFilter.USER) }
        var editingApp by remember { mutableStateOf<AppEntry?>(null) }

        LaunchedEffect(Unit) {
            apps = loadInstalledApps()
        }

        val filteredApps = apps
            .filter { app ->
                when (filter) {
                    AppTypeFilter.USER -> !app.isSystemApp
                    AppTypeFilter.SYSTEM -> app.isSystemApp
                    AppTypeFilter.ALL -> true
                }
            }
            .filter { app ->
                val normalizedQuery = query.trim().lowercase()
                normalizedQuery.isEmpty() ||
                    app.label.lowercase().contains(normalizedQuery) ||
                    app.packageName.lowercase().contains(normalizedQuery)
            }
            .sortedWith(
                compareByDescending<AppEntry> { config.perPackage.containsKey(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.location_app_config_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { padding ->
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.location_search_apps)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filter == AppTypeFilter.USER,
                            onClick = { filter = AppTypeFilter.USER },
                            label = { Text(stringResource(R.string.location_filter_user_apps)) }
                        )
                        FilterChip(
                            selected = filter == AppTypeFilter.SYSTEM,
                            onClick = { filter = AppTypeFilter.SYSTEM },
                            label = { Text(stringResource(R.string.location_filter_system_apps)) }
                        )
                        FilterChip(
                            selected = filter == AppTypeFilter.ALL,
                            onClick = { filter = AppTypeFilter.ALL },
                            label = { Text(stringResource(R.string.location_filter_all_apps)) }
                        )
                    }
                }
                items(
                    count = filteredApps.size,
                    key = { index -> filteredApps[index].packageName }
                ) { index ->
                    val app = filteredApps[index]
                    val profile = config.perPackage[app.packageName]
                    AppLocationRow(
                        app = app,
                        profile = profile,
                        onClick = { editingApp = app },
                        onDelete = profile?.let {
                            {
                                val updated = config.copy(perPackage = config.perPackage - app.packageName)
                                LocationConfigPrefs.saveConfig(context, updated)
                                config = updated
                                Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        editingApp?.let { app ->
            EditProfileDialog(
                app = app,
                profile = config.perPackage[app.packageName],
                onDismiss = { editingApp = null },
                onSave = { profile ->
                    val updated = config.copy(perPackage = config.perPackage + (app.packageName to profile))
                    LocationConfigPrefs.saveConfig(context, updated)
                    config = updated
                    editingApp = null
                    Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
                },
                onDelete = config.perPackage[app.packageName]?.let {
                    {
                        val updated = config.copy(perPackage = config.perPackage - app.packageName)
                        LocationConfigPrefs.saveConfig(context, updated)
                        config = updated
                        editingApp = null
                        Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private suspend fun loadInstalledApps(): List<AppEntry> {
        return withContext(Dispatchers.IO) {
            val pm = packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { app ->
                    AppEntry(
                        label = pm.getApplicationLabel(app).toString(),
                        packageName = app.packageName,
                        isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        icon = pm.getApplicationIcon(app)
                    )
                }
        }
    }

    @Composable
    private fun AppLocationRow(
        app: AppEntry,
        profile: Profile?,
        onClick: () -> Unit,
        onDelete: (() -> Unit)?
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
                app.icon?.let { icon ->
                    Image(
                        bitmap = remember(app.packageName) { icon.toBitmap().asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = profile?.let {
                            stringResource(
                                R.string.location_app_configured,
                                it.latitude.toString(),
                                it.longitude.toString()
                            )
                        } ?: stringResource(R.string.location_app_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profile == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.location_remove_app_profile)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EditProfileDialog(
        app: AppEntry,
        profile: Profile?,
        onDismiss: () -> Unit,
        onSave: (Profile) -> Unit,
        onDelete: (() -> Unit)?
    ) {
        val context = LocalContext.current
        var enabled by remember(app.packageName) { mutableStateOf(profile?.enabled ?: false) }
        var latitudeText by remember(app.packageName) {
            mutableStateOf((profile?.latitude ?: LocationConfigPrefs.DEFAULT_LATITUDE).toString())
        }
        var longitudeText by remember(app.packageName) {
            mutableStateOf((profile?.longitude ?: LocationConfigPrefs.DEFAULT_LONGITUDE).toString())
        }
        var altitudeText by remember(app.packageName) {
            mutableStateOf((profile?.altitude ?: LocationConfigPrefs.DEFAULT_ALTITUDE).toString())
        }
        var accuracyText by remember(app.packageName) {
            mutableStateOf((profile?.accuracy ?: LocationConfigPrefs.DEFAULT_ACCURACY).toString())
        }
        var speedText by remember(app.packageName) {
            mutableStateOf((profile?.speed ?: LocationConfigPrefs.DEFAULT_SPEED).toString())
        }
        var bearingText by remember(app.packageName) {
            mutableStateOf((profile?.bearing ?: LocationConfigPrefs.DEFAULT_BEARING).toString())
        }
        var satellitesText by remember(app.packageName) {
            mutableStateOf((profile?.satellites ?: LocationConfigPrefs.DEFAULT_SATELLITES).toString())
        }
        var revision by remember(app.packageName) { mutableLongStateOf(0L) }
        var validationError by remember(app.packageName) { mutableStateOf<String?>(null) }

        fun parseProfile(): Profile? {
            val parsedLatitude = parseDouble(
                latitudeText,
                context.getString(R.string.location_field_latitude),
                -90.0,
                90.0,
                context.getString(R.string.location_invalid_number)
            )
            val parsedLongitude = parseDouble(
                longitudeText,
                context.getString(R.string.location_field_longitude),
                -180.0,
                180.0,
                context.getString(R.string.location_invalid_number)
            )
            val parsedAltitude = parseDouble(
                altitudeText,
                context.getString(R.string.location_field_altitude),
                -500.0,
                10000.0,
                context.getString(R.string.location_invalid_number)
            )
            val parsedAccuracy = parseFloat(
                accuracyText,
                context.getString(R.string.location_field_accuracy),
                0.1f,
                10000f,
                context.getString(R.string.location_invalid_number)
            )
            val parsedSpeed = parseFloat(
                speedText,
                context.getString(R.string.location_field_speed),
                0.0f,
                400f,
                context.getString(R.string.location_invalid_number)
            )
            val parsedBearing = parseFloat(
                bearingText,
                context.getString(R.string.location_field_bearing),
                0.0f,
                360f,
                context.getString(R.string.location_invalid_number)
            )
            val parsedSatellites = parseInt(
                satellitesText,
                context.getString(R.string.location_field_satellites),
                0,
                64,
                context.getString(R.string.location_invalid_number)
            )
            val firstError = listOf(
                parsedLatitude,
                parsedLongitude,
                parsedAltitude,
                parsedAccuracy,
                parsedSpeed,
                parsedBearing,
                parsedSatellites
            ).firstOrNull { it.error != null }?.error

            if (firstError != null) {
                validationError = firstError
                return null
            }

            validationError = null
            return Profile(
                enabled = enabled,
                latitude = parsedLatitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LATITUDE,
                longitude = parsedLongitude.doubleValue ?: LocationConfigPrefs.DEFAULT_LONGITUDE,
                altitude = parsedAltitude.doubleValue ?: LocationConfigPrefs.DEFAULT_ALTITUDE,
                accuracy = parsedAccuracy.floatValue ?: LocationConfigPrefs.DEFAULT_ACCURACY,
                speed = parsedSpeed.floatValue ?: LocationConfigPrefs.DEFAULT_SPEED,
                bearing = parsedBearing.floatValue ?: LocationConfigPrefs.DEFAULT_BEARING,
                satellites = parsedSatellites.intValue ?: LocationConfigPrefs.DEFAULT_SATELLITES
            )
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    app.icon?.let { icon ->
                        Image(
                            bitmap = remember(app.packageName) { icon.toBitmap().asImageBitmap() },
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Column {
                        Text(stringResource(R.string.location_edit_app_location))
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .toggleable(
                                value = enabled,
                                onValueChange = { enabled = it },
                                role = androidx.compose.ui.semantics.Role.Switch
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.location_hook_enabled),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                    
                    LocationTextField(
                        label = stringResource(R.string.location_field_latitude),
                        value = latitudeText,
                        revision = revision,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { latitudeText = it }
                    )
                    LocationTextField(
                        label = stringResource(R.string.location_field_longitude),
                        value = longitudeText,
                        revision = revision,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { longitudeText = it }
                    )
                    LocationTextField(
                        label = stringResource(R.string.location_field_altitude),
                        value = altitudeText,
                        revision = revision,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { altitudeText = it }
                    )
                    LocationTextField(
                        label = stringResource(R.string.location_field_accuracy),
                        value = accuracyText,
                        revision = revision,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { accuracyText = it }
                    )
                    LocationTextField(
                        label = stringResource(R.string.location_field_speed),
                        value = speedText,
                        revision = revision,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { speedText = it }
                    )
                    LocationTextField(
                        label = stringResource(R.string.location_field_bearing),
                        value = bearingText,
                        revision = revision,
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { bearingText = it }
                    )
                    LocationTextField(
                        label = stringResource(R.string.location_field_satellites),
                        value = satellitesText,
                        revision = revision,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { satellitesText = it.filter(Char::isDigit) }
                    )
                    validationError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    parseProfile()?.let(onSave)
                    revision++
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onDelete?.let {
                        TextButton(onClick = it) {
                            Text(stringResource(R.string.location_remove_app_profile))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        )
    }

    @Composable
    private fun LocationTextField(
        label: String,
        value: String,
        revision: Long,
        keyboardType: KeyboardType,
        onValueChange: (String) -> Unit
    ) {
        var localValue by remember(revision, label) { mutableStateOf(value) }
        OutlinedTextField(
            value = localValue,
            onValueChange = {
                localValue = it
                onValueChange(it)
            },
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    private fun parseDouble(text: String, label: String, min: Double, max: Double, errorFormat: String): ParsedValue {
        val value = text.trim().toDoubleOrNull()
        return if (value == null || value !in min..max) {
            ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
        } else {
            ParsedValue(doubleValue = value)
        }
    }

    private fun parseFloat(text: String, label: String, min: Float, max: Float, errorFormat: String): ParsedValue {
        val value = text.trim().toFloatOrNull()
        return if (value == null || value !in min..max) {
            ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
        } else {
            ParsedValue(floatValue = value)
        }
    }

    private fun parseInt(text: String, label: String, min: Int, max: Int, errorFormat: String): ParsedValue {
        val value = text.trim().toIntOrNull()
        return if (value == null || value !in min..max) {
            ParsedValue(error = errorFormat.format(label, min.toString(), max.toString()))
        } else {
            ParsedValue(intValue = value)
        }
    }

    private data class ParsedValue(
        val doubleValue: Double? = null,
        val floatValue: Float? = null,
        val intValue: Int? = null,
        val error: String? = null
    )

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val isSystemApp: Boolean,
        val icon: android.graphics.drawable.Drawable? = null
    )

    private enum class AppTypeFilter {
        USER,
        SYSTEM,
        ALL
    }
}
