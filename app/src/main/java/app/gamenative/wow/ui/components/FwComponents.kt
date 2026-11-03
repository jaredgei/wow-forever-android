package app.gamenative.wow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.wow.ui.theme.Fw

/**
 * The app-wide focus treatment: a ring and a slight lift. The ring is bronze, except on elements that
 * are bronze themselves (primary buttons), which pass [ringColor] so the ring stays visible.
 */
@Composable
fun Modifier.fwFocus(interaction: MutableInteractionSource, shape: Shape, lift: Boolean = true, ringColor: Color = Fw.Brass): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && lift) 1.02f else 1f, label = "focus")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.border(if (focused) 3.dp else 0.dp, if (focused) ringColor else Color.Transparent, shape)
}

/**
 * A home-screen tile: full-bleed [art] behind a flat translucent band holding the title, [content]
 * and [footer]. Without art (or while it loads) the tile is plain slate stone.
 */
@Composable
fun FwTile(
    title: String,
    modifier: Modifier = Modifier,
    art: ForeverArt? = null,
    badge: String? = null,
    titleStyle: TextStyle = Fw.Title,
    footer: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .fwFocus(interaction, Fw.TileShape)
            .clip(Fw.TileShape)
            .background(Fw.Stone)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        art?.let { ArtImage(it, Modifier.fillMaxSize()) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Fw.Scrim).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = titleStyle)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
            footer?.let { Box(Modifier.padding(top = 6.dp)) { it() } }
        }
        badge?.let {
            Text(
                it,
                style = Fw.Label.copy(color = Fw.BrassInk, fontSize = 13.sp),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Fw.Brass, Fw.Pill).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** The bronze "press here" bar inside the Play tile. Not focusable itself: the tile is. */
@Composable
fun FwActionBar(text: String, enabled: Boolean = true) {
    Box(Modifier.background(if (enabled) Fw.Brass else Fw.Stone2, Fw.Shape).padding(horizontal = 40.dp, vertical = 13.dp)) {
        Text(text.uppercase(), style = Fw.Label.copy(color = if (enabled) Fw.BrassInk else Fw.Ash, fontSize = 17.sp, letterSpacing = 1.8.sp))
    }
}

enum class FwButtonKind { Primary, Ghost, Danger }

@Composable
fun FwButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: FwButtonKind = FwButtonKind.Primary,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val (bg, fg) = when (kind) {
        FwButtonKind.Primary -> Fw.Brass to Fw.BrassInk
        FwButtonKind.Ghost -> Fw.Stone2 to Fw.Bone
        FwButtonKind.Danger -> Fw.Stone2 to Fw.Warn
    }
    Box(
        modifier
            .heightIn(min = 46.dp)
            .fwFocus(interaction, Fw.Shape, lift = false, ringColor = if (kind == FwButtonKind.Primary && enabled) Fw.Bone else Fw.Brass)
            .background(if (enabled) bg else Fw.Stone, Fw.Shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = Fw.Label.copy(color = if (enabled) fg else Fw.Ash), maxLines = 1)
            icon?.let { androidx.compose.material3.Icon(it, contentDescription = null, tint = if (enabled) fg else Fw.Ash, modifier = Modifier.height(16.dp)) }
        }
    }
}

/** A quiet underlined text link for a side path, focusable like the buttons so a controller reaches it. */
@Composable
fun FwLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text,
        style = Fw.Secondary.copy(color = Fw.Brass, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = modifier
            .fwFocus(interaction, Fw.Shape, lift = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** A pill toggle: the current choice is filled with parchment. */
@Composable
fun FwChip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 42.dp)
            .fwFocus(interaction, Fw.Pill, lift = false)
            .background(if (on) Fw.Bone else Fw.Stone, Fw.Pill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Fw.Label.copy(color = if (on) Fw.Ground else Fw.Ash, fontSize = 13.sp), maxLines = 1)
    }
}

/** A selectable list row. Rows are separate rounded surfaces; focus and selection share the bronze ring. */
@Composable
fun FwRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (focused && onFocus != null) onFocus()
    val ring = focused || selected
    Row(
        modifier
            .fillMaxWidth()
            .border(if (ring) 2.dp else 0.dp, if (ring) Fw.Brass else Color.Transparent, Fw.Shape)
            .background(if (ring) Fw.Stone2 else Fw.Stone, Fw.Shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun FwTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 20.dp, vertical = 14.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FwButton("Back", onBack, kind = FwButtonKind.Ghost)
        Text(title, style = Fw.Title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** A thin rounded progress bar; indeterminate when [fraction] is null. */
@Composable
fun FwProgress(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(6.dp).clip(Fw.Pill), color = Fw.Brass, trackColor = Fw.Stone2)
    } else {
        Box(modifier.fillMaxWidth().height(6.dp).clip(Fw.Pill).background(Fw.Stone2)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(Fw.Pill).background(Fw.Brass))
        }
    }
}

@Composable
fun FwDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Fw.Title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = Fw.Text.copy(color = Fw.Ash))
                extra()
            }
        },
        confirmButton = { FwButton(confirm, onConfirm) },
        dismissButton = { FwButton(dismiss, onDismiss, kind = FwButtonKind.Ghost) },
        containerColor = Fw.Stone,
        shape = RoundedCornerShape(20.dp),
    )
}
