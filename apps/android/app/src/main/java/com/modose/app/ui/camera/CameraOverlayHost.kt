package com.modose.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CameraOverlayHost(
    cameraSurface: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        cameraSurface()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            content = overlay,
        )
    }
}

@Composable
fun BoxScope.CameraLiveStatus() {
    Text(
        text = "AR CAMERA / LIVE",
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(16.dp)
            .background(
                color = Color(0xB31A1C1B),
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
    )
}
