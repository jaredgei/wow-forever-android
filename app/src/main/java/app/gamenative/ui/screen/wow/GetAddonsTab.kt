package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.addons.AddonInstaller
import app.gamenative.addons.AddonManager
import app.gamenative.addons.AddonSites
import app.gamenative.addons.AddonSort
import app.gamenative.addons.AddonSources
import app.gamenative.addons.CatalogAddon
import app.gamenative.addons.GitHubProduct
import app.gamenative.addons.OverlapDeclined
import app.gamenative.addons.Provider
import app.gamenative.addons.TukuiProvider
import app.gamenative.addons.WowInterfaceProvider
import app.gamenative.addons.formatBytes
import app.gamenative.addons.selectionWarnings
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import java.io.File
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "wow_forever"
private const val KEY_MIDNIGHT_ONLY = "addon_midnight_only"
private const val KEY_SORT = "addon_sort"
private const val MAX_RESULTS = 100

@Volatile private var memoryCatalog: List<CatalogAddon>? = null

/** The combined WoWInterface and Tukui catalog. Either source may be down; both failing is an error. */
internal suspend fun loadCatalog(context: Context, force: Boolean = false): List<CatalogAddon> = withContext(Dispatchers.IO) {
    memoryCatalog?.takeIf { !force }?.let { return@withContext it }
    val wowi = runCatching { WowInterfaceProvider.catalog(context.cacheDir) }
    val tukui = runCatching { TukuiProvider.catalog() }
    if (wowi.isFailure && tukui.isFailure) throw wowi.exceptionOrNull()!!
    (tukui.getOrDefault(emptyList()) + wowi.getOrDefault(emptyList())).also { memoryCatalog = it }
}

@Composable
internal fun ColumnScope.GetAddonsTab(gameRoot: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var catalog by remember { mutableStateOf<List<CatalogAddon>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf<Provider?>(null) }
    var sort by remember { mutableStateOf(AddonSort.fromKey(prefs.getString(KEY_SORT, null))) }
    var midnightOnly by remember { mutableStateOf(prefs.getBoolean(KEY_MIDNIGHT_ONLY, true)) }
    var installedFolders by remember { mutableStateOf(emptySet<String>()) }
    var records by remember { mutableStateOf(AddonSources.load(gameRoot)) }
    val busy = remember { mutableStateListOf<String>() }
    var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<CatalogAddon?>(null) }
    val asker = rememberOverlapAsker()
    OverlapDialog(asker)

    fun refreshInstalled() {
        scope.launch {
            withContext(Dispatchers.IO) {
                installedFolders = AddonManager.listInstalled(gameRoot).map { it.folder }.toSet()
                records = AddonSources.load(gameRoot)
            }
        }
    }
    fun load(force: Boolean) {
        scope.launch {
            catalog = null
            error = null
            try {
                catalog = loadCatalog(context, force)
            } catch (e: Exception) {
                error = "Couldn't reach the addon sites: ${e.message}"
            }
        }
    }
    LaunchedEffect(gameRoot) { load(false); refreshInstalled() }

    /** (installed, hasUpdate) for an addon, from what's on disk and what we recorded installing. */
    fun stateOf(addon: CatalogAddon): Pair<Boolean, Boolean> {
        val record = records.firstOrNull { it.provider == addon.provider && it.id == addon.id }
        val installed = if (record != null) record.folders.any { it in installedFolders } else addon.folders.isNotEmpty() && addon.folders.all { it in installedFolders }
        return installed to (record != null && installed && addon.version.isNotEmpty() && record.version != addon.version)
    }
    fun install(addon: CatalogAddon) {
        busy.add(addon.key)
        message = null
        scope.launch {
            message = try {
                val folders = withContext(Dispatchers.IO) { AddonInstaller.installCatalog(gameRoot, context.cacheDir, addon, asker.gate) }
                "Installed ${addon.name} (${folders.joinToString(", ")})"
            } catch (e: OverlapDeclined) {
                "Kept the installed copy; ${addon.name} was not installed"
            } catch (e: Exception) {
                "Couldn't install ${addon.name}: ${e.message}"
            }
            busy.remove(addon.key)
            refreshInstalled()
        }
    }

    // The controls are the first item of one list, so they scroll away while browsing and the results get the whole screen.
    // Keeping them in the same list for every state also means the search box isn't rebuilt (and its keyboard dismissed)
    // when a query happens to match nothing.
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search addons") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                colors = fieldColors(),
                modifier = Modifier.dpadTextField().upToTab().focusGlow(8.dp).weight(1f),
            )
            TextButton(modifier = Modifier.upToTab().focusGlow(20.dp), onClick = { load(true) }) { Text("Refresh", color = WowMuted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            SourceChip("All", provider == null) { provider = null }
            SourceChip("WoWInterface", provider == Provider.WOWINTERFACE) { provider = Provider.WOWINTERFACE }
            SourceChip("Tukui", provider == Provider.TUKUI) { provider = Provider.TUKUI }
            Checkbox(
                modifier = Modifier.focusGlow(8.dp),
                checked = midnightOnly,
                onCheckedChange = { midnightOnly = it; prefs.edit().putBoolean(KEY_MIDNIGHT_ONLY, it).apply() },
                colors = CheckboxDefaults.colors(checkedColor = WowBronze),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Only addons that work with WoW Forever", fontSize = 13.sp, color = Color(0xFFE2E8F0))
                Text(
                    "The sites don't list WoW Forever, so this shows addons marked for Retail 12.x (\"Midnight\"), the version it shares addons with.",
                    fontSize = 11.sp, color = WowSubtle, lineHeight = 14.sp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("Sort", fontSize = 13.sp, color = WowMuted)
            AddonSort.entries.forEach { option ->
                SourceChip(option.label, sort == option) { sort = option; prefs.edit().putString(KEY_SORT, option.key).apply() }
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = WowGold, modifier = Modifier.padding(vertical = 4.dp)) }
    }

    detail?.let { addon -> AddonDetailFor(addon, stateOf(addon), addon.key in busy, onInstall = { install(addon) }, onDismiss = { detail = null }) }

    val all = catalog
    val q = query.trim()
    val shown = remember(all, q, provider, midnightOnly, sort) {
        all?.asSequence()
            ?.filter { provider == null || it.provider == provider }
            ?.filter { !midnightOnly || it.isMidnight }
            ?.filter { q.isEmpty() || it.name.contains(q, true) || it.author.contains(q, true) || it.folders.any { f -> f.contains(q, true) } }
            ?.sortedWith(sort.comparator(q))
            ?.take(MAX_RESULTS)?.toList() ?: emptyList()
    }
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "header") { header() }
        when {
            error != null -> item(key = "error") { Text(error!!, color = WowError, fontSize = 13.sp) }
            all == null -> item(key = "loading") { CircularProgressIndicator(color = WowGold, modifier = Modifier.size(28.dp).padding(top = 8.dp)) }
            shown.isEmpty() -> item(key = "empty") {
                Text(
                    "Nothing matches. Try a different search${if (midnightOnly) ", or untick \"Only addons that work with WoW Forever\" to see everything" else ""}.",
                    fontSize = 13.sp, color = WowMuted, modifier = Modifier.padding(top = 8.dp),
                )
            }
            else -> items(shown, key = { it.key }) { addon ->
                val (isInstalled, hasUpdate) = stateOf(addon)
                AddonRow(addon, busy = addon.key in busy, installed = isInstalled, hasUpdate = hasUpdate, onOpen = { detail = addon }, onInstall = { install(addon) })
            }
        }
    }
}

@Composable
private fun AddonDetailFor(
    addon: CatalogAddon,
    state: Pair<Boolean, Boolean>,
    busy: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (installed, hasUpdate) = state
    AddonDetailDialog(
        title = addon.name,
        subtitle = listOfNotNull(addon.provider.label, addon.author.takeIf { it.isNotEmpty() }, addon.version.takeIf { it.isNotEmpty() }?.let(::displayVersion)).joinToString("  ·  "),
        site = AddonSites.forAddon(addon.name, providerPage = addon.infoUrl),
        load = {
            withContext(Dispatchers.IO) {
                when (addon.provider) {
                    Provider.WOWINTERFACE -> WowInterfaceProvider.details(addon)
                    Provider.TUKUI -> TukuiProvider.details(addon.id)
                    else -> error("No details for ${addon.provider.label}")
                }
            }
        },
        action = DetailAction(if (hasUpdate) "Update" else if (installed) "Installed" else "Install", enabled = !installed || hasUpdate, busy = busy, onClick = onInstall),
        onDismiss = onDismiss,
        openedWithKeyboard = isKeyboardNavigating(),
    )
}

@Composable
private fun AddonRow(addon: CatalogAddon, busy: Boolean, installed: Boolean, hasUpdate: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).focusGlow(12.dp).clickable(onClick = onOpen).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(addon.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                addon.provider.label,
                addon.author.takeIf { it.isNotEmpty() },
                addon.version.takeIf { it.isNotEmpty() }?.let(::displayVersion),
                addon.downloadsMonthly.takeIf { it > 0 }?.let { "${NumberFormat.getIntegerInstance().format(it)}/mo" },
            ).joinToString("  ·  ")
            Text(detail, fontSize = 12.sp, color = WowSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            addon.summary?.let { Text(it, fontSize = 12.sp, color = WowMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(modifier = Modifier.width(8.dp))
        when {
            busy -> CircularProgressIndicator(color = WowGold, modifier = Modifier.padding(end = 12.dp).size(24.dp))
            installed && !hasUpdate -> Text("Installed", fontSize = 13.sp, color = WowSubtle, modifier = Modifier.padding(end = 8.dp))
            else -> Button(
                modifier = Modifier.focusGlow(10.dp),
                onClick = onInstall,
                colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) { Text(if (hasUpdate) "Update" else "Install", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        modifier = Modifier.focusGlow(20.dp),
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WowBronze, selectedLabelColor = Color.White, labelColor = WowMuted),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = WowGold, unfocusedBorderColor = WowSubtle, focusedLabelColor = WowGold, unfocusedLabelColor = WowSubtle,
    focusedTextColor = Color(0xFFE2E8F0), unfocusedTextColor = Color(0xFFE2E8F0), cursorColor = WowGold,
)

@Composable
internal fun ColumnScope.UrlTab(gameRoot: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val asker = rememberOverlapAsker()
    OverlapDialog(asker)
    var choices by remember { mutableStateOf<List<GitHubProduct>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var progress by remember { mutableStateOf<String?>(null) }

    fun installSingle() {
        busy = true
        message = null
        scope.launch {
            try {
                val folders = withContext(Dispatchers.IO) { AddonInstaller.installFromUrl(gameRoot, context.cacheDir, url, asker.gate) }
                failed = false
                message = "Installed ${folders.joinToString(", ")}"
                url = ""
            } catch (e: OverlapDeclined) {
                failed = false
                message = e.message
            } catch (e: Exception) {
                failed = true
                message = "Couldn't install: ${e.message}"
            }
            busy = false
        }
    }

    fun installSelected(products: List<GitHubProduct>) {
        val chosen = products.filter { it.prefix in selected }
        if (chosen.isEmpty()) return
        busy = true
        message = null
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    AddonInstaller.installProducts(
                        gameRoot, context.cacheDir, url, chosen,
                        onProgress = { i, n, p -> progress = "Installing ${i + 1} of $n: ${p.prefix.ifEmpty { p.assetName }}…" },
                        gate = asker.gate,
                    )
                }
                failed = results.any { it.error != null }
                message = results.joinToString("\n") { r ->
                    val label = r.product.prefix.ifEmpty { r.product.assetName }
                    when {
                        r.error != null -> "$label: couldn't install (${r.error})"
                        r.skipped -> "$label: skipped, nothing was replaced"
                        else -> "$label: installed ${r.folders.joinToString(", ")}"
                    }
                }
                if (results.any { it.error == null && !it.skipped }) url = ""
                choices = null
            } catch (e: Exception) {
                failed = true
                message = "Couldn't install: ${e.message}"
            }
            progress = null
            busy = false
        }
    }

    val offered = choices
    if (offered != null) {
        ProductChooser(
            repo = url.trim(), products = offered, selected = selected,
            onToggle = { p -> selected = if (p.prefix in selected) selected - p.prefix else selected + p.prefix },
            gameFree = gameRoot.usableSpace, cacheFree = context.cacheDir.usableSpace,
            busy = busy, progress = progress,
            onInstall = { installSelected(offered) },
            onCancel = { choices = null; message = null },
        )
        message?.let { Text(it, fontSize = 13.sp, color = if (failed) WowError else WowGold, modifier = Modifier.padding(top = 10.dp)) }
        return
    }

    Text(
        "Paste a GitHub repository (its latest release zip is installed, or you pick which to install if it publishes several) or a direct https link to an addon zip.",
        fontSize = 13.sp, color = WowMuted,
    )
    Spacer(modifier = Modifier.size(8.dp))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        singleLine = true,
        label = { Text("github.com/owner/repo  or  https://…/addon.zip") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        colors = fieldColors(),
        modifier = Modifier.dpadTextField().upToTab().focusGlow(8.dp).fillMaxWidth(),
    )
    Spacer(modifier = Modifier.size(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            modifier = Modifier.focusGlow(14.dp),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    // A repo that publishes several products (a monorepo) lets you pick; anything else installs straight away.
                    val products = try {
                        withContext(Dispatchers.IO) { AddonInstaller.githubProducts(url) }
                    } catch (_: Exception) {
                        null // the install below reports the real error
                    }
                    if (products != null && products.size > 1) {
                        selected = products.filter { it.isDefault }.map { it.prefix }.toSet()
                        choices = products
                        busy = false
                    } else {
                        installSingle()
                    }
                }
            },
            enabled = url.isNotBlank() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        ) { Text("INSTALL", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        if (busy) CircularProgressIndicator(color = WowGold, modifier = Modifier.padding(start = 12.dp).size(24.dp))
    }
    message?.let { Text(it, fontSize = 13.sp, color = if (failed) WowError else WowGold, modifier = Modifier.padding(top = 10.dp)) }
}

/**
 * A repo that publishes several products: tick the ones to install. The product that matches the repo's name comes
 * first as recommended, and translations are tucked away. A product that would replace folders that are
 * already installed pauses the batch for the overlap dialog, and keeping the installed copy skips just that product.
 */
@Composable
private fun ColumnScope.ProductChooser(
    repo: String,
    products: List<GitHubProduct>,
    selected: Set<String>,
    onToggle: (GitHubProduct) -> Unit,
    gameFree: Long,
    cacheFree: Long,
    busy: Boolean,
    progress: String?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    var showLanguages by remember { mutableStateOf(false) }
    val translations = products.filter { it.locale != null }
    // Translations are tucked away unless they're all there is.
    val shown = if (showLanguages || translations.size == products.size) products else products - translations.toSet()
    val chosen = products.filter { it.prefix in selected }
    val name = repo.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    val warnings = selectionWarnings(chosen, gameFree, cacheFree)
    val total = chosen.sumOf { it.sizeBytes }
    Text("$name publishes ${shown.size} separate downloads. Tick the ones to install.", fontSize = 13.sp, color = WowMuted)
    Spacer(modifier = Modifier.size(8.dp))
    // The list gets all the room the buttons leave: the size is on the install button and only "no room" warnings stay pinned.
    LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(warnings.filter { !it.serious }, key = { it.text }) { Text(it.text, fontSize = 12.sp, color = WowGold) }
        items(shown, key = { it.prefix }) { product ->
            val checked = product.prefix in selected
            Row(
                modifier = Modifier.fillMaxWidth().focusGlow(14.dp)
                    .background(if (checked) Color(0xFF33405A) else Color(0xFF252B3B), RoundedCornerShape(14.dp))
                    .clickable(enabled = !busy) { onToggle(product) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = WowBronze))
                Column(modifier = Modifier.padding(start = 12.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(product.prefix.ifEmpty { product.assetName }, fontWeight = FontWeight.Bold, color = Color.White)
                        val notes = listOfNotNull(if (product.isDefault) "recommended" else null, if (product.isLarge) "large download" else null)
                        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), fontSize = 12.sp, color = WowGold, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
                    }
                    Text(
                        listOf(product.tag.substringAfterLast('/'), product.assetName, product.sizeLabel).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        fontSize = 12.sp, color = Color(0xFFE2E8F0), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    for (warning in warnings.filter { it.serious }) {
        Text(warning.text, fontSize = 12.sp, color = WowError, modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(modifier = Modifier.size(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            modifier = Modifier.focusGlow(14.dp),
            onClick = onInstall,
            enabled = chosen.isNotEmpty() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        ) {
            val label = (if (chosen.size > 1) "INSTALL ${chosen.size} SELECTED" else "INSTALL") + if (total > 0) " · ${formatBytes(total)}" else ""
            Text(label, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        TextButton(modifier = Modifier.focusGlow(14.dp), onClick = onCancel, enabled = !busy) { Text("CANCEL", color = WowMuted) }
        if (translations.isNotEmpty() && translations.size < products.size) {
            TextButton(modifier = Modifier.focusGlow(14.dp), onClick = { showLanguages = !showLanguages }, enabled = !busy) {
                Text(if (showLanguages) "HIDE OTHER LANGUAGES" else "SHOW ${translations.size} OTHER LANGUAGES", color = WowGold)
            }
        }
        if (busy) CircularProgressIndicator(color = WowGold, modifier = Modifier.padding(start = 12.dp).size(24.dp))
    }
    progress?.let { Text(it, fontSize = 13.sp, color = WowGold, modifier = Modifier.padding(top = 6.dp)) }
}

/** "v" is only added to bare numbers; some sources already include it (e.g. "v4.3.13-release"). */
internal fun displayVersion(version: String) = if (version.firstOrNull()?.isDigit() == true) "v$version" else version

/** TOCs often list every game flavor's interface number; show the first few and count the rest. */
internal fun interfaceSummary(interfaceField: String): String {
    val numbers = interfaceField.split(',', ' ').filter { it.isNotBlank() }
    return if (numbers.size <= 3) numbers.joinToString(", ") else numbers.take(2).joinToString(", ") + " +${numbers.size - 2} more"
}
