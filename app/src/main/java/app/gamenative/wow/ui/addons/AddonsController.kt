package app.gamenative.wow.ui.addons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.BuildConfig
import app.gamenative.utils.Net
import app.gamenative.wow.addons.AddonCategory
import app.gamenative.wow.addons.AddonDb
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.addons.AddonListing
import app.gamenative.wow.addons.AddonManager
import app.gamenative.wow.addons.addonTarget
import app.gamenative.wow.addons.provider.CurseForgeProvider
import app.gamenative.wow.addons.provider.GitHubProvider
import app.gamenative.wow.addons.provider.WagoProvider
import app.gamenative.wow.addons.provider.WowInterfaceProvider
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.product.WowProduct
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** UI state for the addon browser, bound to one `Interface/AddOns` folder whose addons are for [version]. */
class AddonsController(val addonsDir: File, val version: GameVersion, private val scope: CoroutineScope) {
    /** The addons of one of the app's own installs. */
    constructor(gameRoot: File, product: WowProduct, scope: CoroutineScope) :
        this(File(gameRoot, "${product.flavorDir}/Interface/AddOns"), GameVersion.of(product), scope)

    enum class Mode { INSTALLED, BROWSE }

    private val target = version.addonTarget()
    private val manager = AddonManager(
        addonsDir = addonsDir,
        db = AddonDb.forAddonsDir(addonsDir),
        providers = listOf(
            CurseForgeProvider(
                BuildConfig.CURSEFORGE_API_KEY,
                apiBase = BuildConfig.CURSEFORGE_API_BASE,
                proxy = BuildConfig.CURSEFORGE_API_KEY.isBlank(),
                target = target,
                proxyToken = CurseForgeProvider.unscramble(BuildConfig.CURSEFORGE_PROXY_TOKEN_A, BuildConfig.CURSEFORGE_PROXY_TOKEN_B),
            ),
            WagoProvider(BuildConfig.WAGO_API_KEY, target = target),
            WowInterfaceProvider(target = target),
            GitHubProvider(target = target),
        ),
        http = Net.http,
        target = target,
    )

    var mode by mutableStateOf(Mode.INSTALLED)
        private set
    var query by mutableStateOf("")
        private set
    var includeClassic by mutableStateOf(true)
        private set
    var source by mutableStateOf<String?>(null)
        private set
    var category by mutableStateOf<AddonCategory?>(null)
        private set

    /** Long descriptions by addon key, loaded when an addon is selected. */
    var descriptions by mutableStateOf<Map<String, String?>>(emptyMap())
        private set
    private var descriptionJob: Job? = null
    var listings by mutableStateOf<List<AddonListing>>(emptyList())
        private set
    var installed by mutableStateOf<List<AddonListing>?>(null)
        private set
    var selected by mutableStateOf<AddonListing?>(null)
    var loading by mutableStateOf(false)
        private set
    var working by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    val updates get() = installed?.count { it.updateAvailable }
    val sources = listOf("curseforge", "wago", "wowinterface", "github")

    private var loadJob: Job? = null

    fun refreshInstalled() {
        scope.launch {
            installed = runCatching { withContext(Dispatchers.IO) { manager.installed() } }
                .onFailure { Timber.w(it, "Listing installed addons failed") }
                .getOrDefault(emptyList())
            if (mode == Mode.INSTALLED) show(installed.orEmpty())
        }
    }

    fun switchMode(newMode: Mode) {
        mode = newMode
        selected = null
        if (newMode == Mode.INSTALLED) show(installed.orEmpty()) else load()
    }

    fun search(text: String) {
        query = text
        if (mode == Mode.INSTALLED) show(installed.orEmpty()) else load(debounce = true)
    }

    fun toggleClassic() {
        includeClassic = !includeClassic
        if (mode == Mode.INSTALLED) show(installed.orEmpty()) else load()
    }

    fun selectCategory(value: AddonCategory?) {
        category = value
        if (mode == Mode.INSTALLED) show(installed.orEmpty()) else load()
    }

    fun keyOf(listing: AddonListing) = "${listing.remote.provider}:${listing.remote.projectId}"

    /** Fetches the long description for [listing] once; shows the summary until it arrives. */
    fun loadDescription(listing: AddonListing) {
        val key = keyOf(listing)
        if (key in descriptions) return
        descriptionJob?.cancel()
        descriptionJob = scope.launch {
            delay(250)
            val text = runCatching { withContext(Dispatchers.IO) { manager.description(listing) } }.getOrNull()
            descriptions = descriptions + (key to text)
        }
    }

    fun cycleSource() {
        source = (listOf<String?>(null) + sources).let { all -> all[(all.indexOf(source) + 1) % all.size] }
        if (mode == Mode.INSTALLED) show(installed.orEmpty()) else load()
    }

    private fun load(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (debounce) delay(400)
            loading = true
            try {
                val found = withContext(Dispatchers.IO) {
                    if (query.isBlank()) manager.popular(includeClassic, category) else manager.search(query, includeClassic, category)
                }
                show(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Couldn't reach the addon sites. Check your connection."
                Timber.w(e, "Addon search failed")
            } finally {
                loading = false
            }
        }
    }

    private fun show(items: List<AddonListing>) {
        val q = query.trim().lowercase()
        listings = items.filter { listing ->
            (includeClassic || listing.bestFlavor != AddonFlavor.FALLBACK) &&
                (source == null || source in listing.sources) &&
                (mode == Mode.BROWSE || category == null || category in listing.remote.categories) &&
                (mode == Mode.BROWSE || q.isEmpty() || q in listing.remote.name.lowercase())
        }
        selected = selected?.let { sel -> listings.firstOrNull { it.remote.projectId == sel.remote.projectId } } ?: listings.firstOrNull()
    }

    fun install(listing: AddonListing) = act("Installing ${listing.remote.name}") { markInstalled(listing, manager.install(listing.remote)) }

    fun update(listing: AddonListing) = act("Updating ${listing.remote.name}") {
        val record = listing.installed ?: return@act
        val updated = if (record.flavor == AddonFlavor.FALLBACK && listing.bestFlavor == AddonFlavor.MAIN) manager.install(listing.remote) else manager.update(record)
        markInstalled(listing, updated)
    }

    /** Shows the new state right away; the full refresh after the action fills in the rest. */
    private suspend fun markInstalled(listing: AddonListing, record: app.gamenative.wow.addons.InstalledAddon) = withContext(Dispatchers.Main) {
        val key = keyOf(listing)
        val patch = { l: AddonListing -> if (keyOf(l) == key) l.copy(installed = record, updateAvailable = false) else l }
        listings = listings.map(patch)
        selected = selected?.let(patch)
    }

    fun remove(listing: AddonListing) = act("Removing ${listing.remote.name}") { listing.installed?.let { manager.remove(it) } }

    fun updateAll() = act("Updating all addons") { manager.updateAll() }

    fun installFromUrl(url: String) = act("Installing from link") { manager.installFromUrl(url.trim()) }

    private fun act(label: String, block: suspend () -> Unit) {
        if (working != null) return
        working = label
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                message = "$label: done."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, label)
                message = "$label failed: ${e.message ?: "unknown error"}"
            } finally {
                working = null
                installed = runCatching { withContext(Dispatchers.IO) { manager.installed() } }.getOrDefault(installed.orEmpty())
                if (mode == Mode.INSTALLED) show(installed.orEmpty()) else show(listings.map { l -> refreshed(l) })
            }
        }
    }

    private fun refreshed(listing: AddonListing): AddonListing {
        val record = installed?.firstOrNull { it.remote.provider == listing.remote.provider && it.remote.projectId == listing.remote.projectId }
        return listing.copy(installed = record?.installed, updateAvailable = record?.updateAvailable ?: false)
    }
}
