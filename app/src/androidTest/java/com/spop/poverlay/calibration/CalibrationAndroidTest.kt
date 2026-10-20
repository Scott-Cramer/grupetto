package com.spop.poverlay.calibration

import android.os.Binder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CalibrationAndroidTest {
    /** Fixture follows the observed 2.4.490 wire layout, including variable-length fields. */
    private fun frameParcel(v3: String? = null, source: Int = 1): Parcel = Parcel.obtain().apply {
        writeLong(82); writeLong(23456); writeLong(0); writeLong(0)
        writeInt(42); writeInt(42)
        writeString("firmware"); writeByteArray(byteArrayOf(1, 2, 3)); writeString("time")
        repeat(7) { writeInt(0) } // start, end, calibration, encoder, system, error index, error 1
        writeString("first error time")
        repeat(4) { writeInt(0); writeString(null) }
        writeIntArray(IntArray(15)); writeString("load table"); writeInt(0)
        writeString("p"); writeString("q"); writeString("TEST_FRAME_0001"); writeString("load serial")
        writeFloat(0f); writeInt(0); writeString("date"); writeString("time")
        repeat(3) { writeInt(0) }
        writeFloat(0f); writeInt(0); writeInt(0); writeFloat(0f); writeString("load version")
        writeInt(0); writeInt(0); writeString("hardware"); writeInt(2345)
        writeInt(0); writeInt(0); writeFloat(0f); repeat(5) { writeInt(0) }
        writeString(v3); writeInt(source); writeInt(0); writeInt(0)
        setDataPosition(0)
    }
    @Test fun decodesRawSensorAndSourceWithoutConfusingDisplayedResistance() {
        val p = frameParcel()
        try {
            val frame = readCalibrationFrame(p, 1234)
            assertEquals(2345, frame.ad)
            assertEquals(234.56, frame.nativeWatts, .001)
            assertEquals(82.0, frame.rpm, 0.0)
            assertEquals(42, frame.resistance)
            assertEquals("TEST_FRAME_0001", frame.frameSerial)
            assertEquals(1, frame.powerSource)
            assertEquals(1234L, frame.timestamp)
        } finally { p.recycle() }
    }
    @Test fun unexpectedParcelableAndUnknownSourcesFailClosed() {
        listOf(frameParcel("unknown.V3BikeData"), frameParcel(source = 99)).forEach { p ->
            try { assertTrue(runCatching { readCalibrationFrame(p, 1) }.isFailure) }
            finally { p.recycle() }
        }
    }
    @Test fun realBinderUsesOnlySubscribeReadUnsubscribe() {
        val transactions = mutableListOf<Int>()
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                transactions += code
                data.enforceInterface("com.onepeloton.affernetservice.IV1Interface")
                when (code) {
                    1, 2 -> { assertNotNull(data.readStrongBinder()); assertEquals("Grupetto calibration capture", data.readString()) }
                    11 -> assertEquals(31, data.readInt())
                    else -> fail("Calibration must not issue transaction $code")
                }
                reply!!.writeNoException()
                if (code == 11) reply.writeIntArray(IntArray(31) { 1000 + it * 100 })
                return true
            }
        }
        CalibrationService(remote).use { service -> service.start(); assertEquals(31, service.readTable().size) }
        assertEquals(listOf(1, 11, 2), transactions)
    }
    @Test fun atomicCaptureRoundTripsAndClearDoesNotTouchOtherFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "calibration-test-${System.nanoTime()}").apply { mkdirs() }
        val store = CalibrationStore(directory)
        val baseline = List(31) { 1000 + it * 100 }
        val sample = CalibrationSample(2000.0, 80.0, 200.0, 180.0, 12345)
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, listOf(sample))
        try {
            assertNull(store.load())
            store.save(capture, CalibrationFitter.fit(baseline, capture.samples))
            assertEquals(capture, store.load())
            val saved = File(directory, "bike-power-calibration.json")
            assertTrue(saved.renameTo(File(directory, "bike-power-calibration.json.bak")))
            assertEquals(capture, store.load()) // recover an interrupted legacy AtomicFile commit
            store.clear()
            assertNull(store.load())
        } finally { directory.deleteRecursively() }
    }
}
