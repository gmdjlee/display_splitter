package com.displaysplitter.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import com.displaysplitter.geometry.AspectRatio
import com.displaysplitter.geometry.PositionPref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Hard cap on the spacer memo, enforced at write time and surfaced by the memo UI —
 *  stops an unbounded text field from bloating the whole settings DataStore file. */
const val SPACER_MEMO_MAX_CHARS = 4000

data class TargetApp(val packageName: String, val labelFallback: String)

/** Preinstalled row entries for the settings screen; users can toggle each. */
val KNOWN_VIDEO_APPS = listOf(
    TargetApp("com.google.android.youtube", "YouTube"),
    TargetApp("com.netflix.mediaclient", "Netflix"),
    TargetApp("com.amazon.avod.thirdpartyclient", "Prime Video"),
    TargetApp("com.disney.disneyplus", "Disney+"),
    TargetApp("net.cj.cjhv.gs.tving", "TVING"),
    TargetApp("com.coupang.mobile.play", "Coupang Play"),
    TargetApp("com.frograms.wavve", "Wavve"),
)

data class SettingsState(
    val ratio: AspectRatio?,               // null = off
    val positionPref: PositionPref,
    val bubbleEnabled: Boolean,
    val bubbleOpacity: Float,              // 0.2f..1f
    val autoReengage: Boolean,
    val enabledApps: Set<String>,
    val bubbleX: Int,                      // saved bubble position, -1 = default
    val bubbleY: Int,
) {
    companion object {
        val DEFAULT = SettingsState(
            ratio = AspectRatio.R16_9,
            positionPref = PositionPref.AUTO,
            bubbleEnabled = true,
            bubbleOpacity = 0.85f,
            autoReengage = true,
            enabledApps = setOf("com.google.android.youtube", "com.netflix.mediaclient"),
            bubbleX = -1,
            bubbleY = -1,
        )
    }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val RATIO_W = intPreferencesKey("ratio_w")
        val RATIO_H = intPreferencesKey("ratio_h")
        val POSITION = stringPreferencesKey("position")
        val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble_opacity")
        val AUTO_REENGAGE = booleanPreferencesKey("auto_reengage")
        val ENABLED_APPS = stringSetPreferencesKey("enabled_apps")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
        val SPACER_WIDGET = stringPreferencesKey("spacer_widget")
        val SPACER_MEMO = stringPreferencesKey("spacer_memo")
    }

    // ---- spacer widget state -----------------------------------------------------------------
    // Kept OUT of SettingsState on purpose: the memo re-saves on every debounced
    // keystroke, and routing it through the app-wide state would re-emit settings.state
    // (recombining bubbleVisible and the ratio observer) per keystroke for a value only
    // the spacer window reads. Raw string out; SpacerWidgetMode.fromStorage maps it at
    // the call site so this layer stays free of UI enums.

    val spacerWidgetMode: Flow<String?> = context.dataStore.data.map { it[Keys.SPACER_WIDGET] }

    val spacerMemo: Flow<String> = context.dataStore.data.map { it[Keys.SPACER_MEMO] ?: "" }

    suspend fun setSpacerWidgetMode(mode: String) = context.dataStore.edit {
        it[Keys.SPACER_WIDGET] = mode
    }

    /** True only when the write actually committed — the memo's save indicator reports
     *  SAVED/FAILED honestly from this. NonCancellable: the debounce launch lives in the
     *  spacer window's composition scope, which dies with the window; a write that has
     *  STARTED must not be killed mid-commit (the ON_PAUSE flush only guarantees the
     *  write begins, completion is guaranteed here). */
    suspend fun saveSpacerMemo(text: String): Boolean = withContext(NonCancellable) {
        runCatching {
            context.dataStore.edit { it[Keys.SPACER_MEMO] = text.take(SPACER_MEMO_MAX_CHARS) }
        }.onFailure {
            Log.w("DisplaySplitter", "saveSpacerMemo failed", it)
        }.isSuccess
    }

    val state: StateFlow<SettingsState> = context.dataStore.data
        .map { p ->
            val d = SettingsState.DEFAULT
            val w = p[Keys.RATIO_W] ?: d.ratio!!.w
            val h = p[Keys.RATIO_H] ?: d.ratio!!.h
            SettingsState(
                ratio = if (w <= 0 || h <= 0) null else AspectRatio(w, h),
                positionPref = runCatching { PositionPref.valueOf(p[Keys.POSITION] ?: "") }
                    .getOrDefault(d.positionPref),
                bubbleEnabled = p[Keys.BUBBLE_ENABLED] ?: d.bubbleEnabled,
                bubbleOpacity = (p[Keys.BUBBLE_OPACITY] ?: d.bubbleOpacity).coerceIn(0.2f, 1f),
                autoReengage = p[Keys.AUTO_REENGAGE] ?: d.autoReengage,
                enabledApps = p[Keys.ENABLED_APPS] ?: d.enabledApps,
                bubbleX = p[Keys.BUBBLE_X] ?: d.bubbleX,
                bubbleY = p[Keys.BUBBLE_Y] ?: d.bubbleY,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, SettingsState.DEFAULT)

    suspend fun setRatio(ratio: AspectRatio?) = context.dataStore.edit {
        it[Keys.RATIO_W] = ratio?.w ?: 0
        it[Keys.RATIO_H] = ratio?.h ?: 0
    }

    suspend fun setPositionPref(pref: PositionPref) = context.dataStore.edit {
        it[Keys.POSITION] = pref.name
    }

    suspend fun setBubbleEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.BUBBLE_ENABLED] = enabled
    }

    suspend fun setBubbleOpacity(opacity: Float) = context.dataStore.edit {
        it[Keys.BUBBLE_OPACITY] = opacity.coerceIn(0.2f, 1f)
    }

    suspend fun setAutoReengage(enabled: Boolean) = context.dataStore.edit {
        it[Keys.AUTO_REENGAGE] = enabled
    }

    suspend fun setAppEnabled(packageName: String, enabled: Boolean) = context.dataStore.edit {
        val current = it[Keys.ENABLED_APPS] ?: SettingsState.DEFAULT.enabledApps
        it[Keys.ENABLED_APPS] = if (enabled) current + packageName else current - packageName
    }

    suspend fun setBubblePosition(x: Int, y: Int) = context.dataStore.edit {
        it[Keys.BUBBLE_X] = x
        it[Keys.BUBBLE_Y] = y
    }
}
