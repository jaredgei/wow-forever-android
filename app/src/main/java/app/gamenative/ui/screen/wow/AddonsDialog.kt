package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.addons.AddonDetails
import app.gamenative.addons.AddonInstaller
import app.gamenative.addons.AddonManager
import app.gamenative.addons.OverlapDeclined
import app.gamenative.addons.AddonSites
import app.gamenative.addons.AddonSources
import app.gamenative.addons.GitHubProvider
import app.gamenative.addons.Provider
import app.gamenative.addons.TukuiProvider
import app.gamenative.addons.WowInterfaceProvider
import app.gamenative.addons.SourceRecord
import app.gamenative.addons.UpdateInfo
import app.gamenative.addons.ImportResult
import app.gamenative.addons.ImportScan
import app.gamenative.addons.InstalledAddon
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.utils.StorageUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "wow_forever"
private const val KEY_IMPORT_DIR = "addon_import_dir"
private const val KEY_IMPORT_DELETE = "addon_import_delete"

private fun defaultImportDir() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

private fun importDir(context: Context): File {
    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IMPORT_DIR, null)
    return saved?.let(::File) ?: defaultImportDir()
}

@Composable
fun AddonsDialog(gameRoot: File, onDismiss: () -> Unit, openedWithKeyboard: Boolean = false) {
    // Cover the whole screen, behind the system bars too, so the launcher doesn't show through along the bottom.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(WowBackgroundGradient)).windowInsetsPadding(WindowInsets.systemBars)) {
            GoldFocusTheme { AddonsContent(gameRoot, onDismiss, openedWithKeyboard) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AddonsContent(gameRoot: File, onDismiss: () -> Unit, openedWithKeyboard: Boolean) {
    var tab by remember { mutableStateOf(0) }
    // Opened with a D-pad or gamepad: put focus on the first tab so the first press isn't wasted finding it. A dialog is
    // its own window and starts in touch mode, so it has to be told that the player is navigating by keys.
    val tabFocus = remember { List(4) { FocusRequester() } }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        if (openedWithKeyboard) {
            delay(150)
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { tabFocus[0].requestFocus() }
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ADDONS", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = WowGold, modifier = Modifier.weight(1f))
            IconButton(modifier = Modifier.focusGlow(24.dp), onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = WowMuted) }
        }
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = WowGold,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = WowGold)
            },
        ) {
            Tab(modifier = Modifier.focusRequester(tabFocus[0]).focusGlow(8.dp), selected = tab == 0, onClick = { tab = 0 }, text = { Text("My Addons") })
            Tab(modifier = Modifier.focusRequester(tabFocus[1]).focusGlow(8.dp), selected = tab == 1, onClick = { tab = 1 }, text = { Text("Get Addons") })
            Tab(modifier = Modifier.focusRequester(tabFocus[2]).focusGlow(8.dp), selected = tab == 2, onClick = { tab = 2 }, text = { Text("Import") })
            Tab(modifier = Modifier.focusRequester(tabFocus[3]).focusGlow(8.dp), selected = tab == 3, onClick = { tab = 3 }, text = { Text("From URL") })
        }
        Spacer(modifier = Modifier.size(12.dp))
        // The controls along the top of each tab send Up to the tab you're on (see upToTab).
        CompositionLocalProvider(LocalTabFocus provides tabFocus[tab]) {
            when (tab) {
                0 -> MyAddonsTab(gameRoot)
                1 -> GetAddonsTab(gameRoot)
                2 -> ImportTab(gameRoot)
                else -> UrlTab(gameRoot)
            }
        }
    }
}

@Composable
private fun MyAddonsTab(gameRoot: File) {
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<InstalledAddon>?>(null) }
    var pendingRemove by remember { mutableStateOf<InstalledAddon?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var updates by remember { mutableStateOf(emptyList<UpdateInfo>()) }
    var records by remember { mutableStateOf(emptyList<SourceRecord>()) }
    var checking by remember { mutableStateOf(false) }
    val updating = remember { mutableStateListOf<String>() }
    val asker = rememberOverlapAsker()
    OverlapDialog(asker)
    // Up from the first row lands on "Check for updates" (which sends Up on to the tab), so those buttons stay reachable.
    val checkReq = remember { FocusRequester() }
    var detailFor by remember { mutableStateOf<Pair<InstalledAddon, SourceRecord?>?>(null) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val list = AddonManager.listInstalled(gameRoot)
                AddonSources.prune(gameRoot, list.map { it.folder }.toSet())
                records = AddonSources.load(gameRoot)
                addons = list
            }
        }
    }
    /** A manual check refreshes the catalog and says so either way; the automatic one on opening only speaks up if something's new. */
    fun checkUpdates(manual: Boolean = true) {
        scope.launch {
            checking = true
            if (manual) message = null
            try {
                val catalog = loadCatalog(context, force = manual)
                updates = withContext(Dispatchers.IO) { AddonInstaller.findUpdates(gameRoot, context.cacheDir, catalog, checkGitHub = true) }
                if (manual || updates.isNotEmpty()) {
                    message = if (updates.isEmpty()) "Everything installed from these sites is up to date." else "${updates.size} update(s) available."
                }
            } catch (e: Exception) {
                if (manual) message = "Couldn't check for updates: ${e.message}"
            }
            checking = false
        }
    }
    fun applyUpdates(list: List<UpdateInfo>) {
        scope.launch {
            val catalog = runCatching { loadCatalog(context) }.getOrDefault(emptyList())
            val done = ArrayList<UpdateInfo>()
            val failed = ArrayList<String>()
            for (info in list) {
                updating.add(info.record.id)
                try {
                    withContext(Dispatchers.IO) { AddonInstaller.update(gameRoot, context.cacheDir, info, catalog, asker.gate) }
                    done.add(info)
                } catch (e: OverlapDeclined) {
                    failed.add("${info.record.name}: kept the installed copy")
                } catch (e: Exception) {
                    failed.add("${info.record.name}: ${e.message}")
                }
                updating.remove(info.record.id)
            }
            updates = updates - done.toSet()
            message = "Updated ${done.size} addon(s)." + if (failed.isNotEmpty()) " Failed: ${failed.joinToString("; ")}" else ""
            reload()
        }
    }
    LaunchedEffect(gameRoot) {
        reload()
        // Like Obtainium: look for new releases of everything we track as soon as the list opens.
        if (withContext(Dispatchers.IO) { AddonSources.load(gameRoot).isNotEmpty() }) checkUpdates(manual = false)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = addons?.let { "${it.size} installed in ${AddonManager.addonsDir(gameRoot).path}" } ?: "Loading...",
            fontSize = 12.sp, color = WowSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (updates.isNotEmpty()) {
            TextButton(modifier = Modifier.upToTab().focusGlow(20.dp), onClick = { applyUpdates(updates) }, enabled = updating.isEmpty()) { Text("Update all (${updates.size})", color = WowGold, fontWeight = FontWeight.Bold) }
        }
        TextButton(modifier = Modifier.focusRequester(checkReq).upToTab().focusGlow(20.dp), onClick = { checkUpdates() }, enabled = !checking) { Text(if (checking) "Checking..." else "Check for updates", color = WowMuted) }
        TextButton(modifier = Modifier.upToTab().focusGlow(20.dp), onClick = ::reload) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = WowMuted)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Refresh", color = WowMuted)
        }
    }
    message?.let { Text(it, fontSize = 12.sp, color = WowGold, modifier = Modifier.padding(vertical = 4.dp)) }

    val list = addons
    when {
        list == null -> CircularProgressIndicator(color = WowGold, modifier = Modifier.size(28.dp))
        list.isEmpty() -> Text("No addons installed yet. Browse Get Addons, paste a link in From URL, or import a downloaded zip.", color = WowMuted, fontSize = 14.sp)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list, key = { _, it -> it.folder }) { index, addon ->
                val upToCheck = if (index == 0) Modifier.focusProperties { up = checkReq } else Modifier
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Every installed addon opens its page, tracked or not; untracked ones show what their TOC says.
                    Column(modifier = Modifier.weight(1f).then(upToCheck).focusGlow(12.dp).clickable { detailFor = addon to records.firstOrNull { addon.folder in it.folders } }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Text(addon.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val source = records.firstOrNull { addon.folder in it.folders }?.provider?.label
                        val detail = listOfNotNull(addon.version?.let(::displayVersion), addon.author, source?.let { "from $it" }, addon.interfaceVersion?.let { "Interface ${interfaceSummary(it)}" }).joinToString("  ·  ")
                        if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = WowSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        addon.notes?.let { Text(it, fontSize = 12.sp, color = WowMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    val update = updates.firstOrNull { addon.folder in it.record.folders }
                    if (update != null) {
                        if (update.record.id in updating) {
                            CircularProgressIndicator(color = WowGold, modifier = Modifier.padding(end = 12.dp).size(24.dp))
                        } else {
                            TextButton(modifier = Modifier.then(upToCheck).focusGlow(20.dp), onClick = { applyUpdates(listOf(update)) }) { Text(updateLabel(update), color = WowGold, fontWeight = FontWeight.Bold) }
                        }
                    }
                    IconButton(modifier = Modifier.then(upToCheck).focusGlow(24.dp), onClick = { pendingRemove = addon }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${addon.title}", tint = WowError)
                    }
                }
            }
        }
    }

    detailFor?.let { (addon, record) ->
        val update = updates.firstOrNull { addon.folder in it.record.folders }
        AddonDetailDialog(
            title = addon.title,
            subtitle = listOfNotNull(record?.provider?.label ?: "Imported", addon.version?.let(::displayVersion), addon.author).joinToString("  ·  "),
            site = AddonSites.forAddon(addon.title, website = addon.website, wowiId = addon.wowiId, curseId = addon.curseId),
            load = {
                withContext(Dispatchers.IO) {
                    when (record?.provider) {
                        Provider.WOWINTERFACE -> WowInterfaceProvider.details(record.id)
                        Provider.TUKUI -> TukuiProvider.details(record.id)
                        Provider.GITHUB -> GitHubProvider.details(GitHubProvider.repoOfRecord(record.id) ?: error("Bad repository"))
                        else -> localDetails(addon, record)
                    }
                }
            },
            action = update?.let { DetailAction(updateLabel(it), enabled = true, busy = it.record.id in updating) { applyUpdates(listOf(it)) } },
            onDismiss = { detailFor = null },
            openedWithKeyboard = isKeyboardNavigating(),
        )
    }

    pendingRemove?.let { addon ->
        // Start on Cancel: the safe choice for a destructive prompt, and the first key press isn't spent finding it.
        val cancelFocus = remember { FocusRequester() }
        val keyboardNav = isKeyboardNavigating()
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove ${addon.title}?") },
            text = {
                Text("This deletes the addon's folder (${addon.folder}). Your settings in WTF are not touched.")
                AutoFocus(cancelFocus, keyboardNav)
            },
            confirmButton = {
                TextButton(modifier = Modifier.focusGlow(20.dp), onClick = {
                    pendingRemove = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { AddonManager.remove(gameRoot, addon.folder) }.getOrDefault(false) }
                        message = if (ok) "Removed ${addon.title}" else "Couldn't remove ${addon.title}"
                        reload()
                    }
                }) { Text("Remove", color = WowError) }
            },
            dismissButton = { TextButton(modifier = Modifier.focusRequester(cancelFocus).focusGlow(20.dp), onClick = { pendingRemove = null }) { Text("Cancel", color = WowMuted) } },
        )
    }
}

@Composable
private fun ColumnScope.ImportTab(gameRoot: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var folder by remember { mutableStateOf(importDir(context)) }
    var deleteAfter by remember { mutableStateOf(prefs.getBoolean(KEY_IMPORT_DELETE, false)) }
    var scan by remember { mutableStateOf<ImportScan?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ImportResult>?>(null) }
    val asker = rememberOverlapAsker()
    OverlapDialog(asker)

    fun rescan() {
        scope.launch {
            scan = null
            val found = withContext(Dispatchers.IO) { AddonManager.scanFolder(folder) }
            scan = found
            selected.clear()
            // Candidates are newest first; skip a zip whose addons a newer one already covers (e.g. per-flavor builds).
            val covered = HashSet<String>()
            for (c in found.candidates) if (covered.addAll(c.folders)) selected.add(c.file.path)
        }
    }
    LaunchedEffect(folder) { rescan() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        prefs.edit().putString(KEY_IMPORT_DIR, path).apply()
        results = null
        folder = File(path)
    }

    var showSteps by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Download an addon's zip from a site like CurseForge, then import it here.", fontSize = 12.sp, color = WowMuted, modifier = Modifier.weight(1f))
        TextButton(modifier = Modifier.upToTab().focusGlow(20.dp), onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide steps" else "How?", color = WowGold) }
        Button(
            modifier = Modifier.upToTab().focusGlow(10.dp),
            onClick = { openUrl(context, CURSEFORGE_URL) },
            colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
            shape = RoundedCornerShape(10.dp),
        ) { Text("Open CurseForge", fontWeight = FontWeight.Bold) }
    }
    if (showSteps) CurseForgeSteps()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("IMPORT FOLDER", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WowGold)
            Text(folder.path, fontSize = 13.sp, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(modifier = Modifier.focusGlow(20.dp), onClick = { picker.launch(null) }) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = WowMuted)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Change", color = WowMuted)
        }
        if (folder.path != defaultImportDir().path) {
            TextButton(modifier = Modifier.focusGlow(20.dp), onClick = {
                prefs.edit().remove(KEY_IMPORT_DIR).apply()
                results = null
                folder = defaultImportDir()
            }) { Text("Use Downloads", color = WowMuted) }
        }
        TextButton(modifier = Modifier.focusGlow(20.dp), onClick = { results = null; rescan() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Rescan", modifier = Modifier.size(18.dp), tint = WowMuted)
        }
    }
    Spacer(modifier = Modifier.size(4.dp))

    val current = scan
    val done = results
    when {
        done != null -> ImportResults(done) { results = null; rescan() }
        busy || current == null -> CircularProgressIndicator(color = WowGold, modifier = Modifier.size(28.dp))
        current.candidates.isEmpty() -> {
            Text("No addon zips found in this folder.", fontSize = 14.sp, color = WowMuted)
            if (!showSteps) CurseForgeSteps()
            if (current.skipped.isNotEmpty()) Text("${current.skipped.size} other zip file(s) were ignored because they don't contain an addon.", fontSize = 12.sp, color = WowSubtle)
        }
        else -> {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(current.candidates, key = { it.file.path }) { candidate ->
                    val checked = candidate.file.path in selected
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            modifier = Modifier.focusGlow(8.dp),
                            checked = checked,
                            onCheckedChange = { if (it) selected.add(candidate.file.path) else selected.remove(candidate.file.path) },
                            colors = CheckboxDefaults.colors(checkedColor = WowBronze),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(candidate.file.name, fontSize = 14.sp, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(candidate.folders.joinToString(", "), fontSize = 12.sp, color = WowSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (current.skipped.isNotEmpty()) Text("${current.skipped.size} other zip file(s) ignored: no addon inside.", fontSize = 12.sp, color = WowSubtle, modifier = Modifier.padding(top = 6.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    modifier = Modifier.focusGlow(8.dp),
                    checked = deleteAfter,
                    onCheckedChange = { deleteAfter = it; prefs.edit().putBoolean(KEY_IMPORT_DELETE, it).apply() },
                    colors = CheckboxDefaults.colors(checkedColor = WowBronze),
                )
                Text("Delete zips after importing", fontSize = 13.sp, color = Color(0xFFE2E8F0), modifier = Modifier.weight(1f))
                Button(
                    modifier = Modifier.focusGlow(14.dp),
                    onClick = {
                        val files = current.candidates.map { it.file }.filter { it.path in selected }
                        busy = true
                        scope.launch {
                            results = withContext(Dispatchers.IO) { AddonManager.importFiles(gameRoot, files, deleteAfter, asker.gate) }
                            busy = false
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("IMPORT ${selected.size} ADDON ZIP${if (selected.size == 1) "" else "S"}", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}

private const val CURSEFORGE_URL = "https://www.curseforge.com/wow/addons"

@Composable
private fun CurseForgeSteps() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            "1.  Tap Open CurseForge and find the addon you want.",
            "2.  Open its Files tab and pick the newest file for WoW Forever. If there isn't one, pick a Retail 12.x (Midnight) file.",
            "3.  Tap Download. Your browser saves a .zip file to your Downloads folder.",
            "4.  Come back here, tick the zip in the list below and tap Import. Turn on \"Delete zips after importing\" to keep Downloads tidy.",
        ).forEach { Text(it, fontSize = 12.sp, color = Color(0xFFE2E8F0), lineHeight = 16.sp) }
    }
}

/** For addons with no page we can read: what their own TOC says, and where they came from. */
private fun localDetails(addon: InstalledAddon, record: SourceRecord?): AddonDetails {
    val origin = when (record?.provider) {
        Provider.URL -> "Installed from a web link (${record.id}). Updates are tracked by that link."
        else -> "Installed from a zip you imported, so it isn't tracked for updates."
    }
    return AddonDetails(
        description = listOfNotNull(addon.notes, origin, "Folder: ${addon.folder}").joinToString("\n\n"),
        changelog = null,
        images = emptyList(),
        homepage = null,
        markdown = false,
    )
}

private fun updateLabel(update: UpdateInfo) =
    if (update.record.provider == Provider.URL) "Update (new file)" else "Update to ${displayVersion(update.newVersion)}"

@Composable
private fun ColumnScope.ImportResults(results: List<ImportResult>, onDone: () -> Unit) {
    val ok = results.count { it.ok }
    Text("Imported $ok of ${results.size}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WowGold)
    Spacer(modifier = Modifier.size(6.dp))
    LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(results, key = { it.file.path }) { r ->
            val text = if (r.ok) "${r.file.name}: ${r.folders.joinToString(", ")}${if (r.deleted) " (zip deleted)" else ""}" else "${r.file.name}: ${r.error}"
            Text(text, fontSize = 13.sp, color = if (r.ok) Color(0xFFE2E8F0) else WowError)
        }
    }
    Spacer(modifier = Modifier.size(10.dp))
    TextButton(modifier = Modifier.focusGlow(20.dp), onClick = onDone) { Text("Done", color = WowGold, fontWeight = FontWeight.Bold) }
}
