package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall
import app.olauncher.listener.OnSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Inline app list
    private lateinit var appDrawerAdapter: AppDrawerAdapter
    private lateinit var appListLayoutManager: LinearLayoutManager
    private var pendingAlphabetJump: Char? = null
    private var isAlphabetDragging = false
    private var homeFavoritesMode = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        initAppList()

        binding.root.doOnLayout {
            applyNiagaraHomeOffset()
        }
    }

    override fun onResume() {
        super.onResume()
        populateDateTime()
        applyClockStyle()
        viewModel.isOlauncherDefault()
        viewModel.getAppList()
        binding.root.post {
            applyNiagaraHomeOffset()
        }
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    private fun applyNiagaraHomeOffset() {
        val scrollView = binding.homeAppsScrollView ?: return
        if (scrollView.height <= 0) return

        val targetTopMargin = (scrollView.height * 0.45f).toInt().coerceAtLeast(96)
        val params = binding.dateTimeLayout.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.topMargin == targetTopMargin) return

        params.topMargin = targetTopMargin
        binding.dateTimeLayout.layoutParams = params
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName ?: "",
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName ?: "",
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    showSettingsSheet()
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateDateTime()
            populateBattery()
            binding.alphabetScroller?.setWaveStyle(prefs.waveStyle)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        viewModel.showRecentApps.observe(viewLifecycleOwner) {
            binding.recents.performClick()
        }
        viewModel.appList.observe(viewLifecycleOwner) { appModels ->
            appModels?.let {
                if (::appDrawerAdapter.isInitialized) {
                    appDrawerAdapter.setAppList(it.toMutableList())
                    updateAlphabetScrollerVisibility()
                }
            }
        }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeAppsScrollView?.onFlingDown = { swipeDownAction() }
        // Long-press on empty area of scroll view → open settings
        binding.homeAppsScrollView?.onLongPress = { showSettingsSheet() }
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

    }

    // -----------------------------------------------------------------------
    // Inline app list (formerly AppDrawerFragment)
    // -----------------------------------------------------------------------

    private fun initAppList() {
        appDrawerAdapter = AppDrawerAdapter(
            flag = Constants.FLAG_LAUNCH_APP,
            appLabelGravity = prefs.appLabelAlignment,
            prefs = prefs,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, Constants.FLAG_LAUNCH_APP)
            },
            appInfoListener = { appModel ->
                openAppInfo(requireContext(), appModel.user, appModel.appPackage)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }
                    is AppModel.App -> {
                        requireContext().apply {
                            if (isSystemApp(appModel.appPackage, appModel.user))
                                showToast(getString(R.string.system_app_cannot_delete))
                            else
                                uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                appDrawerAdapter.appFilteredList.removeAt(position)
                appDrawerAdapter.notifyItemRemoved(position)
                appDrawerAdapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                newSet.add(appModel.appPackage + "|" + appModel.user.toString())
                prefs.hiddenApps = newSet

                viewModel.getAppList()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.shortcutId
                    is AppModel.App -> appModel.appPackage
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            appShortcutsListener = { appModel, anchor ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
                    showShortcutsPopup(appModel, anchor)
                else
                    requireContext().showToast(getString(R.string.no_shortcuts))
            },
            appFavoriteListener = { appModel, shouldFavorite ->
                if (appModel is AppModel.App) {
                    if (shouldFavorite)
                        prefs.addFavorite(appModel.appPackage, appModel.user.toString())
                    else
                        prefs.removeFavorite(appModel.appPackage, appModel.user.toString())
                    viewModel.getAppList()
                    viewModel.refreshHome(true)
                }
            },
            rowLongPressListener = { showSettingsSheet() },
        )
        appDrawerAdapter.fragmentManager = childFragmentManager
        appDrawerAdapter.onListUpdated = {
            if (_binding != null) {
                updateAlphabetScrollerVisibility()
                performPendingAlphabetJump()
            }
        }

        appListLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun canScrollVertically() = false // let the outer NestedScrollView handle scrolling
        }

        binding.appListView.let { rv ->
            rv.layoutManager = appListLayoutManager
            rv.adapter = appDrawerAdapter
            rv.itemAnimator = null
        }

        // Track scroll position to show/hide favorites vs AZ sections
        binding.homeAppsScrollView?.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                updateSectionVisibility(scrollY)
            }
        )

        // Home at-rest state: show only Favorites section.
        appDrawerAdapter.setHomeFavoritesOnly(true)

        initAlphabetScroller()
    }

    /**
     * Home behavior:
     * - At top: show Favorites only.
     * - As soon as scrolling starts: show full A-Z list (including favorite apps in their letters).
     */
    private fun updateSectionVisibility(scrollY: Int) {
        if (!::appDrawerAdapter.isInitialized) return

        val scrollerTouchActive = binding.alphabetScroller?.isUserTouchActive() == true
        if (isAlphabetDragging && !scrollerTouchActive) {
            isAlphabetDragging = false
        }

        if (isAlphabetDragging) return
        val shouldShowFavorites = when {
            homeFavoritesMode -> scrollY <= 28
            else -> scrollY <= 8
        }
        if (homeFavoritesMode != shouldShowFavorites) {
            homeFavoritesMode = shouldShowFavorites
            appDrawerAdapter.setHomeFavoritesOnly(homeFavoritesMode)
        }
        updateAlphabetHighlightFromScroll(scrollY)
    }

    private fun updateAlphabetHighlightFromScroll(scrollY: Int) {
        val scroller = binding.alphabetScroller ?: return
        val targetPos = findTopVisibleAdapterPosition()
        if (targetPos == RecyclerView.NO_POSITION) return

        val letter = appDrawerAdapter.getSectionLetterAt(targetPos)
        scroller.highlightLetter(letter)
    }

    private fun findTopVisibleAdapterPosition(): Int {
        val scrollView = binding.homeAppsScrollView ?: return RecyclerView.NO_POSITION
        val rv = binding.appListView
        if (rv.childCount == 0) return RecyclerView.NO_POSITION

        val scrollLoc = IntArray(2)
        val rvLoc = IntArray(2)
        scrollView.getLocationOnScreen(scrollLoc)
        rv.getLocationOnScreen(rvLoc)

        val viewportTop = scrollLoc[1] + scrollView.paddingTop
        val firstVisibleInRv = (viewportTop - rvLoc[1]).coerceAtLeast(0)

        var bestPos = RecyclerView.NO_POSITION
        var smallestDelta = Int.MAX_VALUE

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val top = child.top
            val bottom = child.bottom
            val intersects = firstVisibleInRv in top..bottom
            val delta = kotlin.math.abs(top - firstVisibleInRv)

            if (intersects || delta < smallestDelta) {
                smallestDelta = delta
                bestPos = rv.getChildAdapterPosition(child)
                if (intersects) break
            }
        }

        return bestPos
    }

    private fun initAlphabetScroller() {
        val scroller = binding.alphabetScroller ?: return
        scroller.setSide(isAlphabetScrollerOnRight())
        scroller.setWaveStyle(prefs.waveStyle)
        scroller.setAvailableLetters(appDrawerAdapter.getAvailableSectionLetters())

        // Home should jump by section letter; avoid progress-based scrolling here because it
        // races with favorites/A-Z mode switches and can override the target jump position.
        scroller.onScrollProgress = null

        scroller.onLetterSelected = { letter ->
            isAlphabetDragging = true
            // Fade non-active sections while dragging the alphabet strip
            appDrawerAdapter.setActiveLetter(letter)
            pendingAlphabetJump = letter
            val targetFavoritesMode = letter == '★'
            homeFavoritesMode = targetFavoritesMode
            appDrawerAdapter.setHomeFavoritesOnly(targetFavoritesMode)
            // Jump immediately for responsive drag; onListUpdated still acts as a safety retry.
            performPendingAlphabetJump()
        }
        scroller.onScrollingEnded = {
            // Restore all items to full opacity when user lifts finger
            isAlphabetDragging = false
            appDrawerAdapter.clearActiveLetter()
            snapHomeListToCurrentSection()
            updateSectionVisibility(binding.homeAppsScrollView?.scrollY ?: 0)
        }
        updateAlphabetScrollerVisibility()
    }

    private fun snapHomeListToCurrentSection() {
        val topPos = findTopVisibleAdapterPosition()
        if (topPos == RecyclerView.NO_POSITION) return

        val currentLetter = appDrawerAdapter.getSectionLetterAt(topPos)
        val sectionPosition = appDrawerAdapter.getPositionForLetter(currentLetter)
        if (sectionPosition == RecyclerView.NO_POSITION) return

        scrollAppListToPosition(sectionPosition, smooth = true)
    }

    private fun isAlphabetScrollerOnRight(): Boolean = prefs.appLabelAlignment != Gravity.END

    private fun updateAlphabetScrollerVisibility() {
        val visible = ::appDrawerAdapter.isInitialized && appDrawerAdapter.hasVisibleApps()
        binding.alphabetScroller?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.alphabetScroller?.setAvailableLetters(appDrawerAdapter.getAvailableSectionLetters())
        }
        if (!visible) binding.alphabetScroller?.clearTouchState()
    }

    /** Scroll the outer NestedScrollView so [position] in the app list is visible at the top. */
    private fun scrollAppListToPosition(position: Int, smooth: Boolean) {
        val rv = binding.appListView
        rv.post {
            val child = appListLayoutManager.findViewByPosition(position)
            if (child != null) {
                val scrollY = child.top + rv.top
                if (smooth) binding.homeAppsScrollView?.smoothScrollTo(0, scrollY)
                else binding.homeAppsScrollView?.scrollTo(0, scrollY)
            } else {
                // View not yet laid out — estimate using height of the first visible child
                val firstChild = appListLayoutManager.findViewByPosition(
                    appListLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
                )
                val itemHeight = firstChild?.height?.takeIf { it > 0 }
                    ?: (rv.height / appListLayoutManager.childCount.coerceAtLeast(1))
                val targetY = rv.top + position * itemHeight
                if (smooth) binding.homeAppsScrollView?.smoothScrollTo(0, targetY)
                else binding.homeAppsScrollView?.scrollTo(0, targetY)
            }
        }
    }

    /**
     * Applies deferred alphabet jump after adapter mode/list updates settle.
     * Needed when jumping from favorites-only mode into A-Z mode.
     */
    private fun performPendingAlphabetJump() {
        val letter = pendingAlphabetJump ?: return
        if (letter == '★') {
            pendingAlphabetJump = null
            binding.homeAppsScrollView?.scrollTo(0, 0)
            return
        }
        val position = appDrawerAdapter.getPositionForLetter(letter)
        if (position != RecyclerView.NO_POSITION) {
            pendingAlphabetJump = null
            scrollAppListToPosition(position, smooth = false)
        }
    }

    // -----------------------------------------------------------------------
    // showAppList — opens AppDrawerFragment for special modes (set-app, hidden, etc.)
    // -----------------------------------------------------------------------

    private fun showSettingsSheet() {
        SettingsFragment.show(parentFragmentManager) { flag ->
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                androidx.core.os.bundleOf(Constants.Key.FLAG to flag)
            )
        }
    }

    private fun showAppList(flag: Int, isAppSet: Boolean = false, hideAppsOnly: Boolean = false) {
        findNavController().navigate(
            R.id.action_mainFragment_to_appListFragment,
            bundleOf(
                Constants.Key.FLAG to flag,
                "is_app_set" to isAppSet,
                "hide_apps_only" to hideAppsOnly
            )
        )
    }

    private fun launchApp(name: String, pkg: String, className: String, user: String) {
        val launcher = context?.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userHandle = getUserHandleFromString(requireContext(), user)

        if (className.isNotBlank()) {
            val intent = Intent().setClassName(pkg, className)
            launcher.startMainActivity(intent.component, userHandle, null, null)
        } else if (requireContext().isPackageInstalled(pkg)) {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                launcher.startMainActivity(intent.component, userHandle, null, null)
            } else {
                requireContext().showToast(R.string.app_not_found)
            }
        } else {
            requireContext().showToast(R.string.app_not_found)
        }
    }

    private fun populateBattery() {
        if (!prefs.showBattery) {
            binding.tvBattery?.isVisible = false
            return
        }
        try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = requireContext().registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level != -1 && scale != -1) {
                val pct = level * 100 / scale
                binding.tvBattery?.text = "$pct%"
                binding.tvBattery?.isVisible = true
            } else {
                binding.tvBattery?.isVisible = false
            }
        } catch (_: Exception) {
            binding.tvBattery?.isVisible = false
        }
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        if (prefs.dateTimeVisibility != Constants.DateTime.OFF) {
            val dateVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)
            binding.date.isVisible = dateVisible
            if (dateVisible) {
                val dateFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
                binding.date.text = dateFormat.format(Date())
            }
        }
        applyClockStyle()
        populateScreenTime()
    }

    private fun applyClockStyle() {
        val clock = binding.clock
        when (prefs.clockStyle) {
            Constants.ClockStyle.SPLIT -> {
                clock.format12Hour = "h\nmm"
                clock.format24Hour = "HH\nmm"
                clock.letterSpacing = -0.01f
                clock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                clock.textSize = resources.getDimension(R.dimen.time_size) / resources.displayMetrics.scaledDensity
            }
            Constants.ClockStyle.MONO -> {
                clock.format12Hour = "hh:mm"
                clock.format24Hour = "HH:mm"
                clock.letterSpacing = 0.03f
                clock.typeface = Typeface.MONOSPACE
                clock.textSize = (resources.getDimension(R.dimen.time_size) * 0.82f) / resources.displayMetrics.scaledDensity
            }
            else -> {
                clock.format12Hour = "h:mm"
                clock.format24Hour = "HH:mm"
                clock.letterSpacing = -0.03f
                clock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                clock.textSize = resources.getDimension(R.dimen.time_size) / resources.displayMetrics.scaledDensity
            }
        }
    }

    private fun populateScreenTime() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val showScreenTime = prefs.showStatusBar.not() && requireContext().appUsagePermissionGranted()
            binding.tvScreenTime.isVisible = showScreenTime
        } else {
            binding.tvScreenTime.isVisible = false
        }
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isBlank()) {
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                intent.setClassName("com.google.android.apps.wellbeing", "com.google.android.apps.wellbeing.home.TopLevelSettingsActivity")
                startActivity(intent)
            } catch (e: Exception) {
                requireContext().showToast(R.string.digital_wellbeing_message)
            }
        } else {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName ?: "",
                prefs.screenTimeAppUser
            )
        }
    }

    private fun setHomeAlignment(alignment: Int = prefs.homeAlignment) {
        val dateTimeGravity = when (alignment) {
            Gravity.START -> Gravity.START
            Gravity.CENTER -> Gravity.CENTER_HORIZONTAL
            Gravity.END -> Gravity.END
            else -> Gravity.START
        }
        val layoutParams = binding.dateTimeLayout.layoutParams as LinearLayout.LayoutParams
        layoutParams.gravity = dateTimeGravity
        binding.dateTimeLayout.layoutParams = layoutParams
        binding.dateTimeLayout.gravity = dateTimeGravity
    }

    private fun getSwipeGestureListener(context: Context): OnSwipeTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onLongClick() {
                showSettingsSheet()
            }

            override fun onSwipeUp() {
                // Swipe up on empty area — scroll to app list
                binding.homeAppsScrollView?.smoothScrollTo(0, binding.appListView.top)
            }

            override fun onSwipeDown() {
                swipeDownAction()
            }

            override fun onSwipeLeft() {
                if (prefs.swipeLeftEnabled) {
                    launchApp(
                        "Left Swipe App",
                        prefs.appPackageSwipeLeft,
                        prefs.appActivityClassNameSwipeLeft ?: "",
                        prefs.appUserSwipeLeft
                    )
                }
            }

            override fun onSwipeRight() {
                if (prefs.swipeRightEnabled) {
                    launchApp(
                        "Right Swipe App",
                        prefs.appPackageSwipeRight,
                        prefs.appActivityClassNameRight ?: "",
                        prefs.appUserSwipeRight
                    )
                }
            }
        }
    }

    private fun swipeDownAction() {
        if (prefs.swipeDownAction == Constants.SwipeDownAction.NOTIFICATIONS) {
            expandNotificationDrawer(requireContext())
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun showShortcutsPopup(appModel: AppModel, anchor: View) {
        if (appModel !is AppModel.App) return
        try {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().apply {
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                )
                setPackage(appModel.appPackage)
            }
            val shortcuts = try { launcherApps.getShortcuts(query, appModel.user) } catch (_: Exception) { null }
            if (shortcuts.isNullOrEmpty()) {
                requireContext().showToast(getString(R.string.no_shortcuts))
                return
            }
            val glassContext = ContextThemeWrapper(requireContext(), R.style.GlassPopupTheme)
            val popup = PopupMenu(glassContext, anchor)
            shortcuts.take(5).forEachIndexed { index, shortcut ->
                popup.menu.add(0, index, index, shortcut.shortLabel ?: shortcut.id)
            }
            popup.setOnMenuItemClickListener { item ->
                val shortcut = shortcuts[item.itemId]
                try { launcherApps.startShortcut(shortcut, null, null) }
                catch (_: Exception) { requireContext().showToast(getString(R.string.unable_to_launch_app)) }
                true
            }
            popup.show()
        } catch (_: Exception) {
            requireContext().showToast(getString(R.string.no_shortcuts))
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.window?.insetsController?.show(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.window?.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    override fun onDestroyView() {
        binding.alphabetScroller?.onLetterSelected = null
        binding.alphabetScroller?.onScrollProgress = null
        binding.alphabetScroller?.onScrollingEnded = null
        super.onDestroyView()
        _binding = null
    }
}
