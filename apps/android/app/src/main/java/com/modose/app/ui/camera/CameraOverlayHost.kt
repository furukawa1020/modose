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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.session.ArTrackingDiagnostics
import com.modose.app.ar.session.ArTrackingIssue
import com.modose.app.ar.session.ArTrackingPhase

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
fun BoxScope.CameraLiveStatus(
    diagnostics: ArTrackingDiagnostics?,
    horizontalPlaneState: HorizontalPlaneState?,
    sceneAnchorState: SceneAnchorState?,
) {
    val presentation = if (diagnostics?.phase == ArTrackingPhase.Tracking) {
        sceneAnchorState.toPresentation() ?: horizontalPlaneState.toPresentation()
    } else {
        diagnostics.toPresentation()
    }
    Text(
        text = presentation.label,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = presentation.stateDescription
            }
            .background(
                color = presentation.background,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
    )
}

private data class TrackingPresentation(
    val label: String,
    val stateDescription: String,
    val background: Color,
)

private fun ArTrackingDiagnostics?.toPresentation(): TrackingPresentation = when {
    this == null -> presentation("CHECKING TRACKING", "Checking AR tracking", 0xB31A1C1B)
    phase == ArTrackingPhase.Tracking -> presentation("AR CAMERA / LIVE", "AR tracking is active", 0xB3005A55)
    phase == ArTrackingPhase.Stopped -> presentation("TRACKING STOPPED", "AR tracking stopped", 0xB38B1E1E)
    issue == ArTrackingIssue.InsufficientLight -> presentation("MORE LIGHT NEEDED", "Move to a brighter area", 0xB37A4E00)
    issue == ArTrackingIssue.ExcessiveMotion -> presentation("MOVE PHONE SLOWLY", "Move the phone more slowly", 0xB37A4E00)
    issue == ArTrackingIssue.InsufficientFeatures -> presentation("POINT AT A TEXTURED SURFACE", "Point at a surface with visible detail", 0xB37A4E00)
    issue == ArTrackingIssue.CameraUnavailable -> presentation("CAMERA UNAVAILABLE", "The camera is unavailable", 0xB38B1E1E)
    issue == ArTrackingIssue.BadState -> presentation("TRACKING PAUSED", "AR tracking is paused", 0xB38B1E1E)
    else -> presentation("SEARCHING FOR SURFACE", "Searching for trackable detail", 0xB31A1C1B)
}

private fun HorizontalPlaneState?.toPresentation(): TrackingPresentation = when (this) {
    null,
    HorizontalPlaneState.Searching,
    -> presentation("FINDING SURFACE", "Searching for a horizontal surface", 0xB31A1C1B)
    is HorizontalPlaneState.Tracking -> presentation("SURFACE READY", "Horizontal surface is ready", 0xB3005A55)
    is HorizontalPlaneState.Lost -> presentation("SURFACE LOST", "Horizontal surface tracking was lost", 0xB38B1E1E)
}

private fun SceneAnchorState?.toPresentation(): TrackingPresentation? = when (this) {
    null,
    SceneAnchorState.Unavailable,
    -> null
    is SceneAnchorState.Tracking -> presentation("SCENE LOCKED", "Scene anchor is tracking", 0xB3005A55)
    is SceneAnchorState.Paused -> presentation("ANCHOR PAUSED", "Scene anchor tracking is paused", 0xB37A4E00)
    is SceneAnchorState.Lost -> presentation("ANCHOR LOST", "Scene anchor was lost", 0xB38B1E1E)
    is SceneAnchorState.Failed -> presentation("ANCHOR FAILED", "Scene anchor could not be created", 0xB38B1E1E)
}

private fun presentation(
    label: String,
    stateDescription: String,
    color: Long,
) = TrackingPresentation(label, stateDescription, Color(color))
