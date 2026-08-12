package com.displaysplitter.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displaysplitter.R
import com.displaysplitter.geometry.AspectRatio
import com.displaysplitter.geometry.PositionPref
import com.displaysplitter.settings.SettingsRepository
import com.displaysplitter.split.EngagementController
import com.displaysplitter.split.EngageState
import com.displaysplitter.ui.AppIcons
import com.displaysplitter.ui.OneUiSlider
import com.displaysplitter.ui.formatRatio
import com.displaysplitter.ui.theme.OneUiTheme
import com.displaysplitter.ui.theme.oneUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Must cover the longest exit animation in the bubble↔panel transition. */
private const val PANEL_EXIT_MS = 180L

/**
 * The floating control: a 46dp bubble that expands into a One UI-style quick panel.
 */
@Composable
fun OverlayContent(
    controller: EngagementController,
    settings: SettingsRepository,
    collapseRequests: SharedFlow<Unit>,
    onDragStart: () -> Unit,
    onDragMove: () -> Unit,
    onDragEnd: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) = OneUiTheme {
    var expanded by remember { mutableStateOf(false) }
    val settingsState by settings.state.collectAsState()
    val engageState by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        collapseRequests.collect { expanded = false }
    }
    // Window repositioning must bracket the visual transition: the window moves
    // BEFORE the panel's first frame (see onTap below), and back only AFTER the
    // exit animation finishes — otherwise the 316dp panel draws clipped at the
    // edge-snapped bubble position for the duration of the fade.
    LaunchedEffect(expanded) {
        if (!expanded) {
            delay(PANEL_EXIT_MS)
            onExpandedChange(false)
        }
    }

    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.82f))
                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f))
        },
        label = "bubble-panel",
    ) { isExpanded ->
        if (!isExpanded) {
            Bubble(
                opacity = settingsState.bubbleOpacity,
                engaged = engageState is EngageState.Engaged,
                onTap = {
                    onExpandedChange(true)
                    expanded = true
                },
                onDragStart = onDragStart,
                onDragMove = onDragMove,
                onDragEnd = onDragEnd,
            )
        } else {
            QuickPanel(
                engageState = engageState,
                ratio = settingsState.ratio,
                positionPref = settingsState.positionPref,
                opacity = settingsState.bubbleOpacity,
                onRatio = { r -> scope.launch { settings.setRatio(r) } },
                onPosition = { p -> scope.launch { settings.setPositionPref(p) } },
                onOpacityCommit = { v -> scope.launch { settings.setBubbleOpacity(v) } },
                onEngageToggle = {
                    if (engageState is EngageState.Engaged) controller.disengage() else controller.engage()
                    expanded = false
                },
                onOpenSettings = onOpenSettings,
                onClose = { expanded = false },
            )
        }
    }
}

@Composable
private fun Bubble(
    opacity: Float,
    engaged: Boolean,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: () -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = MaterialTheme.oneUi
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "bubble-press")

    // Opacity applies to the whole bubble as ONE layer (shadow included) so nothing
    // desynchronizes; the engaged state is signalled by a badge, never by overriding
    // the user's opacity choice.
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(46.dp)
            .scale(scale)
            .graphicsLayer { alpha = opacity }
            .shadow(6.dp, CircleShape)
            .background(colors.Primary, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onTap)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, _ ->
                        change.consume()
                        onDragMove()
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Ratio,
            contentDescription = stringResource(R.string.bubble_content_desc),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        if (engaged) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(9.dp)
                    .background(Color(0xFF00C853), CircleShape),
            )
        }
    }
}

@Composable
private fun QuickPanel(
    engageState: EngageState,
    ratio: AspectRatio?,
    positionPref: PositionPref,
    opacity: Float,
    onRatio: (AspectRatio?) -> Unit,
    onPosition: (PositionPref) -> Unit,
    onOpacityCommit: (Float) -> Unit,
    onEngageToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.oneUi
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = colors.PanelBackground,
        contentColor = colors.OnSurface,
        shadowElevation = 12.dp,
        modifier = Modifier.width(316.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header ------------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.open_settings),
                        tint = colors.OnSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = colors.OnSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            StatusLine(engageState)
            Spacer(Modifier.height(12.dp))

            // Ratio chips -------------------------------------------------------------
            Text(
                stringResource(R.string.section_ratio),
                fontSize = 13.sp,
                color = colors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AspectRatio.PRESETS.forEach { preset ->
                    RatioChip(
                        label = preset.label,
                        selected = ratio == preset,
                        onClick = { onRatio(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                RatioChip(
                    label = stringResource(R.string.ratio_off),
                    selected = ratio == null,
                    onClick = { onRatio(null) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Position ---------------------------------------------------------------
            Text(
                stringResource(R.string.section_position),
                fontSize = 13.sp,
                color = colors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RatioChip(
                    label = stringResource(R.string.position_auto),
                    selected = positionPref == PositionPref.AUTO,
                    onClick = { onPosition(PositionPref.AUTO) },
                    modifier = Modifier.weight(1f),
                )
                RatioChip(
                    label = stringResource(R.string.position_first),
                    selected = positionPref == PositionPref.FIRST,
                    onClick = { onPosition(PositionPref.FIRST) },
                    modifier = Modifier.weight(1f),
                )
                RatioChip(
                    label = stringResource(R.string.position_second),
                    selected = positionPref == PositionPref.SECOND,
                    onClick = { onPosition(PositionPref.SECOND) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Opacity ----------------------------------------------------------------
            // Local state while dragging; persist once on release — never a disk
            // write per drag frame.
            var liveOpacity by remember(opacity) { mutableFloatStateOf(opacity) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bubble_opacity),
                    fontSize = 13.sp,
                    color = colors.OnSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Live preview: the bubble itself is hidden while the panel is open.
                Box(
                    Modifier
                        .size(24.dp)
                        .graphicsLayer { alpha = liveOpacity }
                        .background(colors.Primary, CircleShape),
                )
            }
            OneUiSlider(
                value = liveOpacity,
                onValueChange = { liveOpacity = it },
                onValueChangeFinished = { onOpacityCommit(liveOpacity) },
                valueRange = 0.2f..1f,
            )
            Spacer(Modifier.height(6.dp))

            // Engage button ----------------------------------------------------------
            val engaged = engageState is EngageState.Engaged
            Surface(
                onClick = onEngageToggle,
                shape = RoundedCornerShape(22.dp),
                color = if (engaged) colors.SurfaceVariant else colors.Primary,
                contentColor = if (engaged) colors.OnSurface else Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
            ) {
                Text(
                    text = stringResource(if (engaged) R.string.disengage else R.string.engage_now),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: EngageState) {
    val colors = MaterialTheme.oneUi
    val (text, color) = when (state) {
        is EngageState.Idle -> stringResource(R.string.status_idle) to colors.OnSurfaceVariant
        is EngageState.Engaging -> stringResource(R.string.status_engaging) to colors.OnSurfaceVariant
        is EngageState.Engaged -> stringResource(
            R.string.engaged_ratio_fmt,
            remember(state.achievedRatio) { formatRatio(state.achievedRatio) },
        ) to colors.OnSurfaceVariant
        // Show the actionable reason (e.g. the Recents guidance), not a generic "failed".
        is EngageState.Failed -> com.displaysplitter.ui.failReasonText(state.reason) to colors.Warning
    }
    Text(text, fontSize = 12.sp, color = color)
}

@Composable
private fun RatioChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.oneUi
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.Primary else colors.SurfaceVariant,
        contentColor = if (selected) Color.White else colors.OnSurface,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 40.dp)) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
            )
        }
    }
}
