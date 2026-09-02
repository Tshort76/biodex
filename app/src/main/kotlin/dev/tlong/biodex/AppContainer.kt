package dev.tlong.biodex

import android.content.Context
import coil3.ImageLoader
import dev.tlong.biodex.data.backup.AndroidBackupGateway
import dev.tlong.biodex.data.backup.BackupGateway
import dev.tlong.biodex.data.backup.BackupService
import dev.tlong.biodex.data.catalogue.AndroidAssetReader
import dev.tlong.biodex.data.catalogue.CatalogueImporter
import dev.tlong.biodex.data.catalogue.DukeIndex
import dev.tlong.biodex.data.catalogue.ImportOutcome
import dev.tlong.biodex.data.catalogue.RoomCatalogueStore
import dev.tlong.biodex.data.db.AppDatabase
import dev.tlong.biodex.data.net.GbifClient
import dev.tlong.biodex.data.net.JsonFetcher
import dev.tlong.biodex.data.net.OkHttpJsonFetcher
import dev.tlong.biodex.data.net.SpeciesLookupRepository
import dev.tlong.biodex.data.net.WikipediaClient
import dev.tlong.biodex.data.photo.AndroidPhotoGateway
import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.repo.AddSpeciesRegistrar
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.data.settings.AppSettings
import dev.tlong.biodex.media.AndroidNetworkMonitor
import dev.tlong.biodex.media.CacheManager
import dev.tlong.biodex.media.NetworkMonitor
import dev.tlong.biodex.media.buildImageLoader
import dev.tlong.biodex.ui.addspecies.AddSpeciesDraftHolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient

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

    /** The platform half of the photo layer (ARCHITECTURE.md 4). */
    val photoGateway: PhotoGateway by lazy { AndroidPhotoGateway(appContext) }

    /** S03 and the app's other preferences (4.5: plain SharedPreferences). */
    val settings: AppSettings by lazy { AppSettings(appContext) }

    /**
     * The core loop's write path. `keepLocalCopy` is S03's setting, read **on every
     * registration** rather than captured: flipping the switch has to affect the next photo,
     * not the next process.
     */
    val captureRegistrar: CaptureRegistrar by lazy {
        CaptureRegistrar(
            store = dexRepository,
            photos = photoGateway,
            keepLocalCopy = settings::keepLocalCopyNow,
        )
    }

    /**
     * ARCHITECTURE.md 5.2's single client. The User-Agent interceptor is not politeness:
     * Wikimedia rejects generic clients, so without it every reference image 403s.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(appContext.cacheDir, "http"), HTTP_CACHE_BYTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build(),
                )
            }
            .build()
    }

    /**
     * Media bytes bypass the 20 MB HTTP cache: images have Coil's own 250 MB disk cache, so
     * letting them also write here would evict slice 7's API responses for nothing.
     */
    private val mediaHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder().cache(null).build()
    }

    /** Installed as Coil's app-wide singleton by [App]; see 5.3. */
    val imageLoader: ImageLoader by lazy { buildImageLoader(appContext, mediaHttpClient) }

    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext) }

    /** Settings' cache management (5.3). */
    val cacheManager: CacheManager by lazy {
        CacheManager(context = appContext, imageLoader = { imageLoader })
    }

    // -----------------------------------------------------------------------
    // The user-added flow (slice 7, ARCHITECTURE.md 5.2 / M18–M21).
    // -----------------------------------------------------------------------

    /** The one platform seam of the network layer; everything above it is testable Kotlin. */
    private val jsonFetcher: JsonFetcher by lazy { OkHttpJsonFetcher(httpClient) }

    /**
     * The bundled Duke's index (11.2). It parses on first use and only a plant confirmation
     * card ever asks, so a session that never adds a plant never pays for it — and it needs an
     * `AssetReader`, which is the one thing in the lookup path that cannot be built without a
     * `Context`. That is why 11.6's "no new wiring" note does not hold for slice 12.
     */
    private val dukeIndex: DukeIndex by lazy { DukeIndex(AndroidAssetReader(appContext)) }

    val speciesLookupRepository: SpeciesLookupRepository by lazy {
        SpeciesLookupRepository(
            gbif = GbifClient(jsonFetcher),
            wikipedia = WikipediaClient(jsonFetcher),
            duke = dukeIndex,
        )
    }

    /** 6.1's Register→Confirm hand-off. In memory: a draft's whole life is two screens. */
    val addSpeciesDrafts: AddSpeciesDraftHolder by lazy { AddSpeciesDraftHolder() }

    val addSpeciesRegistrar: AddSpeciesRegistrar by lazy {
        AddSpeciesRegistrar(store = dexRepository, captures = captureRegistrar)
    }

    // -----------------------------------------------------------------------
    // Export and import (slice 8, S01).
    // -----------------------------------------------------------------------

    private val backupGateway: BackupGateway by lazy {
        AndroidBackupGateway(appContext, photoGateway)
    }

    val backupService: BackupService by lazy {
        BackupService(store = dexRepository, gateway = backupGateway)
    }

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

/** 5.2: enough to identify the app to Wikimedia and GBIF. */
private const val USER_AGENT = "BioDex/1.0 (personal Android app; https://github.com/Tshort76/biodex)"

/** 5.2: the API-lookup response cache. Media never writes here — see `mediaHttpClient`. */
private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024

/** Reaches the container from a composable: `LocalContext.current.appContainer`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
