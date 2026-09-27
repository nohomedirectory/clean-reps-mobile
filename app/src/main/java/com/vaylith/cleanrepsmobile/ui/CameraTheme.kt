package com.vaylith.cleanrepsmobile.ui

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Decorative choices only: health, verdict and recording colors keep their existing meaning. */
enum class CameraThemeOption(val label: String, val description: String) {
    SPACE("Space", "Stars, planetary rings & a quiet black hole"),
    PSYCHEDELIC("Psychedelic", "Liquid color & slow, flowing waves"),
    SLIME("Slime", "Soft blobs, little drips & a lime glow"),
    CLASSIC("Classic", "The original orange, with a quiet dark panel"),
}

data class CameraThemePreferences(
    val theme: CameraThemeOption = CameraThemeOption.SPACE,
    val motionEnabled: Boolean = true,
)

/** A separate, app-private preference file; no connection or capture data belongs here. */
class CameraThemeStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    fun load(): CameraThemePreferences {
        // Reading the typed map also tolerates corrupt or obsolete preference types.
        val saved = preferences.all
        return CameraThemePreferences(
            theme = CameraThemeOption.entries.firstOrNull { it.name == saved[THEME] } ?: CameraThemeOption.SPACE,
            motionEnabled = saved[MOTION] as? Boolean ?: true,
        )
    }

    fun save(value: CameraThemePreferences) {
        check(preferences.edit().putString(THEME, value.theme.name).putBoolean(MOTION, value.motionEnabled).commit()) {
            "Could not save the theme on this phone"
        }
    }

    companion object {
        const val FILE_NAME = "camera-appearance"
        internal const val THEME = "theme"
        internal const val MOTION = "motion"
    }
}

data class CameraThemePalette(val accent: Color, val secondary: Color, val panel: Color) {
    val onAccent: Color = Color(0xFF101018)
    val text: Color = Color(0xFFF5F4FA)
    val mutedText: Color = Color(0xFFD0CDD9)
}

val CameraThemeOption.palette: CameraThemePalette
    get() = when (this) {
        CameraThemeOption.SPACE -> CameraThemePalette(Color(0xFFC4C7FF), Color(0xFF8BE6F4), Color(0xFF0B0E20))
        CameraThemeOption.PSYCHEDELIC -> CameraThemePalette(Color(0xFFF4B8FF), Color(0xFF9FE7EA), Color(0xFF160D21))
        CameraThemeOption.SLIME -> CameraThemePalette(Color(0xFFBBF36B), Color(0xFF7CDCC7), Color(0xFF0F190E))
        CameraThemeOption.CLASSIC -> CameraThemePalette(Color(0xFFFF6D00), Color(0xFFFFCFAC), Color(0xFF121212))
    }

@Stable
class CameraThemeState internal constructor(initial: CameraThemePreferences, private val store: CameraThemeStore?) {
    var preferences by mutableStateOf(initial)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    val theme: CameraThemeOption get() = preferences.theme

    fun selectTheme(theme: CameraThemeOption) = update(preferences.copy(theme = theme))
    fun setMotionEnabled(enabled: Boolean) = update(preferences.copy(motionEnabled = enabled))

    private fun update(value: CameraThemePreferences) {
        try {
            store?.save(value)
            preferences = value
            saveError = null
        } catch (_: Exception) {
            saveError = "Could not save the theme on this phone. Try again."
        }
    }
}

val LocalCameraTheme = staticCompositionLocalOf { CameraThemeState(CameraThemePreferences(), null) }

/** Same camera layout and distance-readable typography; only the control palette changes. */
@Composable
fun CleanRepsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val appearance = remember(context) { CameraThemeStore(context).let { CameraThemeState(it.load(), it) } }
    val palette = appearance.theme.palette
    val base = Typography()
    CompositionLocalProvider(LocalCameraTheme provides appearance, LocalContentColor provides palette.text) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = palette.accent, onPrimary = palette.onAccent,
                primaryContainer = palette.panel, onPrimaryContainer = palette.accent,
                secondary = palette.secondary, onSecondary = palette.onAccent,
                background = Color.Black, onBackground = palette.text,
                surface = palette.panel, onSurface = palette.text,
                surfaceVariant = palette.panel, onSurfaceVariant = palette.mutedText,
                outline = palette.mutedText.copy(alpha = 0.6f),
                error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            ),
            typography = base.copy(
                titleLarge = base.titleLarge.copy(fontSize = 24.sp),
                titleMedium = base.titleMedium.copy(fontSize = 20.sp),
                bodyLarge = base.bodyLarge.copy(fontSize = 18.sp),
            ),
            content = content,
        )
    }
}

@Composable
fun CameraThemePicker(landscape: Boolean, onDismiss: () -> Unit) {
    val appearance = LocalCameraTheme.current
    ScreenPanel(title = "Themes", landscape = landscape, onDismiss = onDismiss) {
        Text("Make it yours", style = MaterialTheme.typography.titleMedium)
        Text("Color and motion for your controls.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CameraThemeOption.entries.forEach { theme ->
                ThemeChoice(theme, selected = appearance.theme == theme, onSelect = { appearance.selectTheme(theme) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Motion", style = MaterialTheme.typography.titleMedium)
                Text("Slow movement in the controls. Follows your system animation setting.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = appearance.preferences.motionEnabled, onCheckedChange = appearance::setMotionEnabled,
                modifier = Modifier.semantics { contentDescription = "Theme motion" })
        }
        appearance.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ThemeChoice(theme: CameraThemeOption, selected: Boolean, onSelect: () -> Unit) {
    val palette = theme.palette
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 92.dp).clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) palette.accent else palette.mutedText.copy(alpha = 0.25f), shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Canvas(Modifier.matchParentSize()) { drawThemePanel(theme, 0f) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(theme.label, color = palette.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(theme.description, color = palette.mutedText, style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** Apply only to a control container. It must never decorate the preview or encoded frames. */
@Composable
fun Modifier.cameraThemePanel(shape: Shape = RoundedCornerShape(20.dp)): Modifier {
    val appearance = LocalCameraTheme.current
    val theme = appearance.theme
    val systemMotion = rememberSystemMotionEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val phase = rememberPanelPhase(appearance.preferences.motionEnabled && systemMotion &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) && theme != CameraThemeOption.CLASSIC)
    // Read phase during drawing, not composition: ticks never remeasure controls or the camera.
    return clip(shape).drawBehind { drawThemePanel(theme, phase.value) }
}

@Composable
private fun rememberPanelPhase(enabled: Boolean): State<Float> {
    val phase = remember { mutableStateOf(0f) }
    LaunchedEffect(enabled) {
        phase.value = 0f
        if (enabled) while (isActive) {
            phase.value = (SystemClock.uptimeMillis() % 24_000L) / 24_000f
            delay(100L) // Ten small canvas redraws per second; no bitmap allocation or blur layers.
        }
    }
    return phase
}

@Composable
private fun rememberSystemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val owner = LocalLifecycleOwner.current
    fun enabled() = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        .getOrDefault(false)
    var motionEnabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver, owner) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionEnabled = enabled() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) motionEnabled = enabled()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        owner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            resolver.unregisterContentObserver(observer)
            owner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return motionEnabled
}

/** All artwork is dimmed together, keeping overlapping shapes from washing out text. */
internal const val THEME_ART_ALPHA = 0.18f

private fun DrawScope.drawThemePanel(theme: CameraThemeOption, phase: Float) {
    val palette = theme.palette
    drawRect(palette.panel)
    if (theme == CameraThemeOption.CLASSIC) return
    // Decorative ink stays behind an opaque scrim; even overlapping shapes have a fixed ceiling.
    val angle = phase * 2f * PI.toFloat()
    when (theme) {
        CameraThemeOption.SPACE -> {
            repeat(18) { index ->
                val x = ((index * 47 + 13) % 101) / 101f * size.width
                val y = ((index * 31 + 7) % 97) / 97f * size.height
                val twinkle = 0.6f + 0.25f * sin(angle + index)
                drawCircle(palette.text.copy(alpha = twinkle), (if (index % 4 == 0) 1.3f else 0.7f).dp.toPx(), Offset(x, y))
            }
            val radius = minOf(33.dp.toPx(), size.width * 0.14f)
            val planet = Offset(size.width * 0.85f, size.height * 0.22f + sin(angle) * 3.dp.toPx())
            drawCircle(Brush.radialGradient(listOf(palette.secondary, palette.accent), planet, radius), radius, planet)
            drawOval(palette.accent, planet - Offset(radius * 1.6f, radius * 0.43f), Size(radius * 3.2f, radius * 0.86f), style = Stroke(1.4.dp.toPx()))
            val hole = Offset(size.width * 0.09f, size.height * 0.79f)
            val holeRadius = radius * 0.7f
            drawCircle(Brush.radialGradient(listOf(palette.accent, Color.Transparent), hole, holeRadius * 1.9f), holeRadius * 1.9f, hole)
            drawOval(palette.secondary, hole - Offset(holeRadius * 1.7f, holeRadius * 0.5f), Size(holeRadius * 3.4f, holeRadius), style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, holeRadius * 0.7f, hole)
        }
        CameraThemeOption.PSYCHEDELIC -> {
            val radius = maxOf(size.width, size.height) * 0.75f
            drawCircle(Brush.radialGradient(listOf(palette.accent, Color.Transparent), Offset(size.width, size.height * 0.16f), radius), radius, Offset(size.width, size.height * 0.16f))
            drawCircle(Brush.radialGradient(listOf(palette.secondary, Color.Transparent), Offset(0f, size.height), radius), radius, Offset(0f, size.height))
            repeat(5) { index ->
                val path = Path()
                val origin = size.width * (0.68f + index * 0.08f)
                path.moveTo(origin, -8.dp.toPx())
                path.cubicTo(origin - 85.dp.toPx(), size.height * 0.28f + sin(angle) * 8.dp.toPx(),
                    origin + 70.dp.toPx(), size.height * 0.6f, origin - 20.dp.toPx(), size.height + 8.dp.toPx())
                drawPath(path, if (index % 2 == 0) palette.accent else palette.secondary, style = Stroke((2 + index).dp.toPx()))
            }
        }
        CameraThemeOption.SLIME -> {
            val sway = sin(angle) * 7.dp.toPx()
            val blob = Path().apply {
                moveTo(size.width * 0.61f, 0f)
                cubicTo(size.width * 0.52f, 35.dp.toPx() + sway, size.width * 0.9f, 28.dp.toPx(), size.width * 0.83f, 68.dp.toPx() + sway)
                cubicTo(size.width * 0.78f, 105.dp.toPx(), size.width, 110.dp.toPx() - sway, size.width, 42.dp.toPx())
                lineTo(size.width, 0f)
                close()
            }
            drawPath(blob, palette.accent)
            drawCircle(palette.secondary, 37.dp.toPx() + sway * 0.3f, Offset(12.dp.toPx(), size.height - 5.dp.toPx()))
            drawCircle(palette.accent, 17.dp.toPx(), Offset(55.dp.toPx() + sway, size.height - 15.dp.toPx()))
            drawOval(palette.text.copy(alpha = 0.65f), Offset(size.width * 0.88f, 23.dp.toPx()), Size(6.dp.toPx(), 15.dp.toPx()))
            repeat(3) { index ->
                drawCircle(palette.secondary, (3 + index).dp.toPx(), Offset(size.width * 0.92f, size.height * (0.55f + index * 0.12f) + sway))
            }
        }
        CameraThemeOption.CLASSIC -> Unit
    }
    // One inexpensive scrim caps the total artwork alpha, including overlapping gradients.
    drawRect(palette.panel.copy(alpha = 1f - THEME_ART_ALPHA))
}
