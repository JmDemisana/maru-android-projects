package com.maru.namispace.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NamiColorScheme = darkColorScheme(
    background     = NamiDeep,
    surface        = NamiPanel,
    primary        = NamiAccent,
    onPrimary      = NamiDeep,
    onBackground   = NamiText,
    onSurface      = NamiText,
    surfaceVariant = NamiBorder,
    secondary      = NamiRibbon,
    error          = NamiBlush,
)

@Composable
fun NamiSpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NamiColorScheme,
        typography = NamiTypography,
        content = content,
    )
}
