package com.spop.poverlay.rides

import com.spop.poverlay.BuildConfig

/** Upload secret for ride.teamcramer.com, injected from ride.properties at build time (never committed). */
object RideConfig {
    val Token: String get() = BuildConfig.RIDE_TOKEN
}
