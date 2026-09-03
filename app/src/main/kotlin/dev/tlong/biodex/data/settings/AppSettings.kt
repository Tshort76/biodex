package dev.tlong.biodex.data.settings

import android.content.Context
import dev.tlong.biodex.data.identify.DEFAULT_MONTHLY_IDENTIFICATION_CAP
import dev.tlong.biodex.data.identify.IdentificationCount
import dev.tlong.biodex.data.identify.currentMonthKey
import dev.tlong.biodex.data.identify.identificationsUsed
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
 * the container was built. [plantNetKeyNow] is read the same way for the same reason.
 *
 * **The Pl@ntNet key lives here and nowhere else** (M39, D24). This repository is public, and
 * ARCHITECTURE.md 5.4 records the `local.properties → BuildConfig` plumbing that once carried
 * the Xeno-canto key: a build-time key is one careless `git add` from being published, so the
 * only way a key enters this app is the user pasting it into Settings, into app-private
 * storage. Encrypting it at rest is deliberately not done — it is the same protection the
 * persisted photo grants get on the same single-user phone, and the threat a key faces here is
 * the repository, not the device.
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

    // -----------------------------------------------------------------------
    // Identification (M37, M39).
    // -----------------------------------------------------------------------

    private val _plantNetKey = MutableStateFlow(plantNetKeyNow())

    /** Null until the user pastes one; the feature ships dark until they do (R16). */
    val plantNetKey: StateFlow<String?> = _plantNetKey.asStateFlow()

    fun plantNetKeyNow(): String? =
        prefs.getString(KEY_PLANTNET_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setPlantNetKey(value: String?) {
        val cleaned = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_PLANTNET_KEY) else putString(KEY_PLANTNET_KEY, cleaned)
        }.apply()
        _plantNetKey.value = cleaned
    }

    private val _identificationsUsed = MutableStateFlow(identificationsUsedNow())

    val identificationsUsed: StateFlow<Int> = _identificationsUsed.asStateFlow()

    /**
     * The month's count, rolled over on read. Nothing runs while the app is closed, so the
     * turn of the month is noticed the first time anything asks (M37).
     */
    fun identificationsUsedNow(nowMillis: Long = System.currentTimeMillis()): Int =
        identificationsUsed(storedCount(), currentMonthKey(nowMillis))

    fun identificationCapNow(): Int =
        prefs.getInt(KEY_IDENTIFICATION_CAP, DEFAULT_MONTHLY_IDENTIFICATION_CAP)

    fun setIdentificationCap(cap: Int) {
        prefs.edit().putInt(KEY_IDENTIFICATION_CAP, cap.coerceAtLeast(0)).apply()
        _identificationsUsed.value = identificationsUsedNow()
    }

    /**
     * Counted on a **successful upload**, not on a press: an attempt that never reached the
     * service has not spent anything, and charging the user's cap for the app's own failure to
     * connect is the kind of quiet unfairness a hard cap makes expensive.
     */
    fun recordIdentification(nowMillis: Long = System.currentTimeMillis()) {
        val month = currentMonthKey(nowMillis)
        val used = identificationsUsed(storedCount(), month) + 1
        prefs.edit()
            .putString(KEY_IDENTIFICATION_MONTH, month)
            .putInt(KEY_IDENTIFICATION_USED, used)
            .apply()
        _identificationsUsed.value = used
    }

    private fun storedCount() = IdentificationCount(
        month = prefs.getString(KEY_IDENTIFICATION_MONTH, "").orEmpty(),
        used = prefs.getInt(KEY_IDENTIFICATION_USED, 0),
    )

    companion object {
        const val PREFS_NAME = "settings"

        /** Default off: linking, not storing, is the point (DESIGN.md D6/S03). */
        const val KEY_KEEP_LOCAL_COPY = "keep_local_copy"

        const val KEY_PLANTNET_KEY = "plantnet_api_key"
        const val KEY_IDENTIFICATION_MONTH = "identification_month"
        const val KEY_IDENTIFICATION_USED = "identification_used"
        const val KEY_IDENTIFICATION_CAP = "identification_cap"
    }
}
