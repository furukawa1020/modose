package com.modose.app.ui.completion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RestoredCompletionScreen(
    model: RestoredCompletionUiModel,
    resetInFlight: Boolean,
    onResetScene: (sceneId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Color(0xFF17352A)
    val accent = Color(0xFFE86A3A)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFFFFBF1),
                        Color(0xFFF0E9D7),
                    ),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .background(Color(0xFFFFF7DF), CircleShape)
                .border(3.dp, accent, CircleShape)
                .semantics {
                    contentDescription = "すべての物体の復元を確認しました"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                color = accent,
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = model.headline,
            color = ink,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "現実を元の位置へ戻しました",
            color = ink.copy(alpha = 0.76f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "${model.restoredObjectCount}個の物体を確認済み",
            color = ink,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 24.dp)
                .background(
                    color = Color(0xFFDCE8D8),
                    shape = RoundedCornerShape(100.dp),
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = { onResetScene(model.sceneId) },
            enabled = !resetInFlight,
            colors = ButtonDefaults.buttonColors(
                containerColor = ink,
                contentColor = Color(0xFFFFFBF1),
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (resetInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFFFFFBF1),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = model.resetLabel,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
