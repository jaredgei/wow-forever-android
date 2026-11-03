package app.gamenative.wow.ui.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import app.gamenative.wow.addons.AddonCategory
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.addons.AddonListing
import app.gamenative.wow.ui.FwPage
import app.gamenative.wow.ui.LauncherController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import app.gamenative.wow.ui.components.ArtCache
import app.gamenative.wow.ui.components.ArtImage
import app.gamenative.wow.ui.components.GameIcon
import app.gamenative.wow.ui.components.icon
import app.gamenative.wow.ui.components.Links
import app.gamenative.wow.ui.components.ForeverArt
import app.gamenative.wow.ui.components.FwButton
import app.gamenative.wow.ui.components.FwChip
import app.gamenative.wow.ui.components.FwButtonKind
import app.gamenative.wow.ui.components.FwDialog
import app.gamenative.wow.ui.components.FwRow
import app.gamenative.wow.ui.theme.Fw
import java.io.File

@Composable
fun AddonsRoute(launcher: LauncherController, addons: AddonsController?, onBack: () -> Unit) {
    if (addons == null) {
        FwPage("Addons", onBack) {
            Text("Install or find ${launcher.version.displayName} first. Addons go into the game's folder.", style = Fw.Text)
        }
        return
    }
    AddonsScreen(addons, onBack)
}

/**
 * The addon browser. With [onSettings], it's the app's main screen in addon manager mode: a Settings
 * button takes the place of Back.
 */
@Composable
fun AddonsScreen(c: AddonsController, onBack: () -> Unit, onSettings: (() -> Unit)? = null) {
    var showUrlDialog by remember { mutableStateOf(false) }
    val wideBar = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    LaunchedEffect(c) { if (c.installed == null) c.refreshInstalled() }

    Column(Modifier.fillMaxSize().background(Fw.Ground)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onSettings != null) FwButton("Settings", onSettings, kind = FwButtonKind.Ghost) else FwButton("Back", onBack, kind = FwButtonKind.Ghost)
            FwChip("Installed ${c.installed?.size ?: "…"}", c.mode == AddonsController.Mode.INSTALLED, { c.switchMode(AddonsController.Mode.INSTALLED) })
            FwChip("Browse", c.mode == AddonsController.Mode.BROWSE, { c.switchMode(AddonsController.Mode.BROWSE) })
            if (wideBar) SearchField(c.query, c::search, searchHint(c.version), Modifier.weight(1f)) else Box(Modifier.weight(1f))
            c.updates?.takeIf { it > 0 }?.let { FwButton("Update all ($it)", c::updateAll, enabled = c.working == null) }
            if (wideBar) FwButton("From link", { showUrlDialog = true }, kind = FwButtonKind.Ghost)
        }
        if (!wideBar) SearchField(c.query, c::search, searchHint(c.version), Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp))
        // One scrolling row of filters: game version, source, then categories.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            // Only WoW Forever has a fallback version (Classic Era) to include or leave out.
            if (c.version == GameVersion.FOREVER) item { FwChip(if (c.includeClassic) "Forever + Classic" else "Forever only", false, c::toggleClassic) }
            item { FwChip(c.source?.let(::sourceName) ?: "All sources", false, c::cycleSource) }
            item { Box(Modifier.width(1.dp).height(42.dp).background(Fw.Rule)) }
            item { FwChip("All categories", c.category == null, { c.selectCategory(null) }) }
            items(AddonCategory.entries) { cat -> FwChip(cat.label, c.category == cat, { c.selectCategory(cat) }) }
            if (!wideBar) item { FwButton("From link", { showUrlDialog = true }, kind = FwButtonKind.Ghost) }
        }
        (c.working ?: c.message)?.let {
            Text(it, style = Fw.Secondary.copy(color = if (c.working != null) Fw.Brass else Fw.Ash), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            val wide = maxWidth >= 600.dp
            var detailOpen by remember { mutableStateOf(false) }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    AddonList(c, Modifier.weight(1.25f).fillMaxHeight(), onOpen = {})
                    Box(Modifier.width(14.dp))
                    c.selected?.let { Detail(c, it, Modifier.weight(1f).fillMaxHeight()) } ?: Box(Modifier.weight(1f))
                }
            } else {
                AddonList(c, Modifier.fillMaxSize(), onOpen = { detailOpen = true })
                val sel = c.selected
                if (detailOpen && sel != null) {
                    Column(Modifier.fillMaxSize().background(Fw.Ground)) {
                        FwButton("Back to list", { detailOpen = false }, kind = FwButtonKind.Ghost, modifier = Modifier.padding(16.dp))
                        Detail(c, sel, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        FwDialog(
            title = "Install from a link",
            message = "Paste a GitHub repository link, or a direct link to an addon .zip.",
            confirm = "Install",
            onConfirm = {
                showUrlDialog = false
                if (url.isNotBlank()) c.installFromUrl(url)
            },
            onDismiss = { showUrlDialog = false },
        ) {
            OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Link") }, textStyle = Fw.Text)
        }
    }
}

@Composable
private fun AddonList(c: AddonsController, modifier: Modifier, onOpen: () -> Unit) {
    Box(modifier) {
        when {
            c.loading -> Text("Searching…", style = Fw.Secondary, modifier = Modifier.padding(20.dp))
            c.listings.isEmpty() -> Text(
                when (c.mode) {
                    AddonsController.Mode.INSTALLED -> if (c.installed == null) "Reading your AddOns folder…" else "No addons installed yet. Switch to Browse to find some."
                    AddonsController.Mode.BROWSE ->
                        if (c.version == GameVersion.FOREVER) "Nothing found. Try another name, or include Classic addons." else "Nothing found. Try another name."
                },
                style = Fw.Secondary,
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(c.listings, key = { "${it.remote.provider}:${it.remote.projectId}" }) { listing ->
                    FwRow(
                        selected = c.selected == listing,
                        onClick = {
                            c.selected = listing
                            onOpen()
                        },
                        onFocus = { if (c.selected != listing) c.selected = listing },
                    ) {
                        AddonIcon(listing, 44, c.version)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(listing.remote.name, style = Fw.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                FlavorMark(listing.bestFlavor, c.version, size = 20)
                            }
                            Text(
                                listOfNotNull(
                                    listing.remote.categories.firstOrNull()?.label,
                                    listing.sources.joinToString(" · ", transform = ::sourceName).takeIf { it.isNotBlank() },
                                    listing.remote.downloads.takeIf { it > 0 }?.let { "${compact(it)} downloads" },
                                ).joinToString(" · "),
                                style = Fw.Data,
                                maxLines = 1,
                            )
                        }
                        val (state, color) = when {
                            listing.updateAvailable -> "Update" to Fw.Warn
                            listing.installed != null -> "Installed" to Fw.Ok
                            else -> "Get" to Fw.Ash
                        }
                        Text(state, style = Fw.Data.copy(color = color))
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(c: AddonsController, listing: AddonListing, modifier: Modifier) {
    val remote = listing.remote
    val file = remote.latest[listing.bestFlavor] ?: remote.latest.values.firstOrNull()
    LaunchedEffect(c.keyOf(listing)) { c.loadDescription(listing) }
    val description = c.descriptions[c.keyOf(listing)]
    Column(modifier.clip(Fw.TileShape).background(Fw.Stone).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(Fw.Stone2)) {
            val banner = remote.screenshots.firstOrNull()
            if (banner != null) {
                ArtImage(banner, Modifier.fillMaxSize(), fitIfNarrowerThan = 640, keepOnDisk = false)
            } else {
                ArtImage(ForeverArt.INTERFACE, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AddonIcon(listing, 56, c.version)
                Column(Modifier.weight(1f)) {
                    Text(remote.name, style = Fw.Title)
                    if (remote.author.isNotBlank()) Text("by ${remote.author}", style = Fw.Secondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlavorMark(listing.bestFlavor, c.version, size = 22)
                Text(flavorLabel(listing.bestFlavor, c.version), style = Fw.Secondary.copy(color = if (listing.bestFlavor == AddonFlavor.MAIN) Fw.Gem else Fw.Ash))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val idle = c.working == null
                when {
                    listing.installed == null -> FwButton("Install", { c.install(listing) }, enabled = idle && file?.installable == true)
                    listing.updateAvailable -> FwButton(
                        if (listing.installed.flavor == AddonFlavor.FALLBACK && listing.bestFlavor == AddonFlavor.MAIN) "Switch to Forever version" else "Update",
                        { c.update(listing) },
                        enabled = idle,
                    )
                    else -> FwButton("Installed", {}, enabled = false)
                }
                if (listing.installed != null) FwButton("Remove", { c.remove(listing) }, kind = FwButtonKind.Danger, enabled = idle)
            }
            if (file != null && !file.installable) {
                Text("The author doesn't allow downloads through other apps. Open its page to get it there.", style = Fw.Secondary.copy(color = Fw.Warn))
            }
            if (listing.pages.isNotEmpty()) {
                val context = LocalContext.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listing.pages.entries.take(3).forEach { (provider, url) ->
                        FwButton(
                            "Open on ${sourceName(provider)}",
                            { Links.open(context, url) },
                            kind = FwButtonKind.Ghost,
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                        )
                    }
                }
            }
            if (remote.categories.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    remote.categories.take(3).forEach { cat ->
                        Text(cat.label, style = Fw.Label.copy(fontSize = 12.sp, color = Fw.Ash), modifier = Modifier.background(Fw.Stone2, Fw.Pill).padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
            }
            Text(
                when {
                    description != null -> description
                    c.keyOf(listing) in c.descriptions -> remote.summary.ifBlank { "No description." }
                    else -> remote.summary.ifBlank { "Loading description…" }
                },
                style = Fw.Text.copy(color = Fw.Bone),
                maxLines = 40,
                overflow = TextOverflow.Ellipsis,
            )
            if (remote.screenshots.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(remote.screenshots.drop(1).take(8)) { shot ->
                        Box(Modifier.size(width = 168.dp, height = 96.dp).clip(Fw.Shape).background(Fw.Stone2)) {
                            ArtImage(shot, Modifier.fillMaxSize(), fitIfNarrowerThan = 320, keepOnDisk = false)
                        }
                    }
                }
            }
            Facts(
                listOfNotNull(
                    file?.let { "Latest" to it.version },
                    listing.installed?.version?.let { "Installed" to it },
                    "Source" to listing.sources.joinToString(", ", transform = ::sourceName).ifBlank { "This device" },
                    file?.dependencies?.takeIf { it.isNotEmpty() }?.let { "Also installs" to "${it.size} required libraries" },
                    listing.installed?.folders?.let { "Folders" to it.joinToString(", ") },
                ),
            )
        }
    }
}

/** The addon's own icon from its source; falls back to the Forever/Classic mark. */
@Composable
private fun AddonIcon(listing: AddonListing, size: Int, version: GameVersion) {
    val url = listing.remote.iconUrl
    Box(Modifier.size(size.dp).clip(Fw.Shape).background(Fw.Stone2), contentAlignment = Alignment.Center) {
        if (url == null) FlavorMark(listing.bestFlavor, version, size = (size * 0.6f).toInt()) else ArtImage(url, Modifier.fillMaxSize(), keepOnDisk = false)
    }
}

@Composable
private fun Facts(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, style = Fw.Secondary, modifier = Modifier.width(110.dp))
                Text(v, style = Fw.Data.copy(color = Fw.Bone))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
    Box(modifier.widthIn(min = 160.dp).background(Fw.Stone, Fw.Pill).padding(horizontal = 18.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, style = Fw.Text.copy(color = Fw.Ash), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(value, onChange, singleLine = true, textStyle = Fw.Text, cursorBrush = SolidColor(Fw.Brass), modifier = Modifier.fillMaxWidth())
    }
}

private fun searchHint(version: GameVersion) = if (version == GameVersion.FOREVER) "Search Forever addons…" else "Search ${version.displayName} addons…"

/**
 * Game version marker: Blizzard's round icon for the version an addon is made for ([version]'s own, or
 * Classic for Forever's fallback). Until it loads (or offline) a simple stand-in shows: a teal square for
 * the selected version, an outlined circle otherwise.
 */
@Composable
fun FlavorMark(flavor: AddonFlavor, version: GameVersion = GameVersion.FOREVER, size: Int = 28) {
    val icon = when (flavor) {
        AddonFlavor.MAIN -> version.icon
        AddonFlavor.FALLBACK -> GameIcon.CLASSIC
        AddonFlavor.OTHER -> null
    }
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(null, icon, px) { value = icon?.let { ArtCache.loadSvg(context, it, px) } }
    val base = Modifier.size(size.dp)
    image?.let {
        Image(it, contentDescription = flavorLabel(flavor, version), modifier = base)
        return
    }
    when (flavor) {
        AddonFlavor.MAIN -> Box(base.background(Fw.Gem, Fw.Shape))
        else -> Box(base.border(2.dp, Fw.Ash, CircleShape))
    }
}

fun flavorLabel(flavor: AddonFlavor, version: GameVersion = GameVersion.FOREVER) = when {
    version == GameVersion.FOREVER -> when (flavor) {
        AddonFlavor.MAIN -> "Made for WoW Forever"
        AddonFlavor.FALLBACK -> "Made for Classic. It may not work in Forever."
        AddonFlavor.OTHER -> "No game version listed. It may not work in Forever."
    }
    flavor == AddonFlavor.OTHER -> "No game version listed. It may not work in ${version.displayName}."
    else -> "Made for ${version.displayName}"
}

private fun sourceName(id: String) = when (id) {
    "curseforge" -> "CurseForge"
    "wago" -> "Wago"
    "wowinterface" -> "WoWInterface"
    "github" -> "GitHub"
    "local" -> "This device"
    else -> id
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fK".format(n / 1_000.0)
    else -> n.toString()
}

internal fun addonsControllerFor(root: String, product: app.gamenative.wow.product.WowProduct?, scope: kotlinx.coroutines.CoroutineScope): AddonsController? =
    product?.let { AddonsController(File(root), it, scope) }
