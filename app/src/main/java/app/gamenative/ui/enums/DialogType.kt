package app.gamenative.ui.enums

import androidx.compose.ui.graphics.vector.ImageVector

enum class DialogType(val icon: ImageVector? = null) {
    NONE,
    SYNC_FAIL,
    EXECUTABLE_NOT_FOUND,
}
