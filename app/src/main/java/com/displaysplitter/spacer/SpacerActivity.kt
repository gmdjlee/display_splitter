package com.displaysplitter.spacer

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.displaysplitter.ui.AppIcons
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.displaysplitter.App
import com.displaysplitter.R
import com.displaysplitter.split.DividerAccessibilityService
import com.displaysplitter.split.EngageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pure-black companion pane. It exists only to occupy split-screen space:
 * visually it reads as bezel. A tap reveals two quiet actions for a few seconds.
 */
class SpacerActivity : ComponentActivity() {

    private val controller get() = App.from(this).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zero cover-screen footprint: if we are ever (re)created off the inner
        // display — process-death restore on the cover screen — vanish immediately.
        if (resources.configuration.smallestScreenWidthDp <
            DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP
        ) {
            finishAndRemoveTask()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // The controller's state is the single authority: whenever it is not
        // engaged/engaging (disengage, failure, service loss, process-death restore
        // against a fresh Idle controller), this window has no reason to exist.
        // A StateFlow makes late subscription safe — nothing can be missed.
        lifecycleScope.launch {
            controller.state.collect { st ->
                if (st is EngageState.Idle || st is EngageState.Failed) finishAndRemoveTask()
            }
        }

        setContent {
            SpacerContent(
                onFlip = { controller.flipVideoSide() },
                onExit = { controller.disengage() },
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding the device shut: the cover screen is none of our business. Vanish silently.
        // We know foldedShut from newConfig directly — never from the service's possibly
        // stale configuration.
        if (newConfig.smallestScreenWidthDp < DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP) {
            controller.onSpacerStopped(foldedShut = true)
            finishAndRemoveTask()
            return
        }
        controller.onSpacerBoundsChanged()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        // The split was dismissed (divider flung to the edge, app-pair closed, or the
        // device was folded shut and the split dissolved on the way to the cover
        // screen). foldedShut comes from newConfig — callback ordering between this
        // and onConfigurationChanged during a fold is not guaranteed.
        if (!isInMultiWindowMode) {
            controller.onSpacerStopped(
                foldedShut = newConfig.smallestScreenWidthDp <
                    DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP
            )
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        // A pure configuration recreation (locale, font scale) must not tear down a
        // live engagement — the recreated instance re-binds to controller.state.
        if (!isChangingConfigurations) {
            controller.onSpacerStopped(
                foldedShut = resources.configuration.smallestScreenWidthDp <
                    DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP
            )
        }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@androidx.compose.runtime.Composable
private fun SpacerContent(onFlip: () -> Unit, onExit: () -> Unit) {
    var controlsVisible by remember { mutableStateOf(false) }

    // Auto-hide the controls shortly after they are revealed.
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(320)),
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF17171B),
                contentColor = Color.White,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onFlip) {
                        Icon(
                            AppIcons.Swap,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.spacer_flip),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    TextButton(onClick = onExit) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.spacer_exit),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
