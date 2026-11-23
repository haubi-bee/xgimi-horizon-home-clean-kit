package tv.cleanhome.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG
import tv.cleanhome.launcher.apps.AppEntry
import tv.cleanhome.launcher.apps.AppRanker
import tv.cleanhome.launcher.apps.AppsAdapter
import tv.cleanhome.launcher.apps.AppsRepository
import tv.cleanhome.launcher.apps.HiddenAppsStore
import tv.cleanhome.launcher.apps.TileNameStore
import tv.cleanhome.launcher.apps.UsageStore
import tv.cleanhome.launcher.net.WifiStatusMonitor
import tv.cleanhome.launcher.photos.PhotoRepository
import tv.cleanhome.launcher.photos.SlideshowController
import tv.cleanhome.launcher.recommendations.Recommendation
import tv.cleanhome.launcher.recommendations.RecommendationsAdapter
import tv.cleanhome.launcher.recommendations.RecommendationsRepository
import tv.cleanhome.launcher.ui.ClockFormat
import tv.cleanhome.launcher.ui.ClockTicker
import tv.cleanhome.launcher.ui.Dialogs
import tv.cleanhome.launcher.ui.HeaderBar
import tv.cleanhome.launcher.ui.TvRowLayoutManager
import tv.cleanhome.launcher.weather.GeocodeResult
import tv.cleanhome.launcher.weather.WeatherController
import java.net.URISyntaxException

/**
 * The home screen: photo background, header (HDMI / Wi-Fi / Settings shortcuts, clock, weather),
 * the recommendations row and the app row.
 *
 * A setup script can preset the weather location with the string extra [EXTRA_WEATHER_CITY]
 * (an empty value turns the weather off).
 */
class HomeActivity : Activity() {

    private val scope = MainScope()

    private lateinit var appsRepository: AppsRepository
    private lateinit var recommendationsRepository: RecommendationsRepository
    private lateinit var usageStore: UsageStore
    private lateinit var hiddenApps: HiddenAppsStore
    private lateinit var tileNames: TileNameStore
    private lateinit var slideshow: SlideshowController
    private lateinit var clockTicker: ClockTicker
    private lateinit var header: HeaderBar
    private lateinit var wifiMonitor: WifiStatusMonitor
    private lateinit var weather: WeatherController

    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var recSection: View
    private lateinit var recTitle: TextView
    private lateinit var recDescription: TextView
    private lateinit var recSource: TextView
    private lateinit var recList: RecyclerView
    private lateinit var appList: RecyclerView
    private lateinit var appsAdapter: AppsAdapter
    private lateinit var recAdapter: RecommendationsAdapter

    private var appsJob: Job? = null
    private var recsJob: Job? = null
    private var recsRefreshJob: Job? = null
    private var weatherLookupJob: Job? = null

    /** HDMI tiles of the last load (including hidden ones), for the HDMI header button. */
    private var hdmiTiles: List<AppEntry> = emptyList()

    /** Adapter position to focus after the next app load. */
    private var pendingAppFocus: Int? = 0
    private var recommendationsExpanded = false
    private var currentRecKey: String? = null
    private var packageReceiverRegistered = false
    private var tvObserverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    private val tvObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleRecommendationsRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        appsRepository = AppsRepository(applicationContext)
        recommendationsRepository = RecommendationsRepository(applicationContext)
        usageStore = UsageStore(applicationContext)
        hiddenApps = HiddenAppsStore(applicationContext)
        tileNames = TileNameStore(applicationContext)

        timeView = findViewById(R.id.clock_time)
        dateView = findViewById(R.id.clock_date)
        recSection = findViewById(R.id.recommendations_section)
        recTitle = findViewById(R.id.recommendation_title)
        recDescription = findViewById(R.id.recommendation_description)
        recSource = findViewById(R.id.recommendation_source)
        recList = findViewById(R.id.recommendation_list)
        appList = findViewById(R.id.app_list)

        // Only hint at the recommendations row while the user is not navigating inside it.
        applyRecommendationsExpanded(expanded = false, animate = false)
        findViewById<View>(R.id.home_root).viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)

        header = HeaderBar(
            hdmiButton = findViewById(R.id.hdmi_button),
            wifiButton = findViewById(R.id.wifi_button),
            settingsButton = findViewById(R.id.settings_button),
            onHdmi = ::openHdmi,
            onWifi = { startSystemScreen(Settings.ACTION_WIFI_SETTINGS, R.string.wifi_button) },
            onSettings = { startSystemScreen(Settings.ACTION_SETTINGS, R.string.settings_button) },
            onSettingsMenu = ::showSettingsMenu,
        )
        wifiMonitor = WifiStatusMonitor(this) { connected -> header.setWifiConnected(connected) }
        weather = WeatherController(
            context = this,
            scope = scope,
            row = findViewById(R.id.weather_row),
            icon = findViewById(R.id.weather_icon),
            temperature = findViewById(R.id.weather_temperature),
        )

        slideshow = SlideshowController(
            firstView = findViewById(R.id.background_a),
            secondView = findViewById(R.id.background_b),
            photos = PhotoRepository(),
            intervalMs = BACKGROUND_INTERVAL_MS,
            fadeMs = BACKGROUND_FADE_MS,
            kenBurns = false,
        )
        clockTicker = ClockTicker(this) {
            // Built per tick so locale and 12/24-hour changes apply within a minute.
            val format = ClockFormat.forContext(this)
            timeView.text = format.time()
            dateView.text = format.date()
        }

        appsAdapter = AppsAdapter(onClick = ::launchTile, onMenu = ::showTileMenu)
        setUpRow(appList, appsAdapter, R.dimen.app_focus_pad_horizontal)

        recAdapter = RecommendationsAdapter(onClick = ::launchRecommendation, onFocused = ::showRecommendationDetails)
        setUpRow(recList, recAdapter, R.dimen.rec_focus_pad_horizontal)

        if (savedInstanceState == null) handleWeatherExtra(intent)
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus != null) applyRecommendationsExpanded(isInside(newFocus, recList), animate = true)
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    /**
     * Collapsed, the row stays as a faint hint (still reachable with UP) and its texts disappear.
     * Positions in the layout never change, so nothing jumps.
     */
    private fun applyRecommendationsExpanded(expanded: Boolean, animate: Boolean) {
        if (recommendationsExpanded == expanded && animate) return
        recommendationsExpanded = expanded
        val listAlpha = if (expanded) 1f else COLLAPSED_ROW_ALPHA
        val textAlpha = if (expanded) 1f else 0f
        val targets = listOf(recList to listAlpha, recTitle to textAlpha, recDescription to textAlpha, recSource to textAlpha)
        for ((view, alpha) in targets) {
            view.animate().cancel()
            if (animate) view.animate().alpha(alpha).setDuration(EXPAND_DURATION_MS).start() else view.alpha = alpha
        }
    }

    private fun setUpRow(list: RecyclerView, adapter: RecyclerView.Adapter<*>, focusPadRes: Int) {
        list.layoutManager = TvRowLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        // The inner padding only makes room for the focus zoom: align the cards with the texts.
        (list.layoutParams as ViewGroup.MarginLayoutParams).marginStart = -resources.getDimensionPixelSize(focusPadRes)
    }

    override fun onStart() {
        super.onStart()
        slideshow.start()
        clockTicker.start()
        wifiMonitor.start()
        weather.start()
        registerPackageReceiver()
    }

    override fun onResume() {
        super.onResume()
        // Re-sort only when returning to the home screen; focus goes back to the first tile.
        pendingAppFocus = 0
        reloadApps()
        reloadRecommendations()
        registerTvObserver()
    }

    override fun onPause() {
        unregisterTvObserver()
        recsRefreshJob?.cancel()
        super.onPause()
    }

    override fun onStop() {
        slideshow.stop()
        clockTicker.stop()
        wifiMonitor.stop()
        weather.stop()
        unregisterPackageReceiver()
        super.onStop()
    }

    override fun onDestroy() {
        findViewById<View>(R.id.home_root).viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Home screen not visible: free photos and posters for the app in front.
            slideshow.releaseImages()
            imageLoader.memoryCache?.clear()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleWeatherExtra(intent)) return
        // HOME pressed while on the home screen: back to the start.
        if (hasWindowFocus()) focusFirstApp()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- Tiles

    private fun reloadApps() {
        appsJob?.cancel()
        appsJob = scope.launch {
            val hidden = hiddenApps.keys()
            val scores = usageStore.scores()
            val names = tileNames.names()
            val tiles = withContext(Dispatchers.IO) {
                appsRepository.loadTiles().map { it.copy(customLabel = names[it.key]) }
            }
            val ranked = withContext(Dispatchers.Default) {
                AppRanker().rank(tiles.filterNot { it.key in hidden }, scores, { it.key }, { it.label })
            }
            hdmiTiles = tiles.filter { it.isHdmiInput }
            header.setHdmiAvailable(hdmiTiles.isNotEmpty())
            appsAdapter.submit(ranked)
            pendingAppFocus?.let { position ->
                pendingAppFocus = null
                focusApp(position)
            }
        }
    }

    private fun focusFirstApp() {
        recList.scrollToPosition(0)
        focusApp(0)
    }

    private fun focusApp(position: Int) {
        val count = appsAdapter.itemCount
        if (count == 0) return
        val target = position.coerceIn(0, count - 1)
        appList.scrollToPosition(target)
        appList.post {
            val view = appList.findViewHolderForAdapterPosition(target)?.itemView
            if (view == null || !view.requestFocus()) appList.requestFocus()
        }
    }

    private fun launchTile(tile: AppEntry) {
        if (startActivitySafely(tile.launchIntent)) {
            usageStore.recordLaunch(tile.key)
        } else {
            toast(getString(R.string.toast_launch_failed, tile.label))
        }
    }

    private fun showTileMenu(tile: AppEntry) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (!tile.isHdmiInput) actions += getString(R.string.menu_app_info) to { openAppInfo(tile.packageName) }
        actions += getString(R.string.menu_rename) to { showRenameDialog(tile) }
        actions += getString(R.string.menu_hide) to { hideTile(tile) }
        Dialogs.showMenu(this, tile.label, actions)
    }

    private fun showRenameDialog(tile: AppEntry) {
        Dialogs.showTextInput(
            activity = this,
            title = getString(R.string.dialog_rename_title),
            message = getString(R.string.dialog_rename_message, tile.defaultLabel),
            initialText = tile.label,
            hint = tile.defaultLabel,
        ) { name ->
            val custom = name.takeUnless { it.isEmpty() || it == tile.defaultLabel }
            tileNames.setName(tile.key, custom)
            toast(if (custom == null) getString(R.string.toast_name_reset, tile.defaultLabel) else getString(R.string.toast_renamed, custom))
            pendingAppFocus = appsAdapter.positionOf(tile)
            reloadApps()
        }
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startActivitySafely(intent)) toast(getString(R.string.toast_app_info_failed))
    }

    private fun hideTile(tile: AppEntry) {
        val position = appsAdapter.positionOf(tile)
        hiddenApps.hide(tile.key)
        toast(getString(R.string.toast_hidden, tile.label))
        pendingAppFocus = position
        reloadApps()
    }

    private fun unhideAll(hiddenCount: Int) {
        if (hiddenCount == 0) {
            toast(getString(R.string.toast_nothing_hidden))
            return
        }
        hiddenApps.unhideAll()
        toast(getString(R.string.toast_unhidden))
        reloadApps()
    }

    private fun registerPackageReceiver() {
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        packageReceiverRegistered = true
    }

    private fun unregisterPackageReceiver() {
        if (!packageReceiverRegistered) return
        unregisterReceiver(packageReceiver)
        packageReceiverRegistered = false
    }

    // ---------------------------------------------------------------- Header

    /** One HDMI input: switch to it. Several: let the user pick (custom names included). */
    private fun openHdmi() {
        val inputs = hdmiTiles
        when (inputs.size) {
            0 -> return
            1 -> launchTile(inputs.single())
            else -> Dialogs.showMenu(
                this,
                getString(R.string.dialog_hdmi_title),
                inputs.map { tile -> tile.label to { launchTile(tile) } },
            )
        }
    }

    private fun startSystemScreen(action: String, nameRes: Int) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startActivitySafely(intent)) toast(getString(R.string.toast_launch_failed, getString(nameRes)))
    }

    /** Long OK on the gear: unhide apps and the weather location. */
    private fun showSettingsMenu() {
        val hiddenCount = hiddenApps.keys().size
        Dialogs.showMenu(
            this,
            getString(R.string.settings_button),
            listOf(
                getString(R.string.menu_unhide_all, hiddenCount) to { unhideAll(hiddenCount) },
                getString(R.string.menu_weather_location) to ::showWeatherLocationDialog,
            ),
        )
    }

    private fun showWeatherLocationDialog() {
        val current = weather.location()
        Dialogs.showTextInput(
            activity = this,
            title = getString(R.string.dialog_weather_title),
            message = getString(R.string.dialog_weather_message),
            initialText = current?.name.orEmpty(),
            hint = getString(R.string.dialog_weather_hint),
            onConfirm = ::applyWeatherCity,
        )
    }

    /** Handles [EXTRA_WEATHER_CITY]; returns true if the intent carried it. */
    private fun handleWeatherExtra(intent: Intent?): Boolean {
        if (intent?.hasExtra(EXTRA_WEATHER_CITY) != true) return false
        val city = intent.getStringExtra(EXTRA_WEATHER_CITY).orEmpty().trim()
        // Consume the extra so that a recreated activity does not apply it again.
        intent.removeExtra(EXTRA_WEATHER_CITY)
        applyWeatherCity(city)
        return true
    }

    /** Empty turns the weather off; otherwise the name is resolved via Open-Meteo geocoding. */
    private fun applyWeatherCity(city: String) {
        weatherLookupJob?.cancel()
        if (city.isEmpty()) {
            weather.setLocation(null)
            toast(getString(R.string.toast_weather_off))
            return
        }
        weatherLookupJob = scope.launch {
            when (val result = weather.lookUp(city)) {
                is GeocodeResult.Found -> {
                    weather.setLocation(result.location)
                    toast(getString(R.string.toast_weather_set, result.location.displayName))
                }
                GeocodeResult.NotFound -> toast(getString(R.string.toast_weather_not_found, city))
                GeocodeResult.Failed -> toast(getString(R.string.toast_weather_lookup_failed))
            }
        }
    }

    // ---------------------------------------------------------------- Recommendations

    private fun reloadRecommendations() {
        recsJob?.cancel()
        recsJob = scope.launch {
            val scores = usageStore.scores()
            val list = withContext(Dispatchers.IO) {
                // Round-robin across packages in the same order as the app row.
                recommendationsRepository.load { packages ->
                    AppRanker().rank(packages.toList(), scores, { it }, { it })
                }
            }
            showRecommendations(list)
        }
    }

    private fun scheduleRecommendationsRefresh() {
        recsRefreshJob?.cancel()
        recsRefreshJob = scope.launch {
            delay(RECOMMENDATIONS_DEBOUNCE_MS)
            reloadRecommendations()
        }
    }

    private fun showRecommendations(list: List<Recommendation>) {
        if (list.isEmpty()) {
            val hadFocus = recSection.hasFocus()
            recSection.visibility = View.GONE
            recAdapter.submit(emptyList())
            currentRecKey = null
            if (hadFocus) focusApp(0)
            return
        }
        recAdapter.submit(list)
        recSection.visibility = View.VISIBLE
        showRecommendationDetails(list.firstOrNull { it.key == currentRecKey } ?: list.first())
    }

    private fun showRecommendationDetails(item: Recommendation) {
        currentRecKey = item.key
        recTitle.text = item.row.title ?: getString(R.string.content_unknown)
        val description = item.row.description?.trim().orEmpty()
        recDescription.text = description
        recDescription.visibility = if (description.isEmpty()) View.INVISIBLE else View.VISIBLE
        recSource.text = item.sourceLabel
    }

    private fun launchRecommendation(item: Recommendation) {
        val started = parseProgramIntent(item)?.let { startActivitySafely(it) } == true ||
            fallbackLaunchIntent(item.packageName)?.let { startActivitySafely(it) } == true
        if (started) {
            // Apps are keyed by package name, so this counts towards the app's tile.
            usageStore.recordLaunch(item.packageName)
        } else {
            toast(getString(R.string.toast_launch_failed, item.sourceLabel))
        }
    }

    private fun parseProgramIntent(item: Recommendation): Intent? {
        val uri = item.row.intentUri?.takeIf { it.isNotBlank() } ?: return null
        return try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                // Plain data URIs (e.g. https://…) go to the source app instead of a chooser.
                if (component == null && `package` == null) setPackage(item.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: URISyntaxException) {
            Log.w(TAG, "Invalid intent_uri from ${item.packageName}: $uri", e)
            null
        }
    }

    private fun fallbackLaunchIntent(packageName: String): Intent? =
        (packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun registerTvObserver() {
        if (tvObserverRegistered) return
        try {
            contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, tvObserver)
            tvObserverRegistered = true
            contentResolver.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, tvObserver)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot observe the TvProvider", e)
        }
    }

    private fun unregisterTvObserver() {
        if (!tvObserverRegistered) return
        contentResolver.unregisterContentObserver(tvObserver)
        tvObserverRegistered = false
    }

    // ---------------------------------------------------------------- Helpers

    private fun startActivitySafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for $intent", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Starting $intent was denied", e)
        false
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        /** String extra: weather location to resolve and store; empty turns the weather off. */
        const val EXTRA_WEATHER_CITY = "weather_city"

        private const val BACKGROUND_INTERVAL_MS = 30_000L
        private const val BACKGROUND_FADE_MS = 1_500L
        private const val RECOMMENDATIONS_DEBOUNCE_MS = 2_000L
        private const val COLLAPSED_ROW_ALPHA = 0.12f
        private const val EXPAND_DURATION_MS = 180L
    }
}
