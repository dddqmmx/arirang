package asia.nana7mi.arirang.ui.screen.advanced

import asia.nana7mi.arirang.ui.component.advanced.*
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import asia.nana7mi.arirang.R
import asia.nana7mi.arirang.data.datastore.GlobalConfigPrefs

@Composable
internal fun AdvancedSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var restrictHotSwitching by remember {
        mutableStateOf(GlobalConfigPrefs.loadConfig(context).restrictHotSwitching)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = stringResource(R.string.advanced_settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.advanced_settings_title)) {
                SettingCardWithSwitch(
                    title = stringResource(R.string.restrict_hot_switching_title),
                    summary = stringResource(R.string.restrict_hot_switching_summary),
                    icon = Icons.Default.Terminal,
                    checked = restrictHotSwitching,
                    onCheckedChange = { checked ->
                        if (GlobalConfigPrefs.saveConfig(context, GlobalConfigPrefs.Config(checked))) {
                            restrictHotSwitching = checked
                            Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}
