package app.olauncher.ui

import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import androidx.recyclerview.widget.LinearSmoothScroller
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var isAlphabetDragging = false
    private var pendingAlphabetPosition = RecyclerView.NO_POSITION

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in setOf(
                Constants.FLAG_SET_HOME_APP_1, Constants.FLAG_SET_HOME_APP_2,
                Constants.FLAG_SET_HOME_APP_3, Constants.FLAG_SET_HOME_APP_4,
                Constants.FLAG_SET_HOME_APP_5, Constants.FLAG_SET_HOME_APP_6,
                Constants.FLAG_SET_HOME_APP_7, Constants.FLAG_SET_HOME_APP_8,
                Constants.FLAG_SET_HOME_APP_9, Constants.FLAG_SET_HOME_APP_10,
                Constants.FLAG_SET_HOME_APP_11, Constants.FLAG_SET_HOME_APP_12,
                Constants.FLAG_SET_HOME_APP_13, Constants.FLAG_SET_HOME_APP_14,
                Constants.FLAG_SET_SWIPE_LEFT_APP, Constants.FLAG_SET_SWIPE_RIGHT_APP,
                Constants.FLAG_SET_CLOCK_APP, Constants.FLAG_SET_CALENDAR_APP,
                Constants.FLAG_SET_SCREEN_TIME_APP,
            ))
            binding.search.queryHint = "Please select an app"
        try {
            val searchTextView = binding.search.findViewById<TextView>(R.id.search_src_text)
            if (searchTextView != null) searchTextView.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }

        configureAlphabetScrollerSide()
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.filter.filter(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            prefs,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    findNavController().popBackStack()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
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
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS) {
                    newSet.remove(appModel.appPackage) // for backward compatibility
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                } else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    findNavController().popBackStack()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    SettingsFragment.show(parentFragmentManager)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
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
            rowLongPressListener = {
                SettingsFragment.show(parentFragmentManager) { flag ->
                    // Pop back to home first, then open the requested list
                    findNavController().popBackStack(R.id.mainFragment, false)
                    findNavController().navigate(
                        R.id.action_mainFragment_to_appListFragment,
                        androidx.core.os.bundleOf(app.olauncher.data.Constants.Key.FLAG to flag)
                    )
                }
            }
        )
        adapter.fragmentManager = childFragmentManager
        adapter.onListUpdated = {
            if (_binding != null) {
                updateAlphabetScrollerVisibility()
            }
        }

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.addOnScrollListener(getScrollIndicatorListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)

        initAlphabetScroller()
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                it?.let { appModels ->
                    adapter.setAppList(appModels.toMutableList())
                    adapter.filter.filter(binding.search.query)
                }
            }
        }
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
                Constants.FLAG_SET_HOME_APP_9 -> prefs.appName9 = name
                Constants.FLAG_SET_HOME_APP_10 -> prefs.appName10 = name
                Constants.FLAG_SET_HOME_APP_11 -> prefs.appName11 = name
                Constants.FLAG_SET_HOME_APP_12 -> prefs.appName12 = name
                Constants.FLAG_SET_HOME_APP_13 -> prefs.appName13 = name
                Constants.FLAG_SET_HOME_APP_14 -> prefs.appName14 = name
            }
            findNavController().popBackStack()
        }
    }

    private fun getScrollIndicatorListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollIndicator(recyclerView)
                binding.scrollIndicator?.show()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE)
                    binding.scrollIndicator?.scheduleHide()
            }
        }
    }

    /** Update the scroll-position indicator pill. */
    private fun updateScrollIndicator(rv: RecyclerView) {
        val indicator = binding.scrollIndicator ?: return
        val total = adapter.itemCount
        if (total == 0) return
        val first = linearLayoutManager.findFirstVisibleItemPosition()
        val last = linearLayoutManager.findLastVisibleItemPosition()
        val visible = (last - first + 1).coerceAtLeast(1)
        val progress = if (total > visible) first.toFloat() / (total - visible).toFloat() else 0f
        indicator.setScrollProgress(progress)
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (isAlphabetDragging) return
                if (!::adapter.isInitialized || !::linearLayoutManager.isInitialized) return
                val first = findTopVisibleAdapterPosition(recyclerView)
                if (first != RecyclerView.NO_POSITION) {
                    val letter = adapter.getSectionLetterAt(first)
                    binding.alphabetScroller?.highlightLetter(letter)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        if (binding.alphabetScroller?.isUserTouchActive() != true) {
                            isAlphabetDragging = false
                            pendingAlphabetPosition = RecyclerView.NO_POSITION
                        }
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        val first = findTopVisibleAdapterPosition(recyclerView)
                        if (first != RecyclerView.NO_POSITION) {
                            val letter = adapter.getSectionLetterAt(first)
                            binding.alphabetScroller?.highlightLetter(letter)
                        }
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                        binding.alphabetScroller?.clearScrollHighlight()
                        // Fade all items back in when scrolling stops
                        adapter.clearActiveLetter()
                    }
                }
            }
        }
    }

    private fun findTopVisibleAdapterPosition(recyclerView: RecyclerView): Int {
        if (recyclerView.childCount == 0) return RecyclerView.NO_POSITION
        val targetTop = recyclerView.paddingTop
        var bestPos = RecyclerView.NO_POSITION
        var bestDelta = Int.MAX_VALUE

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val top = child.top
            val bottom = child.bottom
            val intersectsTop = targetTop in top..bottom
            val delta = kotlin.math.abs(top - targetTop)

            if (intersectsTop || delta < bestDelta) {
                bestDelta = delta
                bestPos = recyclerView.getChildAdapterPosition(child)
                if (intersectsTop) break
            }
        }

        return bestPos
    }

    private fun initAlphabetScroller() {
        binding.alphabetScroller?.setSide(isAlphabetScrollerOnRight())
        binding.alphabetScroller?.setWaveStyle(prefs.waveStyle)
        binding.alphabetScroller?.setAvailableLetters(adapter.getAvailableSectionLetters())
        binding.alphabetScroller?.onScrollProgress = null
        // Only scroll when the focused letter has at least one app — no nearest-letter fallback.
        binding.alphabetScroller?.onLetterSelected = { letter ->
            isAlphabetDragging = true
            // Fade non-active sections while dragging
            adapter.setActiveLetter(letter)
            if (letter == '★') {
                // Scroll to top (favorites section)
                pendingAlphabetPosition = 0
                binding.recyclerView.stopScroll()
                linearLayoutManager.scrollToPositionWithOffset(0, 0)
            } else {
                val position = adapter.getExactPositionForLetter(letter)
                if (position != RecyclerView.NO_POSITION) {
                    pendingAlphabetPosition = position
                    binding.recyclerView.stopScroll()
                    linearLayoutManager.scrollToPositionWithOffset(position, 0)
                }
            }
        }
        binding.alphabetScroller?.onScrollingEnded = {
            isAlphabetDragging = false
            adapter.clearActiveLetter()
            if (pendingAlphabetPosition != RecyclerView.NO_POSITION) {
                smoothScrollToPosition(pendingAlphabetPosition)
                pendingAlphabetPosition = RecyclerView.NO_POSITION
            }
        }
        updateAlphabetScrollerVisibility()
    }

    private fun configureAlphabetScrollerSide() {
        val params = binding.alphabetScroller?.layoutParams as? FrameLayout.LayoutParams ?: return
        params.gravity = (if (isAlphabetScrollerOnRight()) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        params.marginStart = 0.dpToPx()
        params.marginEnd = 0.dpToPx()
        params.bottomMargin = 24.dpToPx()
        binding.alphabetScroller?.layoutParams = params
    }

    private fun isAlphabetScrollerOnRight(): Boolean = prefs.appLabelAlignment != Gravity.END

    private fun updateAlphabetScrollerVisibility() {
        val visible = ::adapter.isInitialized && adapter.hasVisibleApps()
        binding.alphabetScroller?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.alphabetScroller?.setAvailableLetters(adapter.getAvailableSectionLetters())
        }
        if (!visible) binding.alphabetScroller?.clearTouchState()
    }

    private fun smoothScrollToPosition(position: Int) {
        val smoothScroller = object : LinearSmoothScroller(requireContext()) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 40f / displayMetrics.densityDpi
            }

            override fun calculateTimeForScrolling(dx: Int): Int {
                // Clamp so very long jumps don't feel sluggish
                return super.calculateTimeForScrolling(dx).coerceAtMost(350)
            }
        }
        smoothScroller.targetPosition = position
        linearLayoutManager.startSmoothScroll(smoothScroller)
    }

    private fun checkMessageAndExit() {
        findNavController().popBackStack()
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun showShortcutsPopup(appModel: AppModel, anchor: android.view.View) {
        if (appModel !is AppModel.App) return
        try {
            val launcherApps = requireContext().getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val query = android.content.pm.LauncherApps.ShortcutQuery().apply {
                setQueryFlags(
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                )
                setPackage(appModel.appPackage)
            }
            val shortcuts = try { launcherApps.getShortcuts(query, appModel.user) } catch (_: Exception) { null }
            if (shortcuts.isNullOrEmpty()) {
                requireContext().showToast(getString(R.string.no_shortcuts))
                return
            }
            val glassContext = android.view.ContextThemeWrapper(requireContext(), R.style.GlassPopupTheme)
            val popup = android.widget.PopupMenu(glassContext, anchor)
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

    override fun onStart() {
        super.onStart()
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onDestroyView() {
        binding.alphabetScroller?.onLetterSelected = null
        binding.alphabetScroller?.onScrollProgress = null
        binding.alphabetScroller?.onScrollingEnded = null
        super.onDestroyView()
        _binding = null
    }
}
