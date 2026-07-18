package io.github.rufim.alice.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.R
import io.github.rufim.alice.engine.EngineActivity
import io.github.rufim.alice.engine.XSystem35Activity
import io.github.rufim.alice.engine.XSystem4Activity
import io.github.rufim.alice.ui.AliceColors
import io.github.rufim.alice.ui.AliceTheme

/** Лаунчер: список установленных игр обоих движков, установка из ZIP, меню. */
class LauncherActivity : ComponentActivity(), GameListObserver {
    private var gameList: GameList? = null
    private var items by mutableStateOf(listOf<Item>())
    private var installProgress by mutableStateOf<String?>(null)
    private var installError by mutableStateOf<Int?>(null)

    private val pickZip = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val input = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
        installProgress = ""
        gameList?.install(input, this)
    }

    private fun refresh() {
        gameList?.observer = null
        gameList = GameList(this).also { it.observer = this }
        items = gameList!!.snapshot()
        if (gameList!!.isInstalling) installProgress = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            AliceTheme {
                LauncherScreen(
                    items = items,
                    installProgress = installProgress,
                    installError = installError,
                    usageHtml = getString(R.string.usage),
                    onRefresh = { refresh() },
                    onInstallZip = { pickZip.launch("application/zip") },
                    onLicenses = { startActivity(Intent(this, LicensesActivity::class.java)) },
                    onErrorDismiss = { installError = null },
                    onPlay = { launchGame(it) },
                    onUninstall = { item ->
                        gameList?.uninstall(item)
                        items = gameList?.snapshot() ?: emptyList()
                    },
                )
            }
        }
    }

    private fun launchGame(item: Item) {
        val cls = when (item.engine) {
            Engine.XSYSTEM4 -> XSystem4Activity::class.java
            Engine.XSYSTEM35 -> XSystem35Activity::class.java
        }
        val i = Intent().setClass(applicationContext, cls)
        i.putExtra(EngineActivity.EXTRA_GAME_ROOT, item.path.path)
        i.putExtra(EngineActivity.EXTRA_SAVE_DIR, item.savedir!!.path)
        startActivity(i)
    }

    override fun onDestroy() {
        gameList?.observer = null
        super.onDestroy()
    }

    // --- GameListObserver (зовётся на главном потоке) ---
    override fun onInstallProgress(path: String) {
        installProgress = getString(R.string.install_progress, path)
    }

    override fun onInstallSuccess() {
        installProgress = null
        items = gameList?.snapshot() ?: emptyList()
    }

    override fun onInstallFailure(msgId: Int) {
        installProgress = null
        installError = msgId
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
private fun LauncherScreen(
    items: List<Item>,
    installProgress: String?,
    installError: Int?,
    usageHtml: String,
    onRefresh: () -> Unit,
    onInstallZip: () -> Unit,
    onLicenses: () -> Unit,
    onErrorDismiss: () -> Unit,
    onPlay: (Item) -> Unit,
    onUninstall: (Item) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var uninstallItem by remember { mutableStateOf<Item?>(null) }
    var errorItem by remember { mutableStateOf<Item?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(AliceColors.Background)) {
        // топ-бар
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AliceColors.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("xsystems", fontSize = 22.sp, color = AliceColors.TextPrimary)
            Spacer(modifier = Modifier.weight(1f))
            Box {
                Text(
                    "⋮", fontSize = 24.sp, color = AliceColors.TextPrimary,
                    modifier = Modifier
                        .combinedClickable(onClick = { menuOpen = true })
                        .padding(horizontal = 12.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Обновить") },
                        onClick = { menuOpen = false; onRefresh() })
                    DropdownMenuItem(text = { Text("Установить из ZIP") },
                        onClick = { menuOpen = false; onInstallZip() })
                    DropdownMenuItem(text = { Text("Помощь") },
                        onClick = { menuOpen = false; helpOpen = true })
                    DropdownMenuItem(text = { Text("Лицензии") },
                        onClick = { menuOpen = false; onLicenses() })
                }
            }
        }

        if (items.isEmpty()) {
            Text(
                AnnotatedString.fromHtml(usageHtml),
                color = AliceColors.TextPrimary,
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            )
        } else {
            LazyColumn {
                items(items) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (item.error != null) errorItem = item else onPlay(item)
                                },
                                onLongClick = { uninstallItem = item },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = remember(item.path) { item.getIconBitmap(96) }
                        if (icon != null) {
                            Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        } else {
                            Box(modifier = Modifier.size(40.dp))
                        }
                        Text(
                            item.name,
                            fontSize = 18.sp,
                            color = if (item.error != null) AliceColors.TextSecondary
                                    else AliceColors.TextPrimary,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }

    // --- диалоги ---
    if (installProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(androidx.compose.ui.res.stringResource(R.string.install_dialog_title)) },
            text = { Text(installProgress) },
            confirmButton = {},
        )
    }
    installError?.let { msgId ->
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            title = { Text(androidx.compose.ui.res.stringResource(R.string.error)) },
            text = { Text(androidx.compose.ui.res.stringResource(msgId)) },
            confirmButton = { TextButton(onClick = onErrorDismiss) { Text("OK") } },
        )
    }
    errorItem?.let { item ->
        AlertDialog(
            onDismissRequest = { errorItem = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.error)) },
            text = { Text(item.error ?: "") },
            confirmButton = { TextButton(onClick = { errorItem = null }) { Text("OK") } },
        )
    }
    uninstallItem?.let { item ->
        AlertDialog(
            onDismissRequest = { uninstallItem = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.uninstall_dialog_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.uninstall_dialog_message, item.name)) },
            confirmButton = {
                TextButton(onClick = { onUninstall(item); uninstallItem = null }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { uninstallItem = null }) { Text("Отмена") }
            },
        )
    }
    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            text = {
                Text(
                    AnnotatedString.fromHtml(usageHtml),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("OK") } },
        )
    }
}
