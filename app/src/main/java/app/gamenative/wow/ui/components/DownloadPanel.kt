package app.gamenative.wow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.wow.install.DownloadSpeed
import app.gamenative.wow.install.InstallProgress
import app.gamenative.wow.ui.theme.Fw

/**
 * A running download: what it is and how far along, the bar, then size, speed and time left, and the
 * file being fetched. Shows "Preparing" until the installer knows the total.
 */
@Composable
fun FwDownloadPanel(title: String, progress: InstallProgress?, bytesPerSecond: Long?, modifier: Modifier = Modifier) {
    val fraction = progress?.fraction
    Column(
        modifier.fillMaxWidth().background(Fw.Stone, Fw.Shape).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, style = Fw.Text, modifier = Modifier.weight(1f))
            Text(
                fraction?.let { "${(it * 100).toInt()}%" } ?: "Preparing",
                style = Fw.Title.copy(color = Fw.Brass, fontSize = 26.sp),
            )
        }
        FwProgress(fraction)
        if (progress != null && progress.totalBytes > 0) {
            Text(DownloadSpeed.summary(progress, bytesPerSecond), style = Fw.Data.copy(color = Fw.Bone))
        } else {
            Text("Reading the build manifest…", style = Fw.Data)
        }
        progress?.stepText?.let { Text(it, style = Fw.Data, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Download speed in bytes per second for a stream of [progress] updates, averaged over [windowMs]. */
@Composable
fun rememberDownloadSpeed(progress: InstallProgress?, windowMs: Long = 3_000): Long? {
    val speed = remember { DownloadSpeed(windowMs) }
    var bytesPerSecond by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(progress) {
        progress?.let { p -> speed.add(p.fetchedBytes, System.nanoTime() / 1_000_000)?.let { bytesPerSecond = it } }
    }
    return bytesPerSecond
}
