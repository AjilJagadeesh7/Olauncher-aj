package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Typeface
import android.os.UserHandle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterSectionHeaderBinding
import java.text.Normalizer

/**
 * Sealed wrapper that the adapter holds in its flat display list.
 * A Header appears once per letter section, immediately before the first app for that letter.
 */
sealed class DrawerItem {
    data class Header(val letter: String) : DrawerItem()
    data class AppItem(
        val appModel: AppModel,
        val sectionLetter: Char,
        val instanceKey: String,
    ) : DrawerItem()

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DrawerItem>() {
            override fun areItemsTheSame(a: DrawerItem, b: DrawerItem) = when {
                a is Header && b is Header -> a.letter == b.letter
                a is AppItem && b is AppItem -> a.instanceKey == b.instanceKey
                else -> false
            }

            override fun areContentsTheSame(a: DrawerItem, b: DrawerItem) = a == b
        }
    }
}

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val prefs: Prefs,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val appShortcutsListener: (AppModel, android.view.View) -> Unit = { _, _ -> },
    private val appFavoriteListener: (AppModel, Boolean) -> Unit = { _, _ -> },
    private val rowLongPressListener: () -> Unit = {},
) : androidx.recyclerview.widget.ListAdapter<DrawerItem, RecyclerView.ViewHolder>(DrawerItem.DIFF), Filterable {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
    }

    private var autoLaunch = true
    private var isBangSearch = false
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()
    private var favoriteKeysSnapshot: Set<String> = emptySet()
    private var includeFavoritesSectionSnapshot = false
    private var allDisplayItems: List<DrawerItem> = emptyList()

    private enum class VisibilityMode {
        ALL,
        FAVORITES_ONLY,
        AZ_ONLY,
    }

    var onListUpdated: (() -> Unit)? = null

    /** Raw sorted app list from app loader (A-Z). */
    var appsList: MutableList<AppModel> = mutableListOf()

    /** Filtered flat list of apps (without headers), mirrors the search results. */
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    /** FragmentManager used to show the context-menu bottom sheet. Set by the host fragment. */
    var fragmentManager: FragmentManager? = null

    // ------- Scroll-fade support -------

    /** When non-null, only items whose section letter == activeLetter are fully opaque;
     *  all others fade to dimAlpha. */
    var activeSectionLetter: Char? = null
        private set
    private var touchVisibilityMode: VisibilityMode = VisibilityMode.ALL
    // Base mode when alphabet strip is not actively dragged.
    // Default to ALL so non-home hosts (AppDrawer) keep full list visible.
    private var homeVisibilityMode: VisibilityMode = VisibilityMode.ALL
    private val dimAlpha = 0.15f

    /** Call from a scroll listener while the user is actively dragging the alphabet strip. */
    fun setActiveLetter(letter: Char?) {
        val previousMode = effectiveVisibilityMode()
        activeSectionLetter = letter?.let { normalizeSectionLetter(it) }
        touchVisibilityMode = when (activeSectionLetter) {
            '★' -> VisibilityMode.FAVORITES_ONLY
            null -> VisibilityMode.ALL
            else -> VisibilityMode.AZ_ONLY
        }
        if (effectiveVisibilityMode() != previousMode)
            applyVisibilityMode()
        else
            notifyItemRangeChanged(0, itemCount, "PRESENTATION_CHANGE")
    }

    /** Call when scrolling stops to fade everything back in. */
    fun clearActiveLetter() {
        val previousMode = effectiveVisibilityMode()
        activeSectionLetter = null
        touchVisibilityMode = VisibilityMode.ALL
        if (effectiveVisibilityMode() != previousMode)
            applyVisibilityMode()
        else
            notifyItemRangeChanged(0, itemCount, "PRESENTATION_CHANGE")
    }

    /**
     * Base section visibility when alphabet strip is idle.
     * true  -> favorites only
     * false -> A-Z only
     */
    fun setHomeFavoritesOnly(enabled: Boolean) {
        val next = if (enabled) VisibilityMode.FAVORITES_ONLY else VisibilityMode.AZ_ONLY
        if (homeVisibilityMode == next) return
        homeVisibilityMode = next
        applyVisibilityMode()
    }

    // ------- ViewType -------

    override fun getItemViewType(position: Int) =
        if (getItem(position) is DrawerItem.Header) TYPE_HEADER else TYPE_APP

    // ------- ViewHolder creation -------

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderViewHolder(
                AdapterSectionHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        } else {
            AppViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

    // ------- Bind -------

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>,
    ) {
        val item = getItem(position)

        // Fast presentation-only rebind (alpha/visibility) while alphabet scroller is active.
        if (payloads.contains("PRESENTATION_CHANGE")) {
            applyPresentation(holder, item)
            return
        }

        when {
            holder is HeaderViewHolder && item is DrawerItem.Header -> {
                holder.bind(item.letter)
                applyPresentation(holder, item)
            }
            holder is AppViewHolder && item is DrawerItem.AppItem -> {
                val appModel = item.appModel
                val fm = fragmentManager
                holder.bind(
                    flag = flag,
                    appLabelGravity = appLabelGravity,
                    myUserHandle = myUserHandle,
                    appModel = appModel,
                    prefs = prefs,
                    clickListener = appClickListener,
                    onLongPress = { model ->
                        val safeFm = fm ?: return@bind
                        if (safeFm.findFragmentByTag("context_menu") == null) {
                            // Find AppModel index in appFilteredList for hide listener
                            val filteredIndex = appFilteredList.indexOf(model)
                            ContextMenuSheet.newInstance(
                                appModel = model,
                                flag = flag,
                                appDeleteListener = { m -> appDeleteListener(m) },
                                appInfoListener = { m -> appInfoListener(m) },
                                appHideListener = { m ->
                                    if (filteredIndex != -1) appHideListener(m, filteredIndex)
                                },
                                appRenameListener = appRenameListener,
                                appShortcutsListener = { m ->
                                    appShortcutsListener(m, holder.itemView)
                                },
                                appFavoriteListener = appFavoriteListener,
                            ).show(safeFm, "context_menu")
                        }
                    },
                    emptyRowLongPress = rowLongPressListener,
                )
                applyPresentation(holder, item)
            }
        }
    }

    private fun applyPresentation(holder: RecyclerView.ViewHolder, item: DrawerItem) {
        val sectionOfItem = getSectionLetterForItem(item)
        if (holder.itemView.visibility != View.VISIBLE) holder.itemView.visibility = View.VISIBLE

        val active = activeSectionLetter
        val targetAlpha = if (active == null || sectionOfItem == active) 1f else dimAlpha
        holder.itemView.animate().alpha(targetAlpha).setDuration(100).start()
    }

    // ------- Filter -------

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = charSearch?.startsWith(" ")?.not() ?: true

                val filtered = (if (charSearch.isNullOrBlank()) appsList
                else appsList.filter { app ->
                    appLabelMatches(app.appLabel, charSearch)
                }).toMutableList()

                val filterResults = FilterResults()
                filterResults.values = filtered
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    favoriteKeysSnapshot = prefs.favoriteApps.toSet()
                    val showFavoritesSection = constraint.isNullOrBlank() && flag == Constants.FLAG_LAUNCH_APP
                    includeFavoritesSectionSnapshot = showFavoritesSection
                    allDisplayItems = buildDisplayList(items, favoriteKeysSnapshot, showFavoritesSection)
                    submitList(itemsForCurrentMode()) {
                        onListUpdated?.invoke()
                        autoLaunch()
                    }
                }
            }
        }
    }

    // ------- Helpers -------

    /**
     * Inserts a section header before the first app of each letter section.
     * Favorites (items at start before letter A) get a "★" header.
     */
    private fun buildDisplayList(
        apps: List<AppModel>,
        favKeys: Set<String>,
        includeFavoritesSection: Boolean,
    ): List<DrawerItem> {
        val result = mutableListOf<DrawerItem>()
        var lastLetter: Char? = null

        if (includeFavoritesSection) {
            val favoriteApps = apps.filter { app ->
                app.appPackage.isNotEmpty() && "${app.appPackage}|${app.user}" in favKeys
            }
            if (favoriteApps.isNotEmpty()) {
                result.add(DrawerItem.Header("★"))
                favoriteApps.forEach { app ->
                    result.add(
                        DrawerItem.AppItem(
                            appModel = app,
                            sectionLetter = '★',
                            instanceKey = "fav|${modelStableKey(app)}",
                        )
                    )
                }
            }
        }

        for (app in apps) {
            if (app.appPackage.isEmpty()) {
                // padding sentinel — include as AppItem but no header
                result.add(
                    DrawerItem.AppItem(
                        appModel = app,
                        sectionLetter = '#',
                        instanceKey = "pad|${result.size}",
                    )
                )
                continue
            }
            val letter = getSectionLetter(app)
            if (letter != lastLetter) {
                result.add(DrawerItem.Header(letter.toString()))
                lastLetter = letter
            }
            result.add(
                DrawerItem.AppItem(
                    appModel = app,
                    sectionLetter = letter,
                    instanceKey = "all|${modelStableKey(app)}",
                )
            )
        }
        return result
    }

    private fun autoLaunch() {
        try {
            if (appFilteredList.count { it.appPackage.isNotEmpty() } == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.size > 0
            ) appClickListener(appFilteredList.first { it.appPackage.isNotEmpty() })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        return (appLabel.contains(charSearch.trim(), true) or
                Normalizer.normalize(appLabel, Normalizer.Form.NFD)
                    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                    .replace(Regex("[-_+,. ]"), "")
                    .contains(charSearch, true))
    }

    fun setAppList(appsList: MutableList<AppModel>) {
        // Add empty app for bottom padding in recyclerview
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        favoriteKeysSnapshot = prefs.favoriteApps.toSet()
        val showFavoritesSection = flag == Constants.FLAG_LAUNCH_APP
        includeFavoritesSectionSnapshot = showFavoritesSection
        allDisplayItems = buildDisplayList(appsList, favoriteKeysSnapshot, showFavoritesSection)
        submitList(itemsForCurrentMode()) {
            onListUpdated?.invoke()
        }
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it.appPackage.isNotEmpty() }
        if (first != null) appClickListener(first)
    }

    fun hasVisibleApps(): Boolean = appFilteredList.any { it.appPackage.isNotEmpty() }

    fun getAvailableSectionLetters(): List<Char> {
        val source = if (allDisplayItems.isNotEmpty()) allDisplayItems else currentList
        val seen = linkedSetOf<Char>()

        source.forEach { item ->
            when (item) {
                is DrawerItem.Header -> {
                    seen.add(item.letter.firstOrNull() ?: '#')
                }
                is DrawerItem.AppItem -> {
                    if (item.appModel.appPackage.isNotEmpty()) {
                        seen.add(item.sectionLetter)
                    }
                }
            }
        }

        val canonical = listOf('★') + ('A'..'Z').toList() + '#'
        return canonical.filter { it in seen }
    }

    /**
     * Returns the position in the *display* list (including headers) for the given letter.
     * Returns [RecyclerView.NO_POSITION] when no app exists for that letter.
     */
    fun getExactPositionForLetter(letter: Char): Int {
        val targetLetter = normalizeSectionLetter(letter)
        val displayList = itemsForCurrentMode()
        if (targetLetter == '★') {
            return displayList.indexOfFirst {
                it is DrawerItem.Header && it.letter.firstOrNull() == '★'
            }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        }
        return displayList.indexOfFirst { item ->
            item is DrawerItem.Header && item.letter.firstOrNull()?.uppercaseChar() == targetLetter
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
    }

    fun getPositionForLetter(letter: Char): Int {
        val exact = getExactPositionForLetter(letter)
        if (exact != RecyclerView.NO_POSITION) return exact
        if (letter == '★') return 0

        // Fallback: jump forward to next available section.
        val target = if (letter.isLetter()) letter.uppercaseChar() else '#'
        val displayList = itemsForCurrentMode()
        val headers = displayList.filterIsInstance<DrawerItem.Header>()
        if (headers.isEmpty()) return RecyclerView.NO_POSITION

        val after = headers.firstOrNull { h ->
            val c = h.letter.firstOrNull()?.uppercaseChar() ?: return@firstOrNull false
            c != '#' && c >= target
        }
        val chosen = when {
            after != null -> after
            else -> headers.last()
        }
        return displayList.indexOf(chosen).takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
    }

    /** Returns the section letter for the item at [position] in the display list. */
    fun getSectionLetterAt(position: Int): Char {
        val item = currentList.getOrNull(position) ?: return '#'
        return getSectionLetterForItem(item)
    }

    @Deprecated("Use getSectionLetterAt(position)", ReplaceWith("getSectionLetterAt(position)"))
    fun getSectionLetter(position: Int): Char = getSectionLetterAt(position)

    private fun getSectionLetter(appModel: AppModel): Char {
        if (appModel.appPackage.isEmpty()) return '#'
        val normalized = Normalizer.normalize(appModel.appLabel.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val firstCharacter = normalized.firstOrNull { !it.isWhitespace() } ?: return '#'
        val upperCase = firstCharacter.uppercaseChar()
        return if (upperCase in 'A'..'Z') upperCase else '#'
    }

    private fun getSectionLetterForItem(item: DrawerItem): Char {
        return when (item) {
            is DrawerItem.Header -> item.letter.firstOrNull() ?: '#'
            is DrawerItem.AppItem -> item.sectionLetter
        }
    }

    private fun modelStableKey(appModel: AppModel): String {
        return when (appModel) {
            is AppModel.App -> "${appModel.appPackage}|${appModel.user}"
            is AppModel.PinnedShortcut -> "${appModel.appPackage}|${appModel.shortcutId}|${appModel.user}"
        }
    }

    private fun normalizeSectionLetter(letter: Char): Char {
        return when {
            letter == '★' -> '★'
            letter.isLetter() -> letter.uppercaseChar()
            else -> '#'
        }
    }

    private fun effectiveVisibilityMode(): VisibilityMode =
        if (touchVisibilityMode != VisibilityMode.ALL) touchVisibilityMode else homeVisibilityMode

    private fun itemsForCurrentMode(): List<DrawerItem> {
        val source = if (allDisplayItems.isNotEmpty()) allDisplayItems else currentList
        return when (effectiveVisibilityMode()) {
            VisibilityMode.ALL -> source
            VisibilityMode.FAVORITES_ONLY -> source.filter { getSectionLetterForItem(it) == '★' }
            VisibilityMode.AZ_ONLY -> source.filter { getSectionLetterForItem(it) != '★' }
        }
    }

    private fun applyVisibilityMode() {
        val target = itemsForCurrentMode()
        submitList(target) {
            onListUpdated?.invoke()
        }
    }

    // ─── ViewHolders ────────────────────────────────────────────────────────

    class HeaderViewHolder(internal val binding: AdapterSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(letter: String) {
            binding.sectionTitle.text = if (letter == "★") "Favorites" else letter
        }
    }

    class AppViewHolder(internal val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            prefs: Prefs,
            clickListener: (AppModel) -> Unit,
            onLongPress: (AppModel) -> Unit,
            emptyRowLongPress: () -> Unit = {},
        ) = with(binding) {
            // Load app icon
            if (appModel.appPackage.isNotEmpty()) {
                try {
                    val launcherApps = root.context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                    val activityList = launcherApps.getActivityList(appModel.appPackage, appModel.user)
                    val icon = if (activityList.isNotEmpty()) activityList.first().getIcon(0)
                    else root.context.packageManager.getApplicationIcon(appModel.appPackage)
                    appIcon.setImageDrawable(icon)
                    appIcon.visibility = View.VISIBLE
                } catch (_: Exception) {
                    appIcon.visibility = View.GONE
                }
            } else {
                appIcon.visibility = View.GONE
            }

            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.gravity = appLabelGravity
            appTitle.typeface = Typeface.create(prefs.fontFamily, Typeface.NORMAL)
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            // Full-row tap → launch
            root.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) clickListener(appModel)
            }

            // Full-row long-press → context menu sheet (or settings for empty rows)
            root.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) onLongPress(appModel)
                else emptyRowLongPress()
                true
            }
        }
    }
}
