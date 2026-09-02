package dev.tlong.animaldex

import android.content.Context

/**
 * Hand-wired singleton holder — the whole of this app's dependency injection
 * (ARCHITECTURE.md 1.2: no DI framework). Later slices add the database, the
 * OkHttp client, the repositories and the media caches as lazy properties here.
 */
class AppContainer(val appContext: Context)
