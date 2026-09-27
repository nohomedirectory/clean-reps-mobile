package com.vaylith.cleanrepsmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.vaylith.cleanrepsmobile.R
import com.vaylith.cleanrepsmobile.media.CameraFacing
import com.vaylith.cleanrepsmobile.session.AppState

/** Lens changes belong between captures; a paused practice still has a running video. */
data class CameraSwitchModel(val facing: CameraFacing, val reason: String?) {
    val enabled: Boolean get() = reason == null
    val description: String get() = reason ?: "Switch to ${if (facing == CameraFacing.BACK) "front" else "rear"} camera"

    companion object {
        fun from(state: AppState, permission: Boolean, available: Boolean, facing: CameraFacing): CameraSwitchModel {
            val reason = when {
                state.videoRunning || state.practiceActive -> "Stop video to switch camera"
                state.requestInFlight -> "Wait to switch camera"
                !permission -> "Allow camera to switch"
                !available -> "Camera switch unavailable"
                else -> null
            }
            return CameraSwitchModel(facing, reason)
        }
    }
}

@Composable
internal fun CameraSwitchButton(model: CameraSwitchModel, onSwitch: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onSwitch, enabled = model.enabled) {
            Icon(painterResource(R.drawable.ic_switch_camera), contentDescription = model.description)
        }
        Text(model.facing.label, style = MaterialTheme.typography.labelMedium)
    }
}
