package com.spop.poverlay.rides

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Records every ride second-by-second and uploads it to the ride site (ride.teamcramer.com).
 *
 * A ride starts the first second the pedals turn and ends after [IdleEndSeconds] of no
 * pedaling (trailing idle is trimmed). Rides with less than [MinActiveSeconds] of pedaling
 * are dropped. The in-progress ride is checkpointed to disk every 30 s so a crash or a
 * reboot mid-ride doesn't lose it; finished rides queue in filesDir/rides/ and are deleted
 * only after the server accepts them, so a Wi-Fi drop just delays the upload.
 *
 * Each sample row is [power W, cadence rpm, resistance %, speed mph, heart rate bpm or -1].
 */
class RideRecorder(
    context: Context,
    private val sensors: SensorInterface,
    private val uploadEnabled: Boolean = true,
) {
    companion object {
        const val IdleEndSeconds = 120
        const val MinActiveSeconds = 180
        private const val CheckpointEverySeconds = 30
        private const val UploadRetryMs = 5 * 60_000L
        const val PrefsName = "ride_upload"
        const val DefaultUrl = "https://ride.teamcramer.com/api/rides"
        val DefaultToken get() = RideConfig.Token
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(app.filesDir, "rides").apply { mkdirs() }
    private val currentFile = File(dir, "current.json")
    private var job: Job? = null

    private class Ride(val id: String, val start: Long) {
        val rows = ArrayList<FloatArray>(4000)
        var lastActiveIndex = -1
        var idleSeconds = 0
        var activeSeconds = 0
    }

    private var ride: Ride? = null

    fun start() {
        if (job != null) return
        recoverCheckpoint()
        job = scope.launch {
            val power = sensors.power.latest(0f)
            val cadence = sensors.cadence.latest(0f)
            val resistance = sensors.resistance.latest(0f)
            val speed = sensors.speed.latest(0f)
            val hr: StateFlow<Int?> = HeartRateManager.heartRate
            var tick = 0L
            var next = System.currentTimeMillis()
            while (isActive) {
                next += 1000
                delay((next - System.currentTimeMillis()).coerceAtLeast(0))
                try {
                    sample(power.value, cadence.value, resistance.value, speed.value, hr.value)
                } catch (e: Exception) {
                    Timber.w(e, "ride sample failed")
                }
                if (++tick % CheckpointEverySeconds == 0L) checkpoint()
            }
        }
        scope.launch {
            while (isActive) {
                uploadPending()
                delay(UploadRetryMs)
            }
        }
    }

    private fun Flow<Float>.latest(initial: Float) =
        stateIn(scope, SharingStarted.Eagerly, initial)

    @Synchronized
    private fun sample(p: Float, c: Float, r: Float, s: Float, h: Int?) {
        val pedaling = c >= 1f || p >= 1f
        var cur = ride
        if (cur == null) {
            if (!pedaling) return
            cur = Ride(UUID.randomUUID().toString(), System.currentTimeMillis())
            ride = cur
            Timber.i("ride started ${cur.id}")
        }
        cur.rows.add(floatArrayOf(p, c, r, s, (h ?: -1).toFloat()))
        if (pedaling) {
            cur.lastActiveIndex = cur.rows.size - 1
            cur.idleSeconds = 0
            cur.activeSeconds++
        } else if (++cur.idleSeconds >= IdleEndSeconds) {
            finish()
        }
    }

    /** End the current ride now (also called when the idle timeout passes). */
    @Synchronized
    fun finish() {
        val cur = ride ?: return
        ride = null
        currentFile.delete()
        val keep = cur.lastActiveIndex + 1
        if (cur.activeSeconds < MinActiveSeconds || keep <= 0) {
            Timber.i("ride ${cur.id} dropped: only ${cur.activeSeconds}s of pedaling")
            return
        }
        val json = toJson(cur, keep, final = true)
        File(dir, "ride-${cur.id}.json").writeText(json.toString())
        val mins = keep / 60
        val kj = cur.rows.take(keep).sumOf { it[0].toDouble() } / 1000.0
        toast("Ride saved: $mins min, ${kj.toInt()} kJ")
        scope.launch { uploadPending() }
    }

    private fun toJson(cur: Ride, count: Int, final: Boolean): JSONObject {
        val data = JSONArray()
        for (i in 0 until count) {
            val row = cur.rows[i]
            data.put(JSONArray().apply {
                put(Math.round(row[0]))          // watts
                put(Math.round(row[1]))          // rpm
                put(Math.round(row[2]))          // resistance
                put(Math.round(row[3] * 10) / 10.0) // mph
                put(Math.round(row[4]))          // bpm (-1 = none)
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("id", cur.id)
            put("start", cur.start)
            put("end", cur.start + count * 1000L)
            put("interval", 1)
            put("fields", JSONArray(listOf("p", "c", "r", "s", "h")))
            put("data", data)
            put("final", final)
            put("source", "grupetto")
            put("device", Build.MODEL)
        }
    }

    @Synchronized
    private fun checkpoint() {
        val cur = ride ?: return
        try {
            val j = toJson(cur, cur.rows.size, final = false)
            j.put("active", cur.activeSeconds)
            j.put("lastActive", cur.lastActiveIndex)
            currentFile.writeText(j.toString())
        } catch (e: Exception) {
            Timber.w(e, "ride checkpoint failed")
        }
    }

    /** A checkpoint left from a crash/reboot becomes a finished ride (trimmed at last pedal stroke). */
    private fun recoverCheckpoint() {
        if (!currentFile.exists()) return
        try {
            val j = JSONObject(currentFile.readText())
            val data = j.getJSONArray("data")
            val cur = Ride(j.getString("id"), j.getLong("start"))
            for (i in 0 until data.length()) {
                val a = data.getJSONArray(i)
                cur.rows.add(FloatArray(5) { k -> a.getDouble(k).toFloat() })
            }
            cur.activeSeconds = j.optInt("active", data.length())
            cur.lastActiveIndex = j.optInt("lastActive", data.length() - 1)
            ride = cur
            finish()
        } catch (e: Exception) {
            Timber.w(e, "ride checkpoint unreadable; discarding")
            currentFile.delete()
        }
    }

    private fun prefs() = app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun uploadPending() {
        if (!uploadEnabled) return
        val url = prefs().getString("url", DefaultUrl) ?: DefaultUrl
        val token = prefs().getString("token", DefaultToken) ?: DefaultToken
        val files = dir.listFiles { f -> f.name.startsWith("ride-") && f.name.endsWith(".json") } ?: return
        for (f in files.sortedBy { it.name }) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(f.readBytes()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299 || code == 409) {
                    f.delete()
                    Timber.i("ride uploaded ${f.name}")
                    toast("Ride synced to ride.teamcramer.com")
                } else {
                    Timber.w("ride upload ${f.name} -> HTTP $code")
                    if (code in 400..499 && code != 401 && code != 429) f.renameTo(File(dir, "rejected-${f.name}"))
                }
            } catch (e: Exception) {
                Timber.w(e, "ride upload failed; will retry")
                return
            }
        }
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }
}
