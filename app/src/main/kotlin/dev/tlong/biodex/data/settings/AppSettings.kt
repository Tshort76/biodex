package dev.tlong.biodex.data.settings

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
 *
 * **No API key lives anywhere any more** (D59). The Pl@ntNet key did live here, and nowhere
 * else, from v6 to v20 (M39, D24), for the reason that still stands: this repository is
 * public, and a build-time key is one careless `git add` from being published. The old
 * preference is removed by name on first construction below, never read.
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

    /**
     * D59. Pl@ntNet identification is gone, and so is its key. The four preferences it kept
     * are removed by name on first construction — the key was a secret the user pasted in,
     * and a secret with no reader left has no business surviving in `shared_prefs`. Never
     * read, never logged: `remove` is the only call.
     */
    private fun forgetIdentificationPrefs() {
        prefs.edit()
            .remove(RETIRED_KEY_PLANTNET)
            .remove(RETIRED_KEY_IDENTIFICATION_MONTH)
            .remove(RETIRED_KEY_IDENTIFICATION_USED)
            .remove(RETIRED_KEY_IDENTIFICATION_CAP)
            .apply()
    }

    init {
        forgetIdentificationPrefs()
    }

    // -----------------------------------------------------------------------
    // The grid's order (D47). Stored as the sort's wire name; this class deliberately does
    // not know the enum, which lives with the screen that uses it.
    // -----------------------------------------------------------------------

    private val _dexSort = MutableStateFlow(dexSortNow())

    /**
     * The grid collects this rather than reading the key once, so a change made on the
     * Settings screen reaches a grid that is already composed behind it.
     */
    val dexSort: StateFlow<String?> = _dexSort.asStateFlow()

    fun dexSortNow(): String? = prefs.getString(KEY_DEX_SORT, null)

    fun setDexSort(wireName: String) {
        prefs.edit().putString(KEY_DEX_SORT, wireName).apply()
        _dexSort.value = wireName
    }

    companion object {
        const val PREFS_NAME = "settings"

        /** D47: absent until the user picks an order; the grid's own default stands in. */
        const val KEY_DEX_SORT = "dex_sort"

        /** Default off: linking, not storing, is the point (DESIGN.md D6/S03). */
        const val KEY_KEEP_LOCAL_COPY = "keep_local_copy"

        // v6–v20's identification preferences, kept only so they can be removed (D59).
        private const val RETIRED_KEY_PLANTNET = "plantnet_api_key"
        private const val RETIRED_KEY_IDENTIFICATION_MONTH = "identification_month"
        private const val RETIRED_KEY_IDENTIFICATION_USED = "identification_used"
        private const val RETIRED_KEY_IDENTIFICATION_CAP = "identification_cap"
    }
}
