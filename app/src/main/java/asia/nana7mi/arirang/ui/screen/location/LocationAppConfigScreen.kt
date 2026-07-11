package asia.nana7mi.arirang.ui.screen.location

import asia.nana7mi.arirang.ui.component.location.*
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.LocationConfigPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationAppConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var config by remember { mutableStateOf(LocationConfigPrefs.loadConfig(context)) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppTypeFilter.USER) }
    var editingApp by remember { mutableStateOf<AppEntry?>(null) }

    LaunchedEffect(Unit) {
        apps = loadInstalledApps(context)
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

private suspend fun loadInstalledApps(context: Context): List<AppEntry> {
    return withContext(Dispatchers.IO) {
        val pm = context.packageManager
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
