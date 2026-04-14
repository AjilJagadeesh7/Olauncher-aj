package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.LayoutContextMenuSheetBinding
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard

/**
 * Bottom-sheet context menu for a single app row.
 *
 * Instantiate with [ContextMenuSheet.newInstance] and call [show].
 */
class ContextMenuSheet private constructor() : BottomSheetDialogFragment() {

    private var _binding: LayoutContextMenuSheetBinding? = null
    private val binding get() = _binding!!

    // Injected before show() is called
    internal var appModel: AppModel? = null
    internal var flag: Int = Constants.FLAG_LAUNCH_APP
    internal var appClickListener: (AppModel) -> Unit = {}
    internal var appDeleteListener: (AppModel) -> Unit = {}
    internal var appInfoListener: (AppModel) -> Unit = {}
    internal var appHideListener: (AppModel) -> Unit = {}
    internal var appRenameListener: (AppModel, String) -> Unit = { _, _ -> }
    internal var appShortcutsListener: (AppModel) -> Unit = {}
    internal var appFavoriteListener: (AppModel, Boolean) -> Unit = { _, _ -> }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = LayoutContextMenuSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Fixed at 50% screen height. Sheet itself does not drag — only internal content scrolls.
        (dialog as? BottomSheetDialog)?.let { d ->
            d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val sheet = d.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.background = null
            sheet?.let { container ->
                val fixedHeight = (resources.displayMetrics.heightPixels * 0.50).toInt()
                val topOffset = (resources.displayMetrics.heightPixels - fixedHeight).coerceAtLeast(0)
                container.layoutParams = container.layoutParams?.also { it.height = fixedHeight }
                    ?: android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, fixedHeight)
                container.requestLayout()
                BottomSheetBehavior.from(container).apply {
                    peekHeight = fixedHeight
                    // Fixed bottom anchor; prevents top-drift when content inside changes.
                    isFitToContents = false
                    expandedOffset = topOffset
                    skipCollapsed = true
                    isHideable = false
                    isDraggable = false
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }

        val model = appModel ?: run { dismiss(); return }
        val prefs = Prefs(requireContext())

        // Header
        binding.sheetAppName.text = model.appLabel

        // Favorite row — hide for pinned shortcuts and non-launch-app contexts
        val isPinnedShortcut = model is AppModel.PinnedShortcut
        binding.appFavorite.isVisible = !isPinnedShortcut && flag == Constants.FLAG_LAUNCH_APP
        if (!isPinnedShortcut) {
            val isFav = prefs.isFavorite(model.appPackage, model.user.toString())
            binding.appFavorite.text = if (isFav)
                getString(R.string.unfavorite)
            else
                getString(R.string.favorite)
        }

        // Hide row label and alpha
        binding.appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
            getString(R.string.adapter_show)
        else
            getString(R.string.adapter_hide)
        binding.appHide.alpha = if (isPinnedShortcut) 0.5f else 1.0f

        // Rename — only for non-hidden contexts
        binding.appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS

        // Delete alpha — system apps are dimmed but not hidden
        val canDelete = isPinnedShortcut || !requireContext().isSystemApp(model.appPackage, model.user)
        binding.appDelete.alpha = if (canDelete) 1.0f else 0.5f

        // ── Click listeners ──────────────────────────────────────────────

        binding.appFavorite.setOnClickListener {
            if (model is AppModel.App) {
                val isFav = prefs.isFavorite(model.appPackage, model.user.toString())
                appFavoriteListener(model, !isFav)
            }
            dismiss()
        }

        binding.appRename.setOnClickListener {
            // Switch to rename sub-state
            binding.actionsGroup.visibility = View.GONE
            binding.renameGroup.visibility = View.VISIBLE
            val hint = getOriginalAppName(requireContext(), model)
            binding.etAppRename.hint = hint
            binding.etAppRename.setText(model.appLabel)
            binding.etAppRename.setSelectAllOnFocus(true)
            binding.etAppRename.showKeyboard()
            binding.etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
        }

        binding.appHide.setOnClickListener {
            if (!isPinnedShortcut) appHideListener(model)
            dismiss()
        }

        binding.appShortcuts.setOnClickListener {
            appShortcutsListener(model)
            dismiss()
        }

        binding.appInfo.setOnClickListener {
            appInfoListener(model)
            dismiss()
        }

        binding.appDelete.setOnClickListener {
            if (canDelete) appDeleteListener(model)
            dismiss()
        }

        // ── Rename sub-state ─────────────────────────────────────────────

        binding.tvSaveRename.setOnClickListener {
            binding.etAppRename.hideKeyboard()
            val label = binding.etAppRename.text.toString().trim()
            if (label.isNotBlank() && model.appPackage.isNotBlank()) {
                appRenameListener(model, label)
            } else if (model.appPackage.isNotBlank()) {
                appRenameListener(model, getOriginalAppName(requireContext(), model))
            }
            dismiss()
        }

        binding.appRenameClose.setOnClickListener {
            binding.etAppRename.hideKeyboard()
            binding.renameGroup.visibility = View.GONE
            binding.actionsGroup.visibility = View.VISIBLE
        }

        binding.etAppRename.setOnEditorActionListener { _, actionCode, _ ->
            if (actionCode == EditorInfo.IME_ACTION_DONE) {
                val label = binding.etAppRename.text.toString().trim()
                if (label.isNotBlank() && model.appPackage.isNotBlank()) {
                    appRenameListener(model, label)
                }
                dismiss()
                true
            } else false
        }

        binding.etAppRename.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.etAppRename.hint = if (s.isNullOrEmpty())
                    getOriginalAppName(requireContext(), model)
                else ""
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun getOriginalAppName(context: Context, model: AppModel): String {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        return try {
            val activityList = launcherApps.getActivityList(model.appPackage, model.user)
            if (activityList.isNotEmpty()) activityList.first().label.toString()
            else context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(model.appPackage, 0)
            ).toString()
        } catch (_: Exception) { model.appLabel }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        fun newInstance(
            appModel: AppModel,
            flag: Int,
            appClickListener: (AppModel) -> Unit = {},
            appDeleteListener: (AppModel) -> Unit = {},
            appInfoListener: (AppModel) -> Unit = {},
            appHideListener: (AppModel) -> Unit = {},
            appRenameListener: (AppModel, String) -> Unit = { _, _ -> },
            appShortcutsListener: (AppModel) -> Unit = {},
            appFavoriteListener: (AppModel, Boolean) -> Unit = { _, _ -> },
        ): ContextMenuSheet {
            return ContextMenuSheet().apply {
                this.appModel = appModel
                this.flag = flag
                this.appClickListener = appClickListener
                this.appDeleteListener = appDeleteListener
                this.appInfoListener = appInfoListener
                this.appHideListener = appHideListener
                this.appRenameListener = appRenameListener
                this.appShortcutsListener = appShortcutsListener
                this.appFavoriteListener = appFavoriteListener
            }
        }
    }
}
