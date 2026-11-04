package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.addons.IncomingAddon
import app.gamenative.addons.OverlapGate
import app.gamenative.addons.OverlapGroup
import app.gamenative.addons.OverlapPrompt
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * Lets install code running on an IO thread stop and ask the user. [gate] blocks its caller until [OverlapDialog] is
 * answered, so pass it only to work launched on Dispatchers.IO, never the main thread.
 */
internal class OverlapAsker {
    var pending by mutableStateOf<Pair<OverlapPrompt, CompletableDeferred<Boolean>>?>(null)

    val gate = OverlapGate { prompt ->
        runBlocking {
            val answer = CompletableDeferred<Boolean>()
            pending = prompt to answer
            try { answer.await() } finally { pending = null }
        }
    }
}

@Composable
internal fun rememberOverlapAsker() = remember { OverlapAsker() }

/** "v" is added to bare numbers, as elsewhere; a missing version is said so rather than left blank. */
private fun versionText(version: String?) = version?.let(::displayVersion) ?: "version unknown"

private const val MAX_FOLDERS = 6

@Composable
internal fun OverlapDialog(asker: OverlapAsker) {
    val (prompt, answer) = asker.pending ?: return
    // Start on "Keep installed": it destroys nothing, and the first key press isn't spent finding it.
    val keepFocus = remember(prompt) { FocusRequester() }
    val keyboardNav = isKeyboardNavigating()
    val incoming = prompt.incoming
    AlertDialog(
        onDismissRequest = { answer.complete(false) },
        title = { Text("Overwrite installed ${incoming.name}?") },
        text = {
            // Scrolls: at the largest text scale the two sides don't both fit, and the one that is cut off is the choice.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installing it will overwrite the folders below. Choose which copy to keep.", fontSize = 13.sp, color = WowMuted)
                prompt.groups.forEach { InstalledSide(it) }
                IncomingSide(incoming)
                AutoFocus(keepFocus, keyboardNav)
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.focusGlow(20.dp), onClick = { answer.complete(true) }) {
                Text("Use new copy", color = WowGold)
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(keepFocus).focusGlow(20.dp), onClick = { answer.complete(false) }) {
                Text("Keep installed", color = WowMuted)
            }
        },
    )
}

@Composable
private fun InstalledSide(group: OverlapGroup) {
    Column {
        Text("Installed: ${group.sourceLabel}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
        Text("${group.name}  ·  ${versionText(group.version)}", fontSize = 12.sp, color = WowSubtle)
        val shown = group.folders.take(MAX_FOLDERS).joinToString(", ")
        val more = group.folders.size - MAX_FOLDERS
        Text(if (more > 0) "$shown and $more more" else shown, fontSize = 12.sp, color = WowSubtle, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun IncomingSide(incoming: IncomingAddon) {
    Column {
        Text("Replace with: ${incoming.sourceLabel}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
        Text("${incoming.name}  ·  ${versionText(incoming.version)}", fontSize = 12.sp, color = WowSubtle)
        incoming.file?.let { Text(it, fontSize = 12.sp, color = WowSubtle) }
    }
}
