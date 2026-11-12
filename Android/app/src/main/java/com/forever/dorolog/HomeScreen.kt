package com.forever.dorolog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.White).testTag("home-screen")) {
        TextButton(onClick = onBack, modifier = Modifier.padding(24.dp).sizeIn(minWidth = 96.dp, minHeight = 48.dp)) {
            Text(stringResource(R.string.back), fontSize = 24.sp, color = Color(0xFF7365D9))
        }
    }
}
