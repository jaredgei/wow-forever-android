package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.addons.AddonDetails
import app.gamenative.addons.SiteLink
import app.gamenative.addons.Markup
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** The button shown in the detail header: install, update or a disabled "Installed". */
data class DetailAction(val label: String, val enabled: Boolean, val busy: Boolean, val onClick: () -> Unit)

internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun AddonDetailDialog(
    title: String,
    subtitle: String,
    site: SiteLink,
    load: suspend () -> AddonDetails,
    action: DetailAction?,
    onDismiss: () -> Unit,
    openedWithKeyboard: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(WowBackgroundGradient)).windowInsetsPadding(WindowInsets.systemBars)) {
            GoldFocusTheme { DetailContent(title, subtitle, site, load, action, onDismiss, openedWithKeyboard) }
        }
    }
}

private enum class DetailTab(val label: String) { DESCRIPTION("Description"), CHANGELOG("Changelog"), PREVIEWS("Previews") }

@Composable
private fun DetailContent(title: String, subtitle: String, site: SiteLink, load: suspend () -> AddonDetails, action: DetailAction?, onDismiss: () -> Unit, openedWithKeyboard: Boolean) {
    val context = LocalContext.current
    var details by remember { mutableStateOf<AddonDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title) {
        try {
            details = load()
        } catch (e: Exception) {
            error = "Couldn't load details: ${e.message}"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = WowGold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = WowSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action?.let {
                if (it.busy) {
                    CircularProgressIndicator(color = WowGold, modifier = Modifier.padding(end = 12.dp).size(24.dp))
                } else {
                    Button(
                        modifier = Modifier.focusGlow(10.dp),
                        onClick = it.onClick,
                        enabled = it.enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(it.label, fontWeight = FontWeight.Bold) }
                }
            }
            // Always present, even while loading or if loading fails: the page the source itself names wins over our guess.
            val link = details?.homepage?.let { SiteLink(it, isSearch = false) } ?: site
            OutlinedButton(
                onClick = { openUrl(context, link.url) },
                border = BorderStroke(1.dp, WowGold),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.focusGlow(10.dp).padding(horizontal = 8.dp),
            ) { Text(link.label, color = WowGold, fontWeight = FontWeight.Bold) }
            IconButton(modifier = Modifier.focusGlow(24.dp), onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = WowMuted) }
        }
        Spacer(modifier = Modifier.size(8.dp))
        val d = details
        when {
            error != null -> Text(error!!, color = WowError, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            d == null -> CircularProgressIndicator(color = WowGold, modifier = Modifier.padding(top = 16.dp).size(28.dp))
            else -> DetailBody(d, openedWithKeyboard)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ColumnScope.DetailBody(d: AddonDetails, openedWithKeyboard: Boolean) {
    val tabs = remember(d) {
        buildList {
            if (d.description != null) add(DetailTab.DESCRIPTION)
            if (d.changelog != null) add(DetailTab.CHANGELOG)
            if (d.images.isNotEmpty()) add(DetailTab.PREVIEWS)
        }
    }
    if (tabs.isEmpty()) {
        Text("This addon's page has no description, changelog or previews.", color = WowMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        return
    }
    var selected by remember(d) { mutableStateOf(0) }
    val tabFocus = remember(tabs) { List(tabs.size) { FocusRequester() } }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(tabs) {
        // Opened with a D-pad or gamepad: start on the first tab. The dialog is its own window, so tell it keys are in use.
        if (openedWithKeyboard) {
            delay(150)
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { tabFocus[0].requestFocus() }
        }
    }
    TabRow(
        selectedTabIndex = selected,
        containerColor = Color.Transparent,
        contentColor = WowGold,
        indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selected]), color = WowGold) },
    ) {
        tabs.forEachIndexed { i, tab -> Tab(modifier = Modifier.focusRequester(tabFocus[i]).focusGlow(8.dp), selected = selected == i, onClick = { selected = i }, text = { Text(tab.label) }) }
    }
    Spacer(modifier = Modifier.size(10.dp))
    // Up from the content goes to the tab you're on, not whichever tab is nearest.
    val upTo = tabFocus[selected]
    when (tabs[selected]) {
        DetailTab.DESCRIPTION -> RichText(d.description.orEmpty(), d.markdown, upTo)
        DetailTab.CHANGELOG -> RichText(d.changelog.orEmpty(), markdown = true, upTo)
        DetailTab.PREVIEWS -> Previews(d.images, upTo)
    }
}

private const val SCROLL_STEP = 360f

/** Long text a D-pad can read: Up/Down scroll it, and at either end hand focus back so nobody gets stuck. */
@Composable
private fun RichText(raw: String, markdown: Boolean, upTo: FocusRequester) {
    val text = remember(raw, markdown) { Markup.render(raw, markdown) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize()
            .focusGlow(8.dp)
            .focusProperties { up = upTo }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> (scroll.value < scroll.maxValue).also { if (it) scope.launch { scroll.animateScrollBy(SCROLL_STEP) } }
                    Key.DirectionUp -> (scroll.value > 0).also { if (it) scope.launch { scroll.animateScrollBy(-SCROLL_STEP) } }
                    else -> false
                }
            }
            .focusable()
            .verticalScroll(scroll),
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFE2E8F0), modifier = Modifier.padding(8.dp))
        Spacer(modifier = Modifier.size(24.dp))
    }
}

/**
 * The screenshots. Each is its own focus target, so D-pad focus moves from one to the next (the row follows) and the
 * focused one is the one that opens full screen on center/A, or on a tap. At the ends focus moves on.
 */
@Composable
private fun Previews(allImages: List<String>, upTo: FocusRequester) {
    // A preview that can't be loaded (dead link, unsupported format) just drops out of the row.
    val failed = remember(allImages) { mutableStateListOf<String>() }
    val images = allImages.filterNot { it in failed }
    if (images.isEmpty()) {
        Text("No previews could be loaded.", color = WowMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        return
    }
    var viewing by remember { mutableStateOf<Int?>(null) }
    viewing?.let { index -> ImageViewer(images, index) { viewing = null } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(images, key = { _, url -> url }) { index, url ->
            RemoteImage(
                url,
                modifier = Modifier.height(300.dp)
                    .focusProperties { up = upTo }
                    .focusGlow(10.dp)
                    .clickable { viewing = index }
                    .background(Color(0x14FFFFFF), RoundedCornerShape(10.dp)),
                onFailed = { failed.add(url) },
            )
        }
    }
}

private const val MAX_IMAGE_EDGE = 1280
private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Fetches and downsamples an image; no image library is needed for a handful of previews. */
internal suspend fun fetchBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    imageCache[url]?.let { return@withContext it }
    runCatching {
        val bytes = Net.http.newCall(Request.Builder().url(url).header("User-Agent", "WoW-Forever-Android").build()).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            r.body?.bytes()
        } ?: return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()?.also { imageCache.put(url, it) }
}

@Composable
internal fun RemoteImage(url: String, modifier: Modifier = Modifier, onFailed: () -> Unit = {}) {
    var failed by remember(url) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, url) {
        value = fetchBitmap(url)?.asImageBitmap()
        failed = value == null
        if (failed) onFailed()
    }
    val loaded = image
    when {
        loaded != null -> Image(loaded, contentDescription = null, contentScale = ContentScale.FillHeight, modifier = modifier)
        failed -> Box(modifier = modifier.width(200.dp), contentAlignment = Alignment.Center) { Text("Couldn't load image", fontSize = 12.sp, color = WowSubtle) }
        else -> Box(modifier = modifier.width(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WowGold, modifier = Modifier.size(24.dp)) }
    }
}
