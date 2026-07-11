package asia.nana7mi.arirang.ui.screen.about

import asia.nana7mi.arirang.ui.component.about.*
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import asia.nana7mi.arirang.R

@Composable
fun AboutScreen() {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_about),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            AppInfoHeader()
        }

        item {
            InfoCard(
                title = stringResource(R.string.about_developer_title),
                content = stringResource(R.string.about_developer_name),
                icon = Icons.Default.Person,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/dddqmmx".toUri())
                    context.startActivity(intent)
                }
            )
        }

        item {
            InfoCard(
                title = stringResource(R.string.about_source_code_title),
                content = stringResource(R.string.about_source_code_content),
                icon = Icons.Default.Code,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/dddqmmx/arirang".toUri())
                    context.startActivity(intent)
                }
            )
        }
    }
}
