package com.displaysplitter.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import com.displaysplitter.App
import com.displaysplitter.overlay.OverlayService
import com.displaysplitter.split.DividerAccessibilityService
import com.displaysplitter.ui.theme.OneUiTheme

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(true)
    private var askedNotifications = false

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = App.from(this)
        setContent {
            OneUiTheme {
                HomeScreen(
                    settings = app.settings,
                    controller = app.controller,
                    overlayGranted = overlayGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    notificationsEnabled = notificationsEnabled,
                    onRequestOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    },
                    onRequestAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestNotifications = { requestNotifications() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityEnabled = DividerAccessibilityService.isEnabled(this)
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        // The FGS notification carries the only "Stop" affordance — ask once up front.
        if (!notificationsEnabled && !askedNotifications && Build.VERSION.SDK_INT >= 33) {
            askedNotifications = true
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (overlayGranted && accessibilityEnabled) {
            OverlayService.start(this)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) &&
            !askedNotifications
        ) {
            askedNotifications = true
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Permanently denied (or pre-33 channel block): the app's notification settings
        // page is the only reliable path.
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }
}
