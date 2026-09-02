package dev.tlong.animaldex.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * S03 and anything else that is a handful of booleans (ARCHITECTURE.md 4.5: plain
 * `SharedPreferences`, deliberately not DataStore).
 *
 * [keepLocalCopyNow] is what `AppContainer` hands `CaptureRegistrar`, and it reads the
 * preference on every call rather than closing over a value — a registration that happens
 * after the user flips the switch must see the new setting, not the one that was true when
 * the container was built.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _keepLocalCopy = MutableStateFlow(keepLocalCopyNow())

    /** For the Settings screen's switch. */
    val keepLocalCopy: StateFlow<Boolean> = _keepLocalCopy.asStateFlow()

    fun keepLocalCopyNow(): Boolean = prefs.getBoolean(KEY_KEEP_LOCAL_COPY, false)

    fun setKeepLocalCopy(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_LOCAL_COPY, enabled).apply()
        _keepLocalCopy.value = enabled
    }

    companion object {
        const val PREFS_NAME = "settings"

        /** Default off: linking, not storing, is the point (DESIGN.md D6/S03). */
        const val KEY_KEEP_LOCAL_COPY = "keep_local_copy"
    }
}
