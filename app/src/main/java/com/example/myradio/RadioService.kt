package com.example.myradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

@androidx.annotation.OptIn(UnstableApi::class)
class RadioService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var player: ExoPlayer
    private lateinit var tts: TextToSpeech
    @Volatile private var ttsReady = false
    private val speechLock = Mutex()
    @Volatile private var lastLoc: Location? = null
    private var started = false
    private var stations = listOf<Station>()
    private var idx = 0
    private var errorsInRow = 0
    private val alertedCams = mutableSetOf<Long>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "NEXT" -> { if (started) nextStation() else stopSelf(); return START_NOT_STICKY }
            "DEMO" -> { if (started) scope.launch { demo() } else stopSelf(); return START_NOT_STICKY }
        }
        if (started) return START_STICKY
        started = true
        startForegroundNow()

        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setUserAgent("MyRadioTest/1.0")
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorsInRow++
                if (errorsInRow < 10) nextStation()
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) errorsInRow = 0 }
        })

        tts = TextToSpeech(this) {
            tts.language = Locale("ru", "RU")
            ttsReady = true
        }
        startLocation()

        scope.launch {
            stations = withContext(Dispatchers.IO) { Radio.load() }
            if (stations.isEmpty()) { speak("Не нашёл радиостанции. Проверьте интернет."); return@launch }
            idx = Random.nextInt(minOf(stations.size, 8))
            playStation()
            delay(60_000)                       // первые новости через минуту, дальше каждые 8 минут
            while (true) { readNews(); delay(8 * 60_000L) }
        }
        scope.launch { sensorLoop() }
        return START_STICKY
    }

    private fun playStation() {
        if (stations.isEmpty()) return
        player.setMediaItem(MediaItem.fromUri(stations[idx].url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun nextStation() {
        if (stations.isEmpty()) return
        idx = (idx + 1) % stations.size
        playStation()
    }

    private suspend fun readNews() {
        val items = withContext(Dispatchers.IO) { News.headlines() }.take(6)
        if (items.isNotEmpty()) speak("Новости. " + items.joinToString(". "))
    }

    private suspend fun demo() {
        speak("Тест голоса. Впереди камера контроля скорости, четыреста метров. Ограничение шестьдесят.")
        speak("Похоже, вы стоите в пробке.")
        readNews()
    }

    // Камеры по OpenStreetMap + «пробка» по вашему собственному движению
    private suspend fun sensorLoop() {
        var lastFast = 0L; var slowSince = 0L; var lastJam = 0L; var lastCamCheck = 0L
        while (true) {
            delay(5_000)
            val loc = lastLoc ?: continue
            val now = System.currentTimeMillis()
            val kmh = loc.speed * 3.6f
            if (kmh > 30) { lastFast = now; slowSince = 0 }
            else if (kmh < 8) { if (slowSince == 0L) slowSince = now }
            else slowSince = 0

            if (slowSince != 0L && now - slowSince > 90_000 && now - lastFast < 300_000 && now - lastJam > 600_000) {
                lastJam = now
                speak("Похоже, вы стоите в пробке.")
            }

            if (kmh > 10 && loc.hasBearing() && now - lastCamCheck > 20_000) {
                lastCamCheck = now
                val cams = withContext(Dispatchers.IO) { Cameras.near(loc.latitude, loc.longitude, 900) }
                for (c in cams) {
                    if (c.id in alertedCams) continue
                    val cl = Location("").apply { latitude = c.lat; longitude = c.lon }
                    val diff = ((loc.bearingTo(cl) - loc.bearing) % 360 + 540) % 360 - 180
                    if (abs(diff) < 45) {
                        alertedCams += c.id
                        val d = (loc.distanceTo(cl) / 50).toInt() * 50
                        speak("Впереди камера контроля скорости, $d метров." +
                              (c.maxspeed?.let { " Ограничение $it." } ?: ""))
                        break
                    }
                }
            }
        }
    }

    // Голос: радио приглушается, не останавливается
    private suspend fun speak(text: String) = speechLock.withLock {
        while (!ttsReady) delay(200)
        player.volume = 0.15f
        suspendCancellableCoroutine<Unit> { c ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (c.isActive) c.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { if (c.isActive) c.resume(Unit) }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
            c.invokeOnCancellation { tts.stop() }
        }
        player.volume = 1f
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()
        LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
            req, object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) { lastLoc = r.lastLocation }
            }, Looper.getMainLooper())
    }

    private fun startForegroundNow() {
        val ch = "radio"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Радио", NotificationManager.IMPORTANCE_LOW))
        fun pi(a: String) = PendingIntent.getService(
            this, a.hashCode(), Intent(this, RadioService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("Моё радио играет")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(0, "Далее", pi("NEXT"))
            .addAction(0, "Стоп", pi("STOP"))
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        startForeground(1, n, type)
    }

    override fun onDestroy() {
        scope.cancel()
        if (::player.isInitialized) player.release()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}
