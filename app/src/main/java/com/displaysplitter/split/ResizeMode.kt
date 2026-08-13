package com.displaysplitter.split

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Detects whether a package declares all its activities unresizeable — those apps
 * (Netflix is the measured example) cannot enter split-select via the Recents card
 * drag (One UI routes the drop to a pop-up window instead), so the MENU entry recipe
 * is their only path.
 *
 * Ported from FoldWindow (device facts, Fold7 / One UI 8 / targetSdk 36):
 * - Reading the instance field `privateFlags` via reflection is hiddenapi-allowed.
 * - Reading the CONSTANT `PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE` is
 *   hiddenapi-denied (`max-target-o`) — so the bit value is hardcoded as a fallback,
 *   cross-checked against dumpsys flag names: 1 shl 11 = ACTIVITIES_RESIZE_MODE_UNRESIZEABLE.
 */
object ResizeMode {

    private const val TAG = "DisplaySplitter"
    private const val FALLBACK_UNRESIZEABLE_BIT = 1 shl 11

    /**
     * @return true = unresizeable confirmed, false = resizeable confirmed,
     *   null = undecidable (reflection/package failure) — callers fall back to DRAG.
     */
    @Suppress("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    fun isActivitiesUnresizeable(pm: PackageManager, packageName: String): Boolean? =
        runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val privateFlags = ApplicationInfo::class.java
                .getDeclaredField("privateFlags")
                .apply { isAccessible = true }
                .getInt(appInfo)
            val bit = runCatching {
                ApplicationInfo::class.java
                    .getDeclaredField("PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE")
                    .apply { isAccessible = true }
                    .getInt(null)
            }.getOrDefault(FALLBACK_UNRESIZEABLE_BIT)
            val unresizeable = (privateFlags and bit) != 0
            Log.i(TAG, "ResizeMode: $packageName privateFlags=0x${privateFlags.toString(16)} unresizeable=$unresizeable")
            unresizeable
        }.onFailure {
            Log.w(TAG, "ResizeMode: lookup failed for $packageName — caller falls back to DRAG", it)
        }.getOrNull()
}
