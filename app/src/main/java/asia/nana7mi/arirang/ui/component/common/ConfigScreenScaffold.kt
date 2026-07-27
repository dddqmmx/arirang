package asia.nana7mi.arirang.ui.component.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import asia.nana7mi.arirang.ui.component.dialog.SaveConfigIconButton
import asia.nana7mi.arirang.ui.component.dialog.UnsavedChangesDialog

/**
 * The common chrome of a config screen: a centre-aligned top bar with back and
 * save affordances, and the discard/save prompt shown when leaving with unsaved
 * edits.
 *
 * Each `*ConfigScreen` had its own copy of this — the showUnsavedDialog flag,
 * the pinned scroll behaviour, requestBack(), the BackHandler, the Scaffold and
 * the trailing UnsavedChangesDialog block. The screens keep ownership of their
 * own config state; only the chrome is shared, so nothing about how a screen
 * edits its config changes.
 *
 * @param hasChanges whether the screen holds unsaved edits. Drives both the save
 *   button's tint and whether backing out prompts.
 * @param onSave persists the current edits and reports whether that succeeded.
 *   Returning false (validation rejected the input) keeps the prompt open and
 *   stays on the screen. Previously only LocationConfigScreen did this; the
 *   other seven navigated away regardless, discarding the edits the user had
 *   just been told to fix.
 * @param actions extra top-bar actions, placed before the save button.
 * @param floatingActionButton passed straight through to the [Scaffold], for the
 *   screens that add rows (SIM slots, IMEI slots, sensor entries).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigScreenScaffold(
    title: String,
    hasChanges: Boolean,
    onSave: () -> Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun requestBack() {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    BackHandler { requestBack() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    actions()
                    SaveConfigIconButton(hasChanges = hasChanges, onClick = { onSave() })
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = floatingActionButton,
        content = content
    )

    if (showUnsavedDialog) {
        UnsavedChangesDialog(
            onDismiss = { showUnsavedDialog = false },
            onDiscard = {
                showUnsavedDialog = false
                onBack()
            },
            onSave = {
                if (onSave()) {
                    showUnsavedDialog = false
                    onBack()
                }
            }
        )
    }
}
