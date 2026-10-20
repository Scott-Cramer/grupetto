package com.spop.poverlay.calibration

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.spop.poverlay.sensor.BikeData
import com.spop.poverlay.sensor.v1new.SERVICE_ACTION

/** This client deliberately has no calibration-writing API. Only subscribe/unsubscribe/read. */
internal class CalibrationService(private val remote: IBinder) : AutoCloseable {
    @Volatile var frame: CalibrationFrame? = null
        private set
    @Volatile var failure: String? = null
        private set
    private var registered = false
    private var lastDecodedAt = Long.MIN_VALUE
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            return try {
                data.enforceInterface("com.onepeloton.affernetservice.IV1Callback")
                if (code != 1) {
                    frame = null
                    failure = "Bike reported a sensor error or calibration change. Start a new capture."
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (lastDecodedAt == Long.MIN_VALUE || now - lastDecodedAt >= 250) {
                        lastDecodedAt = now
                        frame = if (data.readInt() != 0) readCalibrationFrame(data, now) else null
                    }
                }
                true
            } catch (e: Exception) {
                frame = null
                failure = "Bike calibration telemetry could not be decoded. Collection stopped."
                true
            }
        }
    }
    fun start() {
        registration(1)
        registered = true
    }
    fun readTable(): List<Int> = readActiveLxx(remote)
    private fun registration(code: Int) {
        require(code == 1 || code == 2)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_ACTION)
            data.writeStrongBinder(callback)
            data.writeString("Grupetto calibration capture")
            check(remote.transact(code, data, reply, 0)) { "Bike rejected calibration subscription" }
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
    override fun close() {
        if (registered) {
            registered = false
            registration(2)
        }
        frame = null
    }
}

/** IV1Interface.getLxxValues is an OUT int[31], not an input array or returned parcelable. */
internal fun readActiveLxx(remote: IBinder): List<Int> {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(SERVICE_ACTION)
        data.writeInt(31)
        check(remote.transact(11, data, reply, 0)) { "Bike rejected calibration-table read" }
        reply.readException()
        val values = IntArray(31)
        reply.readIntArray(values)
        return values.toList().also { check(validCalibrationTable(it)) { "Bike table is not strictly increasing" } }
    } finally { data.recycle(); reply.recycle() }
}

/** Confirmed schema for Affernet 2.4.490 only. Read the existing fixed prefix, then
 * require the V3 parcelable to be null before accessing the V1 power source.
 * Do not guess field offsets on a future firmware. Ordinary ride telemetry is unaffected.
 */
internal fun readCalibrationFrame(data: Parcel, timestamp: Long): CalibrationFrame {
    val bike = BikeData.CREATOR.createFromParcel(data)
    check(data.readString() == null) { "Unexpected V3 telemetry" }
    val source = data.readInt()
    check(source in 0..2) { "Unknown power source" }
    val serial = bike.bikeFrameSerial?.trim().orEmpty()
    check(serial.isNotEmpty() && bike.adValue > 0) { "Missing bike identity or raw resistance" }
    return CalibrationFrame(timestamp, bike.adValue, bike.rpm.toDouble(), bike.power / 100.0, serial, source,
        bike.currentResistance)
}
