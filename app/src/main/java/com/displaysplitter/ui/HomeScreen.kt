package com.displaysplitter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displaysplitter.R
import com.displaysplitter.geometry.AspectRatio
import com.displaysplitter.geometry.PaneSide
import com.displaysplitter.geometry.PositionPref
import com.displaysplitter.geometry.SplitAxis
import com.displaysplitter.settings.KNOWN_VIDEO_APPS
import com.displaysplitter.settings.SettingsRepository
import com.displaysplitter.split.EngagementController
import com.displaysplitter.split.EngageState
import com.displaysplitter.split.FailReason
import com.displaysplitter.split.Posture
import com.displaysplitter.ui.theme.oneUi
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    settings: SettingsRepository,
    controller: EngagementController,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    notificationsEnabled: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val colors = MaterialTheme.oneUi
    val settingsState by settings.state.collectAsState()
    val engageState by controller.state.collectAsState()
    val posture by controller.posture.collectAsState()
    val onInner by controller.onInnerDisplay.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.Background),
    ) {
        // One UI signature: large centered title, proportional to screen height,
        // collapsing *continuously* with scroll into the small top bar.
        val largeTitleHeight = maxHeight * 0.28f
        val largeTitlePx = with(LocalDensity.current) { largeTitleHeight.toPx() }
        val collapseFraction by remember(largeTitlePx) {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / (largeTitlePx * 0.6f)).coerceIn(0f, 1f)
            }
        }

        // One UI collapsing bars never rest half-faded: when a scroll settles inside
        // the collapse zone, snap fully open or fully collapsed like Sesl toolbars do.
        LaunchedEffect(listState.isScrollInProgress) {
            if (!listState.isScrollInProgress && listState.firstVisibleItemIndex == 0) {
                val snapPoint = largeTitlePx * 0.6f
                val off = listState.firstVisibleItemScrollOffset.toFloat()
                if (off > 0f && off < snapPoint) {
                    if (off < snapPoint / 2) listState.animateScrollToItem(0)
                    else listState.animateScrollBy(snapPoint - off)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            item { LargeTitle(height = largeTitleHeight, alpha = 1f - collapseFraction) }

            item {
                StatusCard(
                    engageState = engageState,
                    ratio = settingsState.ratio,
                    posture = posture,
                    onInner = onInner,
                )
            }

            if (!overlayGranted || !accessibilityEnabled || !notificationsEnabled) {
                item {
                    SectionHeader(stringResource(R.string.perm_section_title))
                    SectionCard {
                        var first = true
                        if (!overlayGranted) {
                            first = false
                            PermissionRow(
                                title = stringResource(R.string.perm_overlay_title),
                                desc = stringResource(R.string.perm_overlay_desc),
                                onGrant = onRequestOverlay,
                            )
                        }
                        if (!accessibilityEnabled) {
                            if (!first) RowDivider()
                            first = false
                            PermissionRow(
                                title = stringResource(R.string.perm_accessibility_title),
                                desc = stringResource(R.string.perm_accessibility_desc),
                                onGrant = onRequestAccessibility,
                            )
                        }
                        if (!notificationsEnabled) {
                            if (!first) RowDivider()
                            PermissionRow(
                                title = stringResource(R.string.perm_notifications_title),
                                desc = stringResource(R.string.perm_notifications_desc),
                                onGrant = onRequestNotifications,
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.section_ratio))
                SectionCard {
                    Text(
                        stringResource(R.string.ratio_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.OnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
                    ) {
                        AspectRatio.PRESETS.forEach { preset ->
                            SelectChip(
                                label = preset.label,
                                selected = settingsState.ratio == preset,
                                onClick = { scope.launch { settings.setRatio(preset) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SelectChip(
                            label = stringResource(R.string.ratio_off),
                            selected = settingsState.ratio == null,
                            onClick = { scope.launch { settings.setRatio(null) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.section_position))
                SectionCard {
                    PositionRow(
                        title = stringResource(R.string.position_auto),
                        desc = stringResource(R.string.position_auto_desc),
                        selected = settingsState.positionPref == PositionPref.AUTO,
                        onClick = { scope.launch { settings.setPositionPref(PositionPref.AUTO) } },
                    )
                    RowDivider()
                    PositionRow(
                        title = stringResource(R.string.position_first),
                        desc = null,
                        selected = settingsState.positionPref == PositionPref.FIRST,
                        onClick = { scope.launch { settings.setPositionPref(PositionPref.FIRST) } },
                    )
                    RowDivider()
                    PositionRow(
                        title = stringResource(R.string.position_second),
                        desc = null,
                        selected = settingsState.positionPref == PositionPref.SECOND,
                        onClick = { scope.launch { settings.setPositionPref(PositionPref.SECOND) } },
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.section_bubble))
                SectionCard {
                    SwitchRow(
                        title = stringResource(R.string.bubble_show),
                        desc = null,
                        checked = settingsState.bubbleEnabled,
                        onChecked = { scope.launch { settings.setBubbleEnabled(it) } },
                    )
                    RowDivider()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        // Local slider state: persist once on release, not per drag frame.
                        var liveOpacity by remember(settingsState.bubbleOpacity) {
                            mutableFloatStateOf(settingsState.bubbleOpacity)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.bubble_opacity),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            // Live preview of the bubble at the chosen opacity.
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(
                                        colors.Primary.copy(alpha = liveOpacity),
                                        CircleShape,
                                    ),
                            )
                        }
                        OneUiSlider(
                            value = liveOpacity,
                            onValueChange = { liveOpacity = it },
                            onValueChangeFinished = {
                                scope.launch { settings.setBubbleOpacity(liveOpacity) }
                            },
                            valueRange = 0.2f..1f,
                            enabled = settingsState.bubbleEnabled,
                        )
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.section_apps))
                SectionCard {
                    Text(
                        stringResource(R.string.apps_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.OnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    val pm = LocalContext.current.packageManager
                    KNOWN_VIDEO_APPS.forEachIndexed { index, app ->
                        val installed = remember(app.packageName) {
                            runCatching { pm.getPackageInfo(app.packageName, 0) }.isSuccess
                        }
                        val label = remember(app.packageName) {
                            runCatching {
                                pm.getApplicationLabel(
                                    pm.getApplicationInfo(app.packageName, 0)
                                ).toString()
                            }.getOrDefault(app.labelFallback)
                        }
                        SwitchRow(
                            title = label,
                            desc = if (installed) null else stringResource(R.string.apps_not_installed),
                            checked = app.packageName in settingsState.enabledApps,
                            enabled = installed,
                            onChecked = {
                                scope.launch { settings.setAppEnabled(app.packageName, it) }
                            },
                        )
                        if (index != KNOWN_VIDEO_APPS.lastIndex) RowDivider()
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.section_behavior))
                SectionCard {
                    SwitchRow(
                        title = stringResource(R.string.auto_engage),
                        desc = stringResource(R.string.auto_engage_desc),
                        checked = settingsState.autoEngage,
                        onChecked = { scope.launch { settings.setAutoEngage(it) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.auto_reengage),
                        desc = stringResource(R.string.auto_reengage_desc),
                        checked = settingsState.autoReengage,
                        onChecked = { scope.launch { settings.setAutoReengage(it) } },
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp, vertical = 28.dp),
                )
            }
        }

        // Collapsed top bar: alpha driven continuously by the same collapse fraction.
        Surface(
            color = colors.Background.copy(alpha = collapseFraction),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .height(56.dp)
                    .fillMaxWidth(),
                // One UI collapses the large centered title into a start-aligned toolbar title.
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.OnSurface,
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .alpha(collapseFraction),
                )
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------------

@Composable
private fun LargeTitle(height: androidx.compose.ui.unit.Dp, alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(WindowInsets.statusBars.asPaddingValues())
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.oneUi.OnSurface,
            modifier = Modifier.alpha(alpha),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.oneUi.Primary,
        modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.oneUi.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(start = 20.dp)
            .fillMaxWidth()
            .height(0.7.dp)
            .background(MaterialTheme.oneUi.Divider),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.oneUi
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChecked(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.OnSurface)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            // Non-null thumbContent keeps the thumb at a constant large size in both
            // states — One UI's constant white thumb, not M3's growing dot.
            thumbContent = {},
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.Primary,
                uncheckedTrackColor = colors.TrackInactive,
                uncheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun PositionRow(
    title: String,
    desc: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.oneUi
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        // One UI radio: outlined circle, filled dot when selected.
        Box(
            Modifier
                .size(22.dp)
                .background(
                    if (selected) colors.Primary else Color.Transparent,
                    CircleShape,
                )
                .then(
                    if (!selected) {
                        Modifier.border(width = 2.dp, color = colors.TrackInactive, shape = CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.OnSurface)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.OnSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, onGrant: () -> Unit) {
    val colors = MaterialTheme.oneUi
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.OnSurface)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = colors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Surface(
            onClick = onGrant,
            shape = RoundedCornerShape(18.dp),
            color = colors.Primary,
            contentColor = Color.White,
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(
                    stringResource(R.string.perm_grant),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectChip(
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
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 9.dp, horizontal = 2.dp),
            )
        }
    }
}


/**
 * Live diagram of the inner display: rounded screen outline, camera hole,
 * video pane (blue) and spacer pane (dark), reflecting current state.
 */
@Composable
private fun StatusCard(
    engageState: EngageState,
    ratio: AspectRatio?,
    posture: Posture,
    onInner: Boolean,
) {
    val colors = MaterialTheme.oneUi
    val engaged = engageState as? EngageState.Engaged
    val axis = engaged?.plan?.axis ?: SplitAxis.HORIZONTAL
    // Full display bounds, not this window's metrics: the settings activity itself
    // can be running in a split or pop-up window on a Fold.
    val context = LocalContext.current
    val display = remember(context, LocalConfiguration.current) {
        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    }
    val videoFraction = when {
        engaged != null -> {
            val len = if (axis == SplitAxis.HORIZONTAL) engaged.videoPane.height() else engaged.videoPane.width()
            val total = if (axis == SplitAxis.HORIZONTAL) display.height() else display.width()
            if (total > 0) len.toFloat() / total else 0.62f
        }
        ratio != null && display.height() > 0 -> (display.width() / ratio.value) / display.height()
        else -> 1f
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = colors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(20.dp),
            ) {
                FoldDiagram(
                    engaged = engaged != null,
                    videoSide = engaged?.plan?.videoSide ?: PaneSide.SECOND,
                    axis = axis,
                    videoFraction = videoFraction,
                    primary = colors.Primary,
                    outline = colors.TrackInactive,
                    spacerColor = colors.OnSurface.copy(alpha = 0.8f),
                    holeColor = colors.OnSurfaceVariant,
                )
                Spacer(Modifier.width(20.dp))
                Column {
                    // Device-posture states take priority: the app pauses itself there.
                    val paused = posture == Posture.HALF_OPENED || !onInner
                    val statusText = when {
                        posture == Posture.HALF_OPENED -> stringResource(R.string.status_flex_paused)
                        !onInner -> stringResource(R.string.status_cover_paused)
                        engageState is EngageState.Engaging -> stringResource(R.string.status_engaging)
                        engageState is EngageState.Engaged -> stringResource(R.string.status_engaged)
                        engageState is EngageState.Failed -> stringResource(R.string.status_failed)
                        else -> stringResource(R.string.status_idle)
                    }
                    val statusColor =
                        if (!paused && engageState is EngageState.Failed) colors.Warning else colors.OnSurface
                    Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColor)
                    if (!paused && engageState is EngageState.Failed) {
                        Text(
                            text = failReasonText(engageState.reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.Warning,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = ratio?.label ?: stringResource(R.string.ratio_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (engaged != null) {
                        Text(
                            text = stringResource(
                                R.string.engaged_ratio_fmt,
                                remember(engaged.achievedRatio) { formatRatio(engaged.achievedRatio) },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.Primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            // Side-by-side split: explain the hole-avoid mechanism instead of showing
            // a mismatched ratio with no context.
            if (engaged?.plan?.holeAvoidMode == true) {
                Text(
                    stringResource(R.string.letterbox_note_vertical),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.OnSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun FoldDiagram(
    engaged: Boolean,
    videoSide: PaneSide,
    axis: SplitAxis,
    videoFraction: Float,
    primary: Color,
    outline: Color,
    spacerColor: Color,
    holeColor: Color,
) {
    Canvas(Modifier.size(width = 86.dp, height = 76.dp)) {
        val corner = CornerRadius(10.dp.toPx())
        val stroke = Stroke(width = 2.dp.toPx())
        // screen outline
        drawRoundRect(color = outline, cornerRadius = corner, style = stroke)

        val inset = 3.dp.toPx()
        val inner = Size(size.width - inset * 2, size.height - inset * 2)
        val origin = Offset(inset, inset)

        if (engaged) {
            val frac = videoFraction.coerceIn(0.2f, 0.9f)
            val paneCorner = CornerRadius(7.dp.toPx())
            if (axis == SplitAxis.HORIZONTAL) {
                val videoH = inner.height * frac
                val videoTop = if (videoSide == PaneSide.FIRST) origin.y else origin.y + inner.height - videoH
                val spacerTop = if (videoSide == PaneSide.FIRST) origin.y + videoH else origin.y
                drawRoundRect(
                    color = spacerColor,
                    topLeft = Offset(origin.x, spacerTop),
                    size = Size(inner.width, inner.height - videoH),
                    cornerRadius = paneCorner,
                )
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(origin.x, videoTop),
                    size = Size(inner.width, videoH),
                    cornerRadius = paneCorner,
                )
            } else {
                val videoW = inner.width * frac
                val videoLeft = if (videoSide == PaneSide.FIRST) origin.x else origin.x + inner.width - videoW
                val spacerLeft = if (videoSide == PaneSide.FIRST) origin.x + videoW else origin.x
                drawRoundRect(
                    color = spacerColor,
                    topLeft = Offset(spacerLeft, origin.y),
                    size = Size(inner.width - videoW, inner.height),
                    cornerRadius = paneCorner,
                )
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(videoLeft, origin.y),
                    size = Size(videoW, inner.height),
                    cornerRadius = paneCorner,
                )
            }
        } else {
            drawRoundRect(
                color = primary.copy(alpha = 0.25f),
                topLeft = origin,
                size = inner,
                cornerRadius = CornerRadius(7.dp.toPx()),
            )
        }
        // camera hole (upper-right, like the Fold7 inner display)
        drawCircle(
            color = holeColor,
            radius = 3.dp.toPx(),
            center = Offset(size.width - 9.dp.toPx(), 8.dp.toPx()),
        )
    }
}
