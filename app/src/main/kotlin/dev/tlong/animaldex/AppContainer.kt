package dev.tlong.animaldex

import android.content.Context
import coil3.ImageLoader
import dev.tlong.animaldex.data.catalogue.AndroidAssetReader
import dev.tlong.animaldex.data.catalogue.CatalogueImporter
import dev.tlong.animaldex.data.catalogue.ImportOutcome
import dev.tlong.animaldex.data.catalogue.RoomCatalogueStore
import dev.tlong.animaldex.data.db.AppDatabase
import dev.tlong.animaldex.data.photo.AndroidPhotoGateway
import dev.tlong.animaldex.data.photo.CaptureRegistrar
import dev.tlong.animaldex.data.photo.PhotoGateway
import dev.tlong.animaldex.data.repo.DexRepository
import dev.tlong.animaldex.media.AndroidNetworkMonitor
import dev.tlong.animaldex.media.CallPlayer
import dev.tlong.animaldex.media.ExoCallPlayer
import dev.tlong.animaldex.media.NetworkMonitor
import dev.tlong.animaldex.media.buildAudioCache
import dev.tlong.animaldex.media.buildImageLoader
import dev.tlong.animaldex.media.callDataSourceFactory
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

    /**
     * The core loop's write path. `keepLocalCopy` is S03's setting, hard-wired off here:
     * slice 8 replaces the lambda with a `SharedPreferences` read (4.5) and nothing else in
     * the registration path changes.
     */
    val captureRegistrar: CaptureRegistrar by lazy {
        CaptureRegistrar(
            store = dexRepository,
            photos = photoGateway,
            keepLocalCopy = { false },
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
     * Media bytes bypass the 20 MB HTTP cache: images have Coil's 250 MB disk cache and audio
     * has `SimpleCache`, so letting them also write here would evict slice 7's API responses
     * for nothing.
     */
    private val mediaHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder().cache(null).build()
    }

    /** Installed as Coil's app-wide singleton by [App]; see 5.3. */
    val imageLoader: ImageLoader by lazy { buildImageLoader(appContext, mediaHttpClient) }

    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext) }

    /** One per process and per directory — a second one over the same folder throws. */
    private val audioCache by lazy { buildAudioCache(appContext) }

    /**
     * One call plays at a time, app-wide (M06). Nothing exercises this today: every `callUrl`
     * in the shipped catalogue is null for want of a Xeno-canto key (5.4), and the row stays
     * in its disabled state until the pipeline fills them in.
     */
    val callPlayer: CallPlayer by lazy {
        ExoCallPlayer(appContext, callDataSourceFactory(audioCache, mediaHttpClient))
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

/** 5.2: enough to identify the app to Wikimedia, GBIF and Xeno-canto. */
private const val USER_AGENT = "AnimalDex/1.0 (personal Android app; tlong@unified.health)"

/** 5.2: the API-lookup response cache. Media never writes here — see `mediaHttpClient`. */
private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024

/** Reaches the container from a composable: `LocalContext.current.appContainer`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
