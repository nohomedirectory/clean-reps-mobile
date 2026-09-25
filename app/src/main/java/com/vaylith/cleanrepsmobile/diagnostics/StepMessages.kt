package com.vaylith.cleanrepsmobile.diagnostics

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface FailureKind {
    data object Timeout : FailureKind
    data object Unreachable : FailureKind
    data class Http(val code: Int) : FailureKind
    data object Camera : FailureKind
    data object Other : FailureKind

    companion object {
        private const val MAX_CAUSES = 4

        /**
         * Classifies a network failure from the exception types in its cause
         * chain; the message is never read. HTTP and camera failures are
         * known only to their callers, which pass [Http] or [Camera] directly.
         */
        fun of(error: Throwable): FailureKind {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth <= MAX_CAUSES) {
                when (current) {
                    is SocketTimeoutException -> return Timeout
                    is UnknownHostException, is ConnectException, is NoRouteToHostException,
                    is PortUnreachableException -> return Unreachable
                }
                val cause = current.cause
                current = if (cause === current) null else cause
                depth += 1
            }
            return Other
        }
    }
}

/**
 * Owner-facing message for a failed step. Pure: it takes no exception text, so a
 * cause (and any secret in it) can only reach the DiagnosticsLog, never the screen.
 */
object StepMessages {
    private enum class Target { API, VIDEO, CAMERA }

    fun forError(step: DiagnosticStep, error: Throwable): String = message(step, FailureKind.of(error))

    fun message(step: DiagnosticStep, kind: FailureKind): String {
        val (target, label) = describe(step)
        return when (kind) {
            FailureKind.Timeout, FailureKind.Unreachable -> {
                val reason = if (kind == FailureKind.Timeout) "timeout" else "unreachable"
                when (target) {
                    Target.API -> "Couldn't reach Clean Reps for $label ($reason) — is Tailscale on?"
                    Target.VIDEO -> "Couldn't reach the video server for $label ($reason) — is Tailscale on?"
                    Target.CAMERA -> "Camera didn't respond for $label ($reason) — close other camera apps and try again"
                }
            }
            is FailureKind.Http -> {
                val server = when (target) {
                    Target.API -> "Server"
                    Target.VIDEO -> "Video server"
                    Target.CAMERA -> "Camera service"
                }
                when (kind.code) {
                    in 400..499 -> "$server refused $label (${kind.code})"
                    in 500..599 -> "$server error during $label (${kind.code})"
                    else -> "$server gave an unexpected reply for $label (${kind.code})"
                }
            }
            FailureKind.Camera -> "Camera problem during $label — close other camera apps and try again"
            FailureKind.Other -> "Couldn't complete $label (unexpected error) — see Diagnostics"
        }
    }

    private fun describe(step: DiagnosticStep): Pair<Target, String> = when (step) {
        DiagnosticStep.CREATE_SESSION -> Target.API to "create session"
        DiagnosticStep.ATTACH_CAPTURE -> Target.API to "attach capture"
        DiagnosticStep.CLIENT_INFO -> Target.API to "app info"
        DiagnosticStep.PUBLISHER_START -> Target.VIDEO to "start video"
        DiagnosticStep.SRT_CONNECT -> Target.VIDEO to "video connection"
        DiagnosticStep.CAMERA_OPEN -> Target.CAMERA to "camera open"
        DiagnosticStep.PREVIEW_START -> Target.CAMERA to "camera preview"
        DiagnosticStep.CREATE_BLOCK -> Target.API to "start practice"
        DiagnosticStep.MARK_REACQUIRED -> Target.API to "back-in-frame marker"
        DiagnosticStep.PAUSE -> Target.API to "pause practice"
        DiagnosticStep.RESUME -> Target.API to "resume practice"
        DiagnosticStep.HEALTH -> Target.API to "video health report"
        DiagnosticStep.EVENT_STREAM -> Target.API to "live updates"
        DiagnosticStep.QUALITY_REPORT -> Target.API to "session check"
    }
}
