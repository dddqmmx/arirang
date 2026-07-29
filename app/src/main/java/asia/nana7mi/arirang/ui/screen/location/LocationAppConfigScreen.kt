package asia.nana7mi.arirang.ui.screen.location

import asia.nana7mi.arirang.ui.component.location.*
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import asia.nana7mi.arirang.ui.component.common.AppEntry
import asia.nana7mi.arirang.ui.component.common.AppSearchField
import asia.nana7mi.arirang.ui.component.common.AppTypeFilter
import asia.nana7mi.arirang.ui.component.common.AppTypeFilterChips
import asia.nana7mi.arirang.ui.component.common.loadInstalledApps
import asia.nana7mi.arirang.ui.component.common.matching

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
        .matching(filter, query)
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item {
                AppSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.location_search_apps)
                )
            }
            item {
                AppTypeFilterChips(selected = filter, onSelect = { filter = it })
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
