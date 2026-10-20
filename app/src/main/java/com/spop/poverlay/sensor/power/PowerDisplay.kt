package com.spop.poverlay.sensor.power

enum class DisplayPowerSource(val label: String) { Bike("Power"), Pedals("Pedals"), Corrected("Corrected") }

/** Display-only choice. Transport/control watts remain on SensorInterface.power. */
data class PowerDisplay(val watts: Float, val source: DisplayPowerSource = DisplayPowerSource.Bike,
    val nativeWatts: Float = watts, val pedalWatts: Float? = null) {
    val comparison: String? get() = when {
        source == DisplayPowerSource.Corrected && pedalWatts != null -> "Pedals %.0f W".format(pedalWatts)
        source == DisplayPowerSource.Corrected || source == DisplayPowerSource.Pedals -> "Bike %.0f W".format(nativeWatts)
        else -> null
    }
}

internal fun selectPowerDisplay(bike: PowerDisplay, pedals: Float?): PowerDisplay = when {
    bike.source == DisplayPowerSource.Corrected -> bike.copy(pedalWatts = pedals)
    pedals != null -> PowerDisplay(pedals, DisplayPowerSource.Pedals, bike.nativeWatts, pedals)
    else -> bike.copy(pedalWatts = null)
}
