package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Aligns a full-screen Dialog window's status and navigation bar icon appearance with the
 * theme. A Dialog gets its own window, and [org.onekash.kashcal.ui.theme.KashCalTheme] only
 * sets the activity's, so a light-theme sheet would otherwise sit under white system icons
 * the user can't see (and vice versa in dark theme). Keyed on the luminance of the theme's
 * background, so it tracks theme switches like the activity-side effect does.
 */
@Composable
fun SyncDialogSystemBarAppearance() {
    val view = LocalView.current
    val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
    LaunchedEffect(view, lightBackground) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }
}
