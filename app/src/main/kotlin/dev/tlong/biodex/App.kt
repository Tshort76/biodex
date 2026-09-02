package dev.tlong.biodex

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

/**
 * Implementing [SingletonImageLoader.Factory] is what makes every existing `AsyncImage` call
 * site — the grid cell, the photo strip, the reveal — use the configured loader rather than
 * Coil's default. Without it the disk cache S02 depends on would exist but never be consulted.
 */
class App : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startCatalogueImport()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
