package com.acme.rc

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun RcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0EA5E9),
            onPrimary = Color.White,
            secondary = Color(0xFF6366F1)
        ),
        content = content
    )
}
