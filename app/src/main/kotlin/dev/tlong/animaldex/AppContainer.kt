package dev.tlong.animaldex

import android.content.Context
import dev.tlong.animaldex.data.catalogue.AndroidAssetReader
import dev.tlong.animaldex.data.catalogue.CatalogueImporter
import dev.tlong.animaldex.data.catalogue.ImportOutcome
import dev.tlong.animaldex.data.catalogue.RoomCatalogueStore
import dev.tlong.animaldex.data.db.AppDatabase
import dev.tlong.animaldex.data.repo.DexRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hand-wired singleton holder — the whole of this app's dependency injection
 * (ARCHITECTURE.md 1.2: no DI framework). Later slices add the OkHttp client, the
 * lookup repository and the media caches as further lazy properties here.
 */
class AppContainer(val appContext: Context) {

    /** Lives as long as the process; the catalogue import is its only user in slice 3. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val dexRepository: DexRepository by lazy { DexRepository(database) }

    private val catalogueImporter: CatalogueImporter by lazy {
        CatalogueImporter(
            assets = AndroidAssetReader(appContext),
            store = RoomCatalogueStore(database),
        )
    }

    private val _importOutcome = MutableStateFlow<ImportOutcome?>(null)

    /** Null until the first-run import finishes. Nothing blocks on it. */
    val importOutcome: StateFlow<ImportOutcome?> = _importOutcome.asStateFlow()

    /**
     * ARCHITECTURE.md 3.3: runs on `Application` start on a background dispatcher. The grid
     * shows its normal loading state meanwhile; an empty or failed import leaves the
     * database untouched rather than crashing.
     */
    fun startCatalogueImport() {
        applicationScope.launch {
            _importOutcome.value = catalogueImporter.import()
        }
    }
}

/** Reaches the container from a composable: `LocalContext.current.appContainer`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
