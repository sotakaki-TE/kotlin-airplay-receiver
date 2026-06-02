package io.carmo.airplay.receiver

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.carmo.airplay.receiver.model.NALPacket
import io.carmo.airplay.receiver.model.PCMPacket
import io.carmo.airplay.receiver.player.AudioPlayer
import io.carmo.airplay.receiver.player.VideoPlayer
import java.nio.ByteBuffer

class RaopServer(
    private val surfaceView: SurfaceView,
    private val onConnectionStarted: () -> Unit,
    private val onVideoActivity: (Boolean) -> Unit,
    private val onTrafficSample: (Int) -> Unit,
    private val onLatencySample: (Long) -> Unit,
    private val onStreamStoppedCallback: () -> Unit,
    private val onStreamStatusChanged: (String) -> Unit,
    initialVideoWidth: Int,
    initialVideoHeight: Int,
    initialAudioVolume: Float
) : SurfaceHolder.Callback {

    @Volatile private var videoPlayer: VideoPlayer? = null
    private var audioPlayer: AudioPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverId: Long = 0
    @Volatile private var lastMediaPacketAtMs = 0L
    @Volatile private var videoWidth = initialVideoWidth
    @Volatile private var videoHeight = initialVideoHeight
    @Volatile private var audioVolume = initialAudioVolume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    @Volatile private var hasConnection = false
    @Volatile private var hasStartedVideo = false
    @Volatile private var streamStopThresholdMs = STREAM_STOP_GRACE_MS

    /**
     * Cached SPS/PPS bytes from the most recent codec-config NAL we received
     * over the wire. The Mac sends SPS/PPS once at session start; if our
     * VideoPlayer is recreated mid-session (surface change, video-mode toggle,
     * adaptive-playback restart) we replay this into the new player so it can
     * actually decode without forcing the source to reconnect.
     */
    @Volatile private var cachedCodecConfig: ByteArray? = null
    /** Wall-clock time (elapsedRealtime) when the first video byte arrived. */
    @Volatile private var firstVideoBytesAtMs = 0L
    /** Wall-clock time (elapsedRealtime) when the first accepted audio byte arrived. */
    @Volatile private var firstAudioBytesAtMs = 0L
    @Volatile private var lastVideoStatusAtMs = 0L

    init {
        surfaceView.holder.addCallback(this)
        ensureAudioPlayer()
    }

    @Suppress("unused")
    fun onRecvVideoData(buffer: ByteBuffer, size: Int, nativePointer: Long, nalType: Int, dts: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvVideoData dts = $dts, pts = $pts, nalType = $nalType, nal length = $size")
        }
        markMediaTraffic()
        val now = SystemClock.elapsedRealtime()
        if (firstVideoBytesAtMs == 0L) {
            firstVideoBytesAtMs = now
            scheduleStartupWatchdog()
            reportStreamStatus("Video bytes received", now)
            Log.i(TAG, "first video bytes received (size=$size, nalType=$nalType)")
        } else if (!hasStartedVideo && now - lastVideoStatusAtMs >= VIDEO_STATUS_INTERVAL_MS) {
            reportStreamStatus("Video bytes receiving", now)
        }
        markVideoActivity()
        onTrafficSample(size)

        // Capture SPS/PPS so we can replay it into any future VideoPlayer
        // instance. The native byte buffer is freed when the NALPacket is
        // released so we MUST copy here, not keep a reference to it.
        if (nalType == NAL_TYPE_CODEC_CONFIG && size > 0) {
            val copy = ByteArray(size)
            val duplicate = buffer.duplicate()
            duplicate.position(0)
            duplicate.limit(size)
            duplicate.get(copy)
            if (hasStartedVideo && cachedCodecConfig != null) {
                restartPlayersForVideoSwitch()
            }
            cachedCodecConfig = copy
            Log.i(TAG, "cached SPS/PPS (size=$size) for replay")
        }

        val packet = NALPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            nalType = nalType,
            pts = pts,
            dts = dts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        val player = videoPlayer ?: ensureVideoPlayer()
        if (player == null) {
            Log.w(TAG, "no video player available (surface invalid?); dropping packet (nalType=$nalType, size=$size)")
            packet.release()
            return
        }
        player.addPacket(packet)
    }

    @Suppress("unused")
    fun onRecvAudioData(buffer: ByteBuffer, size: Int, nativePointer: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvAudioData pcm bytes = $size, pts = $pts")
        }
        markMediaTraffic()
        if (firstAudioBytesAtMs == 0L) {
            firstAudioBytesAtMs = SystemClock.elapsedRealtime()
            onStreamStatusChanged("Audio bytes received")
        }
        onTrafficSample(size)
        val packet = PCMPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            pts = pts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        val player = ensureAudioPlayer()
        if (player == null) {
            packet.release()
            return
        }
        player.addPacket(packet)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoPlayer?.stopDecode()
        videoPlayer = null
        hasStartedVideo = false
    }

    fun startServer() {
        ensureAudioPlayer()
        if (serverId == 0L) {
            serverId = start()
            if (serverId != 0L) {
                onStreamStatusChanged("Receiver ready")
            }
        }
    }

    fun stopServer() {
        mainHandler.removeCallbacks(confirmStreamStopped)
        mainHandler.removeCallbacks(startupWatchdog)
        if (serverId != 0L) {
            stop(serverId)
        }
        serverId = 0L
        stopVideoPlayer()
        audioPlayer?.stopPlay()
        audioPlayer = null
        lastMediaPacketAtMs = 0L
        hasConnection = false
        hasStartedVideo = false
        firstVideoBytesAtMs = 0L
        firstAudioBytesAtMs = 0L
        lastVideoStatusAtMs = 0L
        cachedCodecConfig = null
        onStreamStatusChanged("Stream idle")
    }

    fun setVideoMode(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (hasConnection) {
            return
        }
        stopVideoPlayer()
    }

    fun setAudioVolume(volume: Float) {
        audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        audioPlayer?.setVolume(audioVolume)
    }

    @Suppress("unused")
    fun getVideoWidth(): Int = videoWidth

    @Suppress("unused")
    fun getVideoHeight(): Int = videoHeight

    @Suppress("unused")
    fun onStreamStopped() {
        if (firstVideoBytesAtMs == 0L && !hasStartedVideo) {
            mainHandler.removeCallbacks(confirmStreamStopped)
            hasConnection = false
            firstAudioBytesAtMs = 0L
            lastMediaPacketAtMs = 0L
            onStreamStatusChanged("Audio stream stopped")
            return
        }
        onStreamStatusChanged("Stream stopped")
        scheduleStreamStopCheck(STREAM_STOP_GRACE_MS)
    }

    val port: Int
        get() = if (serverId != 0L) getPort(serverId) else 0

    private external fun start(): Long
    private external fun stop(serverId: Long)
    private external fun getPort(serverId: Long): Int

    private fun markMediaTraffic() {
        mainHandler.removeCallbacks(confirmStreamStopped)
        lastMediaPacketAtMs = SystemClock.elapsedRealtime()
        if (!hasConnection) {
            hasConnection = true
        }
    }

    private val confirmStreamStopped = Runnable {
        if (!hasConnection) {
            return@Runnable
        }
        val lastPacketAgeMs = SystemClock.elapsedRealtime() - lastMediaPacketAtMs
        if (lastPacketAgeMs >= streamStopThresholdMs) {
            resetStreamPlayback()
            onStreamStoppedCallback.invoke()
        }
    }

    private fun resetStreamPlayback() {
        stopVideoPlayer()
        restartAudioPlayer()
        hasConnection = false
        hasStartedVideo = false
        firstVideoBytesAtMs = 0L
        firstAudioBytesAtMs = 0L
        lastMediaPacketAtMs = 0L
        lastVideoStatusAtMs = 0L
        cachedCodecConfig = null
        onStreamStatusChanged("Receiver ready")
    }

    private fun scheduleStreamStopCheck(thresholdMs: Long) {
        streamStopThresholdMs = thresholdMs
        mainHandler.removeCallbacks(confirmStreamStopped)
        mainHandler.postDelayed(confirmStreamStopped, thresholdMs)
    }

    private fun ensureAudioPlayer(): AudioPlayer? {
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer(audioVolume, onLatencySample).also { it.start() }
        } else {
            audioPlayer?.setVolume(audioVolume)
        }
        return audioPlayer
    }

    private fun restartPlayersForVideoSwitch() {
        mainHandler.removeCallbacks(confirmStreamStopped)
        mainHandler.removeCallbacks(startupWatchdog)
        stopVideoPlayer()
        restartAudioPlayer()
        hasStartedVideo = false
        firstVideoBytesAtMs = 0L
        firstAudioBytesAtMs = 0L
        lastVideoStatusAtMs = 0L
    }

    private fun restartAudioPlayer() {
        audioPlayer?.let { player ->
            player.stopPlay()
            try {
                player.join(AUDIO_RESTART_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        audioPlayer = null
        ensureAudioPlayer()
    }

    @Synchronized
    private fun ensureVideoPlayer(): VideoPlayer? {
        val currentPlayer = videoPlayer
        if (currentPlayer != null) {
            return currentPlayer
        }
        if (!surfaceView.holder.surface.isValid) {
            return null
        }
        val newPlayer = VideoPlayer(
            surfaceView.holder.surface,
            videoWidth,
            videoHeight,
            onLatencySample,
            ::handleVideoFrameRendered
        )
        videoPlayer = newPlayer
        onStreamStatusChanged("Decoder starting")
        newPlayer.start()
        // Replay cached SPS/PPS so the freshly-created decoder has codec
        // config without waiting for the source to send it again. Without
        // this, swapping video modes mid-session (or any time the surface
        // is recreated) leaves the decoder permanently waiting and the
        // user staring at a black screen.
        cachedCodecConfig?.let { configBytes ->
            Log.i(TAG, "replaying cached SPS/PPS (size=${configBytes.size}) into new VideoPlayer")
            newPlayer.addPacket(
                NALPacket.forCodecConfig(configBytes, SystemClock.elapsedRealtime())
            )
        }
        return newPlayer
    }

    @Synchronized
    private fun stopVideoPlayer() {
        videoPlayer?.let { player ->
            player.stopDecode()
            try {
                player.join(VIDEO_RESTART_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        videoPlayer = null
    }

    private fun handleVideoFrameRendered() {
        if (hasStartedVideo) {
            return
        }
        hasStartedVideo = true
        mainHandler.removeCallbacks(startupWatchdog)
        lastMediaPacketAtMs = SystemClock.elapsedRealtime()
        onConnectionStarted()
        onStreamStatusChanged("First frame rendered")
    }

    private fun reportStreamStatus(status: String, now: Long = SystemClock.elapsedRealtime()) {
        lastVideoStatusAtMs = now
        onStreamStatusChanged(status)
    }

    /**
     * Watchdog that logs when video bytes are arriving but no decoded frame has
     * reached the surface yet. It deliberately does not hide the startup panel:
     * that would just turn a decoder startup problem into a black screen.
     */
    private val startupWatchdog = Runnable {
        if (hasStartedVideo) {
            return@Runnable
        }
        val trafficAgeMs = SystemClock.elapsedRealtime() - lastMediaPacketAtMs
        if (lastMediaPacketAtMs == 0L || trafficAgeMs > STARTUP_WATCHDOG_TRAFFIC_MAX_AGE_MS) {
            return@Runnable
        }
        Log.w(
            TAG,
            "startup watchdog: ${STARTUP_WATCHDOG_MS}ms with traffic but no rendered frame"
        )
    }

    private fun scheduleStartupWatchdog() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.postDelayed(startupWatchdog, STARTUP_WATCHDOG_MS)
    }

    private fun markVideoActivity() {
        onVideoActivity(true)
    }

    companion object {
        private const val TAG = "Receiver-RAOP"
        private const val DEBUG_FRAMES = false
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val AUDIO_RESTART_TIMEOUT_MS = 500L
        private const val VIDEO_RESTART_TIMEOUT_MS = 500L
        private const val STREAM_STOP_GRACE_MS = 5_000L
        private const val VIDEO_STATUS_INTERVAL_MS = 1_000L
        // If video bytes have been arriving for this long with no rendered
        // frame, give up waiting for onFrameRendered and unblock the UI. The
        // surface will simply show whatever the decoder eventually paints.
        private const val STARTUP_WATCHDOG_MS = 6_000L
        // The watchdog only fires if traffic is recent; avoids spuriously
        // hiding the panel after a long idle gap.
        private const val STARTUP_WATCHDOG_TRAFFIC_MAX_AGE_MS = 2_000L
        private const val NAL_TYPE_CODEC_CONFIG = 0

        init {
            System.loadLibrary("raop_server")
            System.loadLibrary("play-lib")
        }
    }
}
