package com.forever.dorolog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TripSelectionScreen(
    selectedTripId: String?,
    onBack: () -> Unit,
    onTripSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tripId = selectedTripId?.takeIf { it.isNotBlank() }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Color(0xFFF8FAFE)).padding(32.dp)
        .testTag("trip-selection-screen"), verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("뒤로가기") }
        Text("기록할 여행 선택", fontSize = 32.sp)
        Text("여행을 선택하면 기록을 시작할 수 있어요", fontSize = 24.sp)
        Button(onClick = { tripId?.let(onTripSelected) }, enabled = tripId != null,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("선택한 여행으로 시작") }
    }
}
