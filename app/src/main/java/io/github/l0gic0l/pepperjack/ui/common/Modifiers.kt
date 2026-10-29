package io.github.l0gic0l.pepperjack.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
