package tech.amditis.flack

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * Watches whether Android Auto is projecting from this phone, using the Car App
 * Library's documented CarConnection. Android Automotive OS (a car with Android
 * built in) is not a target, so it counts as not connected.
 */
class CarSession(context: Context, private val onChange: (Boolean) -> Unit) {
    private val type: LiveData<Int> = CarConnection(context.applicationContext).type
    private val observer = Observer<Int> { onChange(it == CarConnection.CONNECTION_TYPE_PROJECTION) }

    /** Must be called on the main thread. */
    fun start() = type.observeForever(observer)

    fun stop() = type.removeObserver(observer)
}
