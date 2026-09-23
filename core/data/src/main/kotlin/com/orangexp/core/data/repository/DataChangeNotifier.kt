package com.orangexp.core.data.repository

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Something outside the app's screens that shows stored data and must refresh
 * when it changes, such as home-screen widgets. Contributed with `@IntoSet`.
 */
interface DataChangeListener {
    suspend fun onDataChanged()
}

/** Fans a change out to every registered [DataChangeListener]; a failing listener never breaks the caller. */
@Singleton
class DataChangeNotifier @Inject constructor(
    private val listeners: Set<@JvmSuppressWildcards DataChangeListener>,
) {
    suspend fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener.onDataChanged()
            } catch (e: Exception) {
                Log.w("DataChangeNotifier", "Listener failed", e)
            }
        }
    }
}
