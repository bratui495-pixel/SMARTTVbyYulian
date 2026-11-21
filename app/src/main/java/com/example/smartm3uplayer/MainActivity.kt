package com.example.smartm3uplayer

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

data class Channel(val name: String, val url: String)
data class SavedPlaylist(val name: String, val url: String)

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerContent: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var tvCurrentChannel: TextView
    private lateinit var osdIndicator: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var lvChannels: ListView
    private lateinit var etSearch: EditText
    private lateinit var btnChannels: Button
    private lateinit var btnAspect: Button
    private lateinit var btnSettings: Button

    private var player: ExoPlayer? = null
    private var audioManager: AudioManager? = null
    private var gestureDetector: GestureDetector? = null
    private lateinit var prefs: SharedPreferences

    private val allChannels = mutableListOf<Channel>()
    private val filteredChannels = mutableListOf<Channel>()
    private lateinit var adapter: ArrayAdapter<String>

    private val savedPlaylists = mutableListOf<SavedPlaylist>()
    private val defaultPlaylistUrl = "https://raw.githubusercontent.com/slava-67/TV/refs/heads/IPTV/kino-plus.m3u"
    private var currentPlaylistUrl = defaultPlaylistUrl

    private var aspectModeIndex = 0
    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
    )

    private var menuLanguage = "Română"
    private var videoQuality = "Maximă (Auto)"
    private var contrastLevel = "Normal"

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        if (::osdIndicator.isInitialized) {
            osdIndicator.visibility = View.GONE
        }
    }
    private var volumeAccumulator = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_main)

            prefs = getSharedPreferences("SmartIPTVPrefs", Context.MODE_PRIVATE)
            loadPreferences()

            drawerLayout = findViewById(R.id.drawerLayout)
            drawerContent = findViewById(R.id.drawerContent)
            playerView = findViewById(R.id.playerView)
            tvCurrentChannel = findViewById(R.id.tvCurrentChannel)
            osdIndicator = findViewById(R.id.osdIndicator)
            topBar = findViewById(R.id.topBar)
            lvChannels = findViewById(R.id.lvChannels)
            etSearch = findViewById(R.id.etSearch)
            btnChannels = findViewById(R.id.btnChannels)
            btnAspect = findViewById(R.id.btnAspect)
            btnSettings = findViewById(R.id.btnSettings)

            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            setupPlayer()
            setupGestures()
            setupUI()
            applyLanguageUI()
            applyContrastUI()
            loadM3U(currentPlaylistUrl)
        } catch (e: Exception) {
            Toast.makeText(this, "Eroare la pornire: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadPreferences() {
        currentPlaylistUrl = prefs.getString("current_url", defaultPlaylistUrl) ?: defaultPlaylistUrl
        menuLanguage = prefs.getString("language", "Română") ?: "Română"
        videoQuality = prefs.getString("quality", "Maximă (Auto)") ?: "Maximă (Auto)"
        contrastLevel = prefs.getString("contrast", "Normal") ?: "Normal"
        aspectModeIndex = prefs.getInt("aspect_index", 0).coerceIn(0, aspectModes.lastIndex)

        savedPlaylists.clear()
        val jsonStr = prefs.getString("saved_playlists", null)
        if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "Playlist")
                    val url = obj.optString("url", "")
                    if (url.isNotBlank()) {
                        savedPlaylists.add(SavedPlaylist(name, url))
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (savedPlaylists.isEmpty()) {
            savedPlaylists.add(SavedPlaylist("Kino Plus", defaultPlaylistUrl))
            savePlaylistsToPrefs()
        }
    }

    private fun savePlaylistsToPrefs() {
        val arr = JSONArray()
        for (pl in savedPlaylists) {
            val obj = JSONObject()
            obj.put("name", pl.name)
            obj.put("url", pl.url)
            arr.put(obj)
        }
        prefs.edit()
            .putString("saved_playlists", arr.toString())
            .putString("current_url", currentPlaylistUrl)
            .apply()
    }

    private fun setupPlayer() {
        try {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)

            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpDataSourceFactory)

            val exoPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val msg = error.message ?: "Eroare necunoscută"
                        tvCurrentChannel.text = "${tvCurrentChannel.text} [${error.errorCodeName}]"
                        Toast.makeText(
                            this@MainActivity,
                            "${error.errorCodeName}: $msg",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })

            player = exoPlayer
            playerView.player = exoPlayer
            playerView.resizeMode = aspectModes[aspectModeIndex].first
            applyVideoQuality()

            playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Eroare player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyVideoQuality() {
        val builder = TrackSelectionParameters.Builder(this)
        when (videoQuality) {
            "Full HD (1080p)" -> builder.setMaxVideoSize(1920, 1080)
            "HD (720p)" -> builder.setMaxVideoSize(1280, 720)
            "SD (Econom)" -> builder.setMaxVideoSize(854, 480)
            else -> builder.clearVideoSizeConstraints()
        }
        player?.trackSelectionParameters = builder.build()
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                if (screenHeight <= 0) return false

                if (abs(distanceX) > abs(distanceY) * 1.5f) {
                    if (distanceX < -25f && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.openDrawer(GravityCompat.START)
                        return true
                    }
                    return false
                }

                if (abs(distanceY) > abs(distanceX)) {
                    val percentDelta = distanceY / (screenHeight * 0.7f)
                    if (e1.x < screenWidth / 2f) {
                        adjustVolume(percentDelta)
                    } else {
                        adjustBrightness(percentDelta)
                    }
                    return true
                }
                return false
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    private fun adjustVolume(delta: Float) {
        val am = audioManager ?: return
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return
        val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeAccumulator += delta * maxVolume
        val newVolume = if (abs(volumeAccumulator) >= 1f) {
            val step = volumeAccumulator.toInt()
            volumeAccumulator -= step
            (currentVolume + step).coerceIn(0, maxVolume).also {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            }
        } else {
            currentVolume
        }
        val percent = ((newVolume.toFloat() / maxVolume) * 100).toInt()
        showOsd("🔊 Volum: $percent%")
    }

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        val baseBrightness = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
        val brightness = (baseBrightness + delta).coerceIn(0.05f, 1.0f)
        lp.screenBrightness = brightness
        window.attributes = lp
        val percent = (brightness * 100).toInt()
        showOsd("☀️ Luminozitate: $percent%")
    }

    private fun showOsd(text: String) {
        osdIndicator.text = text
        osdIndicator.visibility = View.VISIBLE
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 1200)
    }

    private fun setupUI() {
        btnChannels.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        btnAspect.setOnClickListener {
            aspectModeIndex = (aspectModeIndex + 1) % aspectModes.size
            playerView.resizeMode = aspectModes[aspectModeIndex].first
            prefs.edit().putInt("aspect_index", aspectModeIndex).apply()
            applyLanguageUI()
        }
        btnSettings.setOnClickListener { showSettingsDialog() }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvChannels.adapter = adapter

        lvChannels.setOnItemClickListener { _, _, position, _ ->
            if (position in filteredChannels.indices) {
                playChannel(filteredChannels[position])
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filterChannels(s.toString())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun applyLanguageUI() {
        val aspectLabel = aspectModes[aspectModeIndex].second
        when (menuLanguage) {
            "English" -> {
                btnChannels.text = "☰ Channels"
                btnAspect.text = "Aspect: $aspectLabel"
                btnSettings.text = "⚙ Settings"
                etSearch.hint = "🔍 Search channel..."
            }
            "Русский" -> {
                btnChannels.text = "☰ Каналы"
                btnAspect.text = "Формат: $aspectLabel"
                btnSettings.text = "⚙ Настройки"
                etSearch.hint = "🔍 Поиск канала..."
            }
            else -> {
                btnChannels.text = "☰ Canale"
                btnAspect.text = "Aspect: $aspectLabel"
                btnSettings.text = "⚙ Setări"
                etSearch.hint = "🔍 Caută canal..."
            }
        }
    }

    private fun applyContrastUI() {
        when (contrastLevel) {
            "Ridicat (High Contrast)" -> drawerContent.setBackgroundColor(0xFF000000.toInt())
            "Întunecat (Dark)" -> drawerContent.setBackgroundColor(0xF2090D16.toInt())
            else -> drawerContent.setBackgroundColor(0xE60F172A.toInt())
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "📂 Playlisturi Salvate (${savedPlaylists.size})",
            "➕ Adaugă Playlist Nou",
            "🌐 Limba Meniului ($menuLanguage)",
            "🎨 Contrast & Culori ($contrastLevel)",
            "📺 Calitate Video ($videoQuality)"
        )

        AlertDialog.Builder(this)
            .setTitle("Setări Player")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSavedPlaylistsDialog()
                    1 -> showAddPlaylistDialog()
                    2 -> showLanguageDialog()
                    3 -> showContrastDialog()
                    4 -> showQualityDialog()
                }
            }
            .setNegativeButton("Închide", null)
            .show()
    }

    private fun showSavedPlaylistsDialog() {
        val names = savedPlaylists.map {
            if (it.url == currentPlaylistUrl) "✔ ${it.name}" else it.name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Alege Playlist")
            .setItems(names) { _, which ->
                currentPlaylistUrl = savedPlaylists[which].url
                savePlaylistsToPrefs()
                loadM3U(currentPlaylistUrl)
            }
            .setNeutralButton("Șterge listă") { _, _ -> showDeletePlaylistDialog() }
            .setNegativeButton("Înapoi", null)
            .show()
    }

    private fun showDeletePlaylistDialog() {
        if (savedPlaylists.isEmpty()) {
            Toast.makeText(this, "Nu există liste de șters.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = savedPlaylists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selectează lista de șters")
            .setItems(names) { _, which ->
                val removed = savedPlaylists.removeAt(which)
                if (removed.url == currentPlaylistUrl) {
                    currentPlaylistUrl = savedPlaylists.firstOrNull()?.url ?: defaultPlaylistUrl
                    loadM3U(currentPlaylistUrl)
                }
                savePlaylistsToPrefs()
                Toast.makeText(this, "Șters!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showAddPlaylistDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val inputName = EditText(this).apply { hint = "Nume listă (ex: Sport, Filme)" }
        val inputUrl = EditText(this).apply { hint = "Link M3U (http://...)" }
        layout.addView(inputName)
        layout.addView(inputUrl)

        AlertDialog.Builder(this)
            .setTitle("Adaugă Playlist Nou")
            .setView(layout)
            .setPositiveButton("Salvează") { _, _ ->
                val name = inputName.text.toString().trim().ifEmpty { "Lista ${savedPlaylists.size + 1}" }
                val url = inputUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    savedPlaylists.add(SavedPlaylist(name, url))
                    currentPlaylistUrl = url
                    savePlaylistsToPrefs()
                    loadM3U(url)
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("Română", "English", "Русский")
        AlertDialog.Builder(this)
            .setTitle("Limba meniului")
            .setSingleChoiceItems(languages, languages.indexOf(menuLanguage)) { dialog, which ->
                menuLanguage = languages[which]
                prefs.edit().putString("language", menuLanguage).apply()
                applyLanguageUI()
                dialog.dismiss()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showContrastDialog() {
        val contrasts = arrayOf("Normal", "Ridicat (High Contrast)", "Întunecat (Dark)")
        AlertDialog.Builder(this)
            .setTitle("Contrast")
            .setSingleChoiceItems(contrasts, contrasts.indexOf(contrastLevel)) { dialog, which ->
                contrastLevel = contrasts[which]
                prefs.edit().putString("contrast", contrastLevel).apply()
                applyContrastUI()
                dialog.dismiss()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showQualityDialog() {
        val qualities = arrayOf("Maximă (Auto)", "Full HD (1080p)", "HD (720p)", "SD (Econom)")
        AlertDialog.Builder(this)
            .setTitle("Calitate Video")
            .setSingleChoiceItems(qualities, qualities.indexOf(videoQuality)) { dialog, which ->
                videoQuality = qualities[which]
                prefs.edit().putString("quality", videoQuality).apply()
                applyVideoQuality()
                dialog.dismiss()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun loadM3U(urlString: String) {
        tvCurrentChannel.text = "Se descarcă playlistul..."
        thread {
            try {
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val content = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val parsed = parseM3U(content)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    allChannels.clear()
                    allChannels.addAll(parsed)
                    filterChannels("")
                    if (allChannels.isNotEmpty()) {
                        val lastUrl = prefs.getString("last_channel_url", null)
                        val toPlay = allChannels.find { it.url == lastUrl } ?: allChannels[0]
                        playChannel(toPlay)
                    } else {
                        tvCurrentChannel.text = "Niciun canal găsit"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    tvCurrentChannel.text = "Eroare la încărcare M3U"
                    Toast.makeText(this, "Eroare M3U: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseM3U(content: String): List<Channel> {
        val list = mutableListOf<Channel>()
        val lines = content.lines()
        var currentName = "Canal Necunoscut"
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                val comma = trimmed.lastIndexOf(',')
                if (comma != -1 && comma < trimmed.length - 1) {
                    currentName = trimmed.substring(comma + 1).trim()
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                list.add(Channel(currentName, trimmed))
                currentName = "Canal Necunoscut"
            }
        }
        return list
    }

    private fun filterChannels(query: String) {
        filteredChannels.clear()
        if (query.isEmpty()) {
            filteredChannels.addAll(allChannels)
        } else {
            filteredChannels.addAll(allChannels.filter { it.name.contains(query, ignoreCase = true) })
        }
        adapter.clear()
        adapter.addAll(filteredChannels.map { it.name })
        adapter.notifyDataSetChanged()
    }

    private fun playChannel(channel: Channel) {
        try {
            tvCurrentChannel.text = channel.name
            prefs.edit().putString("last_channel_url", channel.url).apply()
            val mediaItem = MediaItem.fromUri(Uri.parse(channel.url))
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Toast.makeText(this, "Eroare redare: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        osdHandler.removeCallbacks(hideOsdRunnable)
        player?.release()
        player = null
    }
}
