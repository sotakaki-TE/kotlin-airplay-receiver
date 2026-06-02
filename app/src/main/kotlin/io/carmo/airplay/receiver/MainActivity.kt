package io.carmo.airplay.receiver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var airPlayServer: AirPlayServer
    private lateinit var raopServer: RaopServer
    private lateinit var dnsNotify: DNSNotify
    private lateinit var playbackSurface: SurfaceView
    private lateinit var startupPanel: View
    private lateinit var startupVersionLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var discoveryStatusView: TextView
    private lateinit var videoModeGroup: RadioGroup
    private lateinit var wakeModeGroup: RadioGroup
    private lateinit var audioVolumeLabel: TextView
    private lateinit var audioVolumeBar: ProgressBar
    private lateinit var audioVolumeOverlay: View
    private lateinit var audioVolumeOverlayLabel: TextView
    private lateinit var audioVolumeOverlayBar: ProgressBar
    private lateinit var trafficMonitor: TrafficMonitorView
    private lateinit var audioManager: AudioManager
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wakeNudgeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var videoMode = VideoMode.FULL_HD
    private var wakeMode = WakeMode.WAKE_ON_ACTIVITY
    private var audioVolume = DEFAULT_AUDIO_VOLUME
    @Volatile private var lastWakeNudgeAtMs = 0L
    @Volatile private var wakeNudgePending = false
    private var volumeGestureStartY = 0f
    private var volumeGestureStartLevel = DEFAULT_AUDIO_VOLUME
    private var isVolumeGestureCandidate = false
    private var isAdjustingVolume = false
    private var trafficGestureStartX = 0f
    private var trafficGestureStartY = 0f
    private var isTrafficGestureCandidate = false
    private var isStarted = false
    private var isStreaming = false
    private var discoveryStatus = "Discovery starting"
    private var streamStatus = "Stream idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configurePlaybackWindow()

        playbackSurface = findViewById(R.id.surface)
        startupPanel = findViewById(R.id.startup_panel)
        startupVersionLabel = findViewById(R.id.startup_version_label)
        statusView = findViewById(R.id.status)
        discoveryStatusView = findViewById(R.id.discovery_status)
        videoModeGroup = findViewById(R.id.video_mode_group)
        wakeModeGroup = findViewById(R.id.wake_mode_group)
        audioVolumeLabel = findViewById(R.id.audio_volume_label)
        audioVolumeBar = findViewById(R.id.audio_volume_bar)
        audioVolumeOverlay = findViewById(R.id.audio_volume_overlay)
        audioVolumeOverlayLabel = findViewById(R.id.audio_volume_overlay_label)
        audioVolumeOverlayBar = findViewById(R.id.audio_volume_overlay_bar)
        trafficMonitor = findViewById(R.id.traffic_monitor)
        trafficMonitor.setOnClickListener { trafficMonitor.visibility = View.GONE }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        configurePlaybackSurface(playbackSurface)
        configureControlLayer()

        videoMode = loadVideoMode()
        audioVolume = loadAudioVolume()
        airPlayServer = AirPlayServer()
        raopServer = RaopServer(
            playbackSurface,
            ::hideStatus,
            ::handleVideoActivity,
            ::handleTrafficSample,
            ::handleLatencySample,
            ::handleStreamStopped,
            ::handleStreamStatus,
            videoMode.width,
            videoMode.height,
            audioVolume
        )
        dnsNotify = DNSNotify(this, ::handleDiscoveryStatus)
        wakeMode = loadWakeMode()
        keepSurfaceProportional(playbackSurface)
        configureVideoModeControl()
        configureWakeModeControl()
        configureAudioControls()
        videoModeGroup.findViewById<View>(videoMode.radioButtonId).requestFocus()
        showWaitingStatus()

        if (DEBUG_CODECS) {
            logSupportedCodecs()
        }

        startServer()
    }

    override fun onResume() {
        super.onResume()
        if (::audioManager.isInitialized) {
            audioVolume = loadAudioVolume()
            updateAudioVolumeUi()
        }
        if (isStarted) {
            applyWakeMode()
            refreshAnnouncements()
        }
        if (::startupPanel.isInitialized && !isStreaming) {
            updateWaitingStatus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::startupPanel.isInitialized) {
            // Only re-assert immersive flags if they actually drifted. Writing
            // systemUiVisibility unconditionally on every focus event —
            // including the brief focus blip when a tap reveals the system
            // bars under FLAG_IMMERSIVE_STICKY — forces a layout pass that
            // shows up as a flicker on the startup panel.
            ensureImmersiveFlags()
            if (isStarted) {
                applyWakeMode()
            }
            // Note: text is intentionally NOT refreshed here. Nothing observable
            // by the user changes between focus events, so rewriting it just
            // adds a needless redraw cycle.
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handleTrafficMonitorGesture(event)
        if (handleVolumeGesture(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun configurePlaybackWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        ensureImmersiveFlags()
    }

    /**
     * Idempotent: only rewrites the system-UI visibility bitmask when it has
     * actually drifted from our desired value. This keeps tap-induced focus
     * blips from triggering a redundant layout pass on the startup panel.
     */
    private fun ensureImmersiveFlags() {
        val decor = window.decorView
        if (decor.systemUiVisibility != IMMERSIVE_FLAGS) {
            decor.systemUiVisibility = IMMERSIVE_FLAGS
        }
    }

    private fun configurePlaybackSurface(surfaceView: SurfaceView) {
        surfaceView.setZOrderOnTop(false)
        surfaceView.setZOrderMediaOverlay(false)
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
    }

    private fun configureControlLayer() {
        val elevation = CONTROL_OVERLAY_ELEVATION_DP * resources.displayMetrics.density
        startupPanel.elevation = elevation
        startupVersionLabel.elevation = elevation
        audioVolumeOverlay.elevation = elevation
        trafficMonitor.elevation = elevation
    }

    private fun applyWakeMode() {
        when (wakeMode) {
            WakeMode.DEFAULT -> {
                allowScreenSaver()
                releaseWakeNudgeLock()
            }
            WakeMode.ALWAYS_AWAKE -> {
                releaseWakeNudgeLock()
                keepReceiverAwake()
            }
            WakeMode.WAKE_ON_ACTIVITY -> {
                setKeepScreenOn(false)
                releaseWakeLock()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun keepReceiverAwake() {
        setKeepScreenOn(true)
        val wakeLock = screenWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "$packageName:$WAKE_LOCK_TAG"
            ).apply {
                setReferenceCounted(false)
                screenWakeLock = this
            }
        }

        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }

    private fun allowScreenSaver() {
        setKeepScreenOn(false)
        releaseWakeLock()
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.keepScreenOn = enabled
        if (::playbackSurface.isInitialized) {
            playbackSurface.keepScreenOn = enabled
        }
    }

    private fun releaseWakeLock() {
        val wakeLock = screenWakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    private fun releaseWakeNudgeLock() {
        val wakeLock = wakeNudgeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun nudgeDisplayAwake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeNudgeAtMs < WAKE_NUDGE_THROTTLE_MS) {
            return
        }
        lastWakeNudgeAtMs = now
        if (!hasWindowFocus()) {
            bringReceiverToFront()
        }

        val wakeLock = wakeNudgeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:$WAKE_NUDGE_LOCK_TAG"
            ).apply {
                setReferenceCounted(false)
                wakeNudgeLock = this
            }
        }
        wakeLock.acquire(WAKE_NUDGE_DURATION_MS)
    }

    private fun bringReceiverToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun keepSurfaceProportional(surfaceView: SurfaceView) {
        fun updateSurfaceLayout() {
            updateSurfaceLayout(surfaceView)
        }

        surfaceView.post {
            val parent = surfaceView.parent as? View ?: return@post
            parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
            updateSurfaceLayout()
        }
    }

    private fun updateSurfaceLayout(surfaceView: SurfaceView) {
        val parent = surfaceView.parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth == 0 || parentHeight == 0) {
            return
        }

        var width = parentWidth
        var height = width * videoMode.height / videoMode.width
        if (height > parentHeight) {
            height = parentHeight
            width = height * videoMode.width / videoMode.height
        }

        val currentParams = surfaceView.layoutParams
        if (currentParams.width != width || currentParams.height != height) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun showWaitingStatus() {
        isStreaming = false
        updateWaitingStatus()
        if (startupVersionLabel.visibility != View.VISIBLE) {
            startupVersionLabel.visibility = View.VISIBLE
        }
        if (startupPanel.visibility != View.VISIBLE) {
            startupPanel.visibility = View.VISIBLE
        }
        if (audioVolumeOverlay.visibility != View.GONE) {
            audioVolumeOverlay.visibility = View.GONE
        }
        showControl(startupVersionLabel)
        showControl(startupPanel)
    }

    private fun updateWaitingStatus() {
        startupVersionLabel.text = "v${BuildConfig.VERSION_NAME}"
        statusView.text = "${dnsNotify.deviceName}\nWaiting in ${videoMode.label} mode\n$streamStatus"
        discoveryStatusView.text = discoveryStatus
    }

    private fun handleDiscoveryStatus(status: String) {
        discoveryStatus = status
        runOnUiThread {
            if (::discoveryStatusView.isInitialized && !isStreaming) {
                discoveryStatusView.text = status
            }
        }
    }

    private fun hideStatus() {
        runOnUiThread {
            isStreaming = true
            streamStatus = "Streaming"
            startupPanel.visibility = View.GONE
            startupVersionLabel.visibility = View.GONE
        }
    }

    private fun handleStreamStatus(status: String) {
        runOnUiThread {
            streamStatus = status
            if (::statusView.isInitialized && !isStreaming) {
                updateWaitingStatus()
            }
        }
    }

    private fun configureVideoModeControl() {
        videoModeGroup.check(videoMode.radioButtonId)
        videoModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = VideoMode.fromRadioButtonId(checkedId) ?: return@setOnCheckedChangeListener
            if (selectedMode == videoMode) {
                return@setOnCheckedChangeListener
            }
            videoMode = selectedMode
            saveVideoMode(selectedMode)
            raopServer.setVideoMode(selectedMode.width, selectedMode.height)
            updateSurfaceLayout(playbackSurface)
            if (!isStreaming) {
                updateWaitingStatus()
            }
        }
    }

    private fun configureWakeModeControl() {
        wakeModeGroup.check(wakeMode.radioButtonId)
        wakeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = WakeMode.fromRadioButtonId(checkedId) ?: return@setOnCheckedChangeListener
            if (selectedMode == wakeMode) {
                return@setOnCheckedChangeListener
            }
            wakeMode = selectedMode
            saveWakeMode(selectedMode)
            applyWakeMode()
        }
    }

    private fun configureAudioControls() {
        updateAudioVolumeUi()
    }

    private fun loadWakeMode(): WakeMode {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return WakeMode.fromPreferenceValue(preferences.getString(PREFERENCE_WAKE_MODE, null))
            ?: WakeMode.WAKE_ON_ACTIVITY
    }

    private fun loadVideoMode(): VideoMode {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return VideoMode.fromPreferenceValue(preferences.getString(PREFERENCE_VIDEO_MODE_V2, null))
            ?: VideoMode.FULL_HD
    }

    private fun saveVideoMode(mode: VideoMode) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_VIDEO_MODE_V2, mode.preferenceValue)
            .apply()
    }

    private fun saveWakeMode(mode: WakeMode) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_WAKE_MODE, mode.preferenceValue)
            .apply()
    }

    private fun loadAudioVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return DEFAULT_AUDIO_VOLUME
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    private fun saveAudioVolume(volume: Float) {
        audioVolume = applySystemAudioVolume(volume)
    }

    private fun setAudioVolume(volume: Float, persist: Boolean) {
        audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        audioVolume = applySystemAudioVolume(audioVolume)
        if (persist) {
            saveAudioVolume(audioVolume)
        }
        updateAudioVolumeUi()
        if (isStreaming) {
            showAudioVolumeOverlay()
        }
    }

    private fun applySystemAudioVolume(volume: Float): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        }
        val volumeIndex = (volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME) * maxVolume)
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeIndex, 0)
        return volumeIndex.toFloat() / maxVolume
    }

    private fun updateAudioVolumeUi() {
        if (::audioManager.isInitialized) {
            audioVolume = loadAudioVolume()
        }
        val progress = (audioVolume * 100).toInt()
        val label = "Volume $progress%"
        audioVolumeLabel.text = label
        audioVolumeBar.progress = progress
        audioVolumeBar.isEnabled = true
        audioVolumeOverlayLabel.text = label
        audioVolumeOverlayBar.progress = progress
    }

    private fun showAudioVolumeOverlay() {
        audioVolumeOverlay.visibility = View.VISIBLE
        showControl(audioVolumeOverlay)
        audioVolumeOverlay.removeCallbacks(hideAudioVolumeOverlay)
        audioVolumeOverlay.postDelayed(hideAudioVolumeOverlay, VOLUME_OVERLAY_DURATION_MS)
    }

    private val hideAudioVolumeOverlay = Runnable {
        audioVolumeOverlay.visibility = View.GONE
    }

    private fun handleVideoActivity(hasVideoActivity: Boolean) {
        if (wakeMode != WakeMode.WAKE_ON_ACTIVITY || !hasVideoActivity) {
            return
        }
        if (SystemClock.elapsedRealtime() - lastWakeNudgeAtMs < WAKE_NUDGE_THROTTLE_MS) {
            return
        }
        if (wakeNudgePending) {
            return
        }
        wakeNudgePending = true
        runOnUiThread {
            try {
                if (wakeMode == WakeMode.WAKE_ON_ACTIVITY) {
                    nudgeDisplayAwake()
                }
            } finally {
                wakeNudgePending = false
            }
        }
    }

    private fun handleTrafficSample(byteCount: Int) {
        trafficMonitor.recordTraffic(byteCount)
    }

    private fun handleLatencySample(latencyMs: Long) {
        trafficMonitor.recordLatency(latencyMs)
    }

    private fun handleStreamStopped() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                showWaitingStatus()
                refreshAnnouncements()
            }
        }
    }

    private fun handleTrafficMonitorGesture(event: MotionEvent) {
        // The traffic monitor is only meaningful while video is streaming, so
        // ignore gesture detection entirely when the startup panel is up. This
        // also stops accidental top-right taps on the version label from
        // "arming" the gesture and changing visible state on the next move.
        if (!isStreaming) {
            isTrafficGestureCandidate = false
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trafficGestureStartX = event.x
                trafficGestureStartY = event.y
                val edgeSize = TRAFFIC_GESTURE_EDGE_DP * resources.displayMetrics.density
                isTrafficGestureCandidate = event.x >= window.decorView.width - edgeSize &&
                    event.y <= edgeSize
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrafficGestureCandidate || trafficMonitor.visibility == View.VISIBLE) {
                    return
                }
                val draggedLeft = trafficGestureStartX - event.x
                val draggedDown = event.y - trafficGestureStartY
                val dragThreshold = TRAFFIC_GESTURE_DRAG_DP * resources.displayMetrics.density
                if (draggedLeft >= dragThreshold || draggedDown >= dragThreshold) {
                    trafficMonitor.visibility = View.VISIBLE
                    showControl(trafficMonitor)
                    isTrafficGestureCandidate = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTrafficGestureCandidate = false
            }
        }
    }

    private fun handleVolumeGesture(event: MotionEvent): Boolean {
        if (!isStreaming) {
            isVolumeGestureCandidate = false
            isAdjustingVolume = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                volumeGestureStartY = event.y
                volumeGestureStartLevel = audioVolume
                isAdjustingVolume = false
                val edgeSize = VOLUME_GESTURE_EDGE_DP * resources.displayMetrics.density
                isVolumeGestureCandidate = event.x >= window.decorView.width - edgeSize &&
                    event.y > edgeSize
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isVolumeGestureCandidate) {
                    return false
                }
                val verticalDrag = volumeGestureStartY - event.y
                val range = VOLUME_GESTURE_RANGE_DP * resources.displayMetrics.density
                if (!isAdjustingVolume && kotlin.math.abs(verticalDrag) < VOLUME_GESTURE_START_DP * resources.displayMetrics.density) {
                    return false
                }
                isAdjustingVolume = true
                setAudioVolume(volumeGestureStartLevel + verticalDrag / range, persist = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isAdjustingVolume) {
                    saveAudioVolume(audioVolume)
                }
                val consumed = isAdjustingVolume
                isVolumeGestureCandidate = false
                isAdjustingVolume = false
                return consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                isVolumeGestureCandidate = false
                isAdjustingVolume = false
            }
        }
        return false
    }

    private fun logSupportedCodecs() {
        val mediaCodecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        for (mediaCodecInfo in mediaCodecList.codecInfos) {
            if (mediaCodecInfo.isEncoder) {
                continue
            }
            Log.d(TAG, "supported codec = ${mediaCodecInfo.supportedTypes.joinToString()}")
        }
    }

    private fun startServer() {
        if (isStarted) {
            return
        }

        startReceiverForegroundService()
        acquireMulticastLock()
        airPlayServer.startServer()
        val airplayPort = airPlayServer.port
        if (airplayPort == 0) {
            Toast.makeText(applicationContext, "Start the AirPlay service failed", Toast.LENGTH_SHORT).show()
            handleDiscoveryStatus("AirPlay failed: port unavailable")
        } else {
            dnsNotify.registerAirplay(airplayPort)
        }

        raopServer.startServer()
        val raopPort = raopServer.port
        if (raopPort == 0) {
            Toast.makeText(applicationContext, "Start the RAOP service failed", Toast.LENGTH_SHORT).show()
            handleDiscoveryStatus("RAOP failed: port unavailable")
        } else {
            dnsNotify.registerRaop(raopPort)
        }

        isStarted = true
        applyWakeMode()
        Log.d(TAG, "deviceName = ${dnsNotify.deviceName}, airplayPort = $airplayPort, raopPort = $raopPort")
    }

    private fun refreshAnnouncements() {
        acquireMulticastLock()
        val airplayPort = airPlayServer.port
        if (airplayPort != 0) {
            dnsNotify.registerAirplay(airplayPort)
        }
        val raopPort = raopServer.port
        if (raopPort != 0) {
            dnsNotify.registerRaop(raopPort)
        }
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: run {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.createMulticastLock("$packageName:$MULTICAST_LOCK_TAG").apply {
                setReferenceCounted(false)
                multicastLock = this
            }
        }
        if (!lock.isHeld) {
            lock.acquire()
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        if (lock?.isHeld == true) {
            lock.release()
        }
    }

    private fun showControl(control: View) {
        control.bringToFront()
        control.translationZ = CONTROL_OVERLAY_ELEVATION_DP * resources.displayMetrics.density
        control.invalidate()
    }

    private fun startReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopReceiverForegroundService() {
        stopService(Intent(this, ReceiverForegroundService::class.java))
    }

    private fun stopServer() {
        if (!isStarted) {
            return
        }
        dnsNotify.stop()
        airPlayServer.stopServer()
        raopServer.stopServer()
        isStarted = false
        lastWakeNudgeAtMs = 0L
        wakeNudgePending = false
        allowScreenSaver()
        releaseWakeNudgeLock()
        releaseMulticastLock()
        stopReceiverForegroundService()
    }

    private enum class WakeMode(val preferenceValue: String, val radioButtonId: Int) {
        DEFAULT("default", R.id.wake_mode_default),
        ALWAYS_AWAKE("always_awake", R.id.wake_mode_always),
        WAKE_ON_ACTIVITY("wake_on_activity", R.id.wake_mode_activity);

        companion object {
            fun fromPreferenceValue(value: String?): WakeMode? = values().firstOrNull { it.preferenceValue == value }
            fun fromRadioButtonId(id: Int): WakeMode? = values().firstOrNull { it.radioButtonId == id }
        }
    }

    private enum class VideoMode(
        val preferenceValue: String,
        val radioButtonId: Int,
        val label: String,
        val width: Int,
        val height: Int
    ) {
        HD("720p", R.id.video_mode_720p, "720p", 1280, 720),
        FULL_HD("1080p", R.id.video_mode_1080p, "1080p", 1920, 1080);

        companion object {
            fun fromPreferenceValue(value: String?): VideoMode? = values().firstOrNull { it.preferenceValue == value }
            fun fromRadioButtonId(id: Int): VideoMode? = values().firstOrNull { it.radioButtonId == id }
        }
    }

    companion object {
        private const val TAG = "Receiver"
        private const val WAKE_LOCK_TAG = "ReceiverActive"
        private const val WAKE_NUDGE_LOCK_TAG = "ReceiverActivity"
        private const val MULTICAST_LOCK_TAG = "ReceiverDiscovery"
        private const val WAKE_NUDGE_DURATION_MS = 10_000L
        private const val WAKE_NUDGE_THROTTLE_MS = 5_000L
        private const val CONTROL_OVERLAY_ELEVATION_DP = 24f
        private const val TRAFFIC_GESTURE_EDGE_DP = 96f
        private const val TRAFFIC_GESTURE_DRAG_DP = 48f
        private const val VOLUME_GESTURE_EDGE_DP = 120f
        private const val VOLUME_GESTURE_START_DP = 12f
        private const val VOLUME_GESTURE_RANGE_DP = 240f
        private const val VOLUME_OVERLAY_DURATION_MS = 1_500L
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val DEFAULT_AUDIO_VOLUME = 1.0f
        private const val PREFERENCES_NAME = "receiver"
        private const val PREFERENCE_VIDEO_MODE_V2 = "video_mode_v2"
        private const val PREFERENCE_WAKE_MODE = "wake_mode"
        private const val DEBUG_CODECS = false

        @Suppress("DEPRECATION")
        private val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
