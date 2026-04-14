package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsFragment : BottomSheetDialogFragment(), View.OnClickListener {

    companion object {
        fun show(fm: FragmentManager, onNavigateToAppList: ((Int) -> Unit)? = null) {
            if (fm.findFragmentByTag("settings") != null) return
            val sheet = SettingsFragment()
            sheet.onNavigateToAppList = onNavigateToAppList
            sheet.show(fm, "settings")
        }
    }

    var onNavigateToAppList: ((Int) -> Unit)? = null

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Always anchor to the bottom with fixed height and fixed expanded offset.
        (dialog as? BottomSheetDialog)?.let { d ->
            d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val sheet = d.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.background = null
            sheet?.let { container ->
                val fixedHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
                val topOffset = (resources.displayMetrics.heightPixels - fixedHeight).coerceAtLeast(0)
                container.layoutParams = container.layoutParams?.also { it.height = fixedHeight }
                    ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fixedHeight)
                container.requestLayout()
                BottomSheetBehavior.from(container).apply {
                    peekHeight = fixedHeight
                    isFitToContents = false
                    expandedOffset = topOffset
                    skipCollapsed = true
                    isHideable = false
                    isDraggable = false
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }

        prefs = Prefs(requireContext())
        viewModel = activity?.run { ViewModelProvider(this)[MainViewModel::class.java] }
            ?: throw Exception("Invalid Activity")

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)

        bindClicks()
        populateUi()
        observe()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.olauncherHiddenApps -> showHiddenApps()
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)

            R.id.autoShowKeyboard -> {
                prefs.autoShowKeyboard = !prefs.autoShowKeyboard
                populateKeyboardText()
            }
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.statusBar -> toggleStatusBar()
            R.id.appThemeText -> cycleThemeMode()
            R.id.clockStyle -> cycleClockStyle()
            R.id.waveStyle -> cycleWaveStyle()

            R.id.textSizeValue -> Unit
            R.id.textSizeMinus -> adjustTextSizePreview(-0.1f)
            R.id.textSizePlus -> adjustTextSizePreview(0.1f)

            R.id.swipeLeftApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_LEFT_APP)
            R.id.swipeRightApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_RIGHT_APP)
            R.id.swipeDownAction -> {
                val next = if (prefs.swipeDownAction == Constants.SwipeDownAction.NOTIFICATIONS)
                    Constants.SwipeDownAction.SEARCH
                else Constants.SwipeDownAction.NOTIFICATIONS
                updateSwipeDownAction(next)
            }

            R.id.toggleLock -> toggleLockMode()
            R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.showIcons -> {
                prefs.showHomeIcons = !prefs.showHomeIcons
                populateShowIcons()
                viewModel.refreshHome(false)
            }
            R.id.batteryLevel -> {
                prefs.showBattery = !prefs.showBattery
                populateBattery()
                viewModel.refreshHome(false)
            }
            R.id.fontFamily -> {
                prefs.fontFamily = when (prefs.fontFamily) {
                    "sans-serif-light" -> "sans-serif"
                    "sans-serif" -> "monospace"
                    else -> "sans-serif-light"
                }
                populateFontFamily()
                viewModel.refreshHome(false)
            }
        }
    }

    private fun bindClicks() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.clockStyle.setOnClickListener(this)
        binding.waveStyle.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.textSizeMinus.setOnClickListener(this)
        binding.textSizePlus.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        binding.swipeDownAction.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        binding.homeButtonRecents.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.showIcons.setOnClickListener(this)
        binding.batteryLevel.setOnClickListener(this)
        binding.fontFamily.setOnClickListener(this)
    }

    private fun observe() {
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) binding.setLauncher.text = getString(R.string.change_default_launcher)
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    private fun populateUi() {
        viewModel.isOlauncherDefault()
        populateKeyboardText()
        populateWallpaperText()
        populateStatusBar()
        populateThemeText()
        populateClockStyle()
        populateWaveStyle()
        populateTextSize()
        populateSwipeApps()
        populateSwipeDownAction()
        populateLockSettings()
        populateHomeButtonRecents()
        populateScreenTimeOnOff()
        populateShowIcons()
        populateBattery()
        populateFontFamily()
    }

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        dismiss()
        onNavigateToAppList?.invoke(Constants.FLAG_HIDDEN_APPS)
    }

    private fun populateKeyboardText() {
        binding.autoShowKeyboard.text = getString(if (prefs.autoShowKeyboard) R.string.on else R.string.off)
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isOlauncherDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            if (isOlauncherDefault(requireContext())) {
                requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
            } else {
                requireContext().showToast(getString(R.string.olauncher_is_not_default_launcher), Toast.LENGTH_LONG)
            }
        } else {
            viewModel.cancelWallpaperWorker()
        }
    }

    private fun populateWallpaperText() {
        binding.dailyWallpaper.text = getString(if (prefs.dailyWallpaper) R.string.on else R.string.off)
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        binding.statusBar.text = getString(if (prefs.showStatusBar) R.string.on else R.string.off)
        if (prefs.showStatusBar) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
            else
                @Suppress("DEPRECATION", "InlinedApi")
                requireActivity().window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
            else
                @Suppress("DEPRECATION")
                requireActivity().window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun cycleThemeMode() {
        val next = when (prefs.appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_NO
            AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        prefs.appTheme = next
        if (prefs.dailyWallpaper) {
            when (next) {
                AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
                AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
                else -> {
                    if (requireContext().isDarkThemeOn())
                        setPlainWallpaper(requireContext(), android.R.color.black)
                    else setPlainWallpaper(requireContext(), android.R.color.white)
                }
            }
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun populateThemeText() {
        binding.appThemeText.text = when (prefs.appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.light)
            else -> getString(R.string.system_default)
        }
    }

    private fun cycleClockStyle() {
        prefs.clockStyle = when (prefs.clockStyle) {
            Constants.ClockStyle.MINIMAL -> Constants.ClockStyle.SPLIT
            Constants.ClockStyle.SPLIT -> Constants.ClockStyle.MONO
            else -> Constants.ClockStyle.MINIMAL
        }
        populateClockStyle()
        viewModel.refreshHome(false)
    }

    private fun populateClockStyle() {
        binding.clockStyle.text = when (prefs.clockStyle) {
            Constants.ClockStyle.SPLIT -> getString(R.string.style_split)
            Constants.ClockStyle.MONO -> getString(R.string.style_mono)
            else -> getString(R.string.style_minimal)
        }
    }

    private fun cycleWaveStyle() {
        prefs.waveStyle = when (prefs.waveStyle) {
            Constants.WaveStyle.SUBTLE -> Constants.WaveStyle.BALANCED
            Constants.WaveStyle.BALANCED -> Constants.WaveStyle.DRAMATIC
            else -> Constants.WaveStyle.SUBTLE
        }
        populateWaveStyle()
        viewModel.refreshHome(false)
    }

    private fun populateWaveStyle() {
        binding.waveStyle.text = when (prefs.waveStyle) {
            Constants.WaveStyle.SUBTLE -> getString(R.string.wave_subtle)
            Constants.WaveStyle.DRAMATIC -> getString(R.string.wave_dramatic)
            else -> getString(R.string.wave_balanced)
        }
    }

    private var pendingTextSizeScale: Float = -1f

    private fun adjustTextSizePreview(delta: Float) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = String.format("%.1f", clamped)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun populateTextSize() {
        val formatted = String.format("%.1f", prefs.textSizeScale)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
        binding.textSizesLayout.visibility = View.VISIBLE
    }

    private fun updateSwipeDownAction(value: Int) {
        if (prefs.swipeDownAction == value) return
        prefs.swipeDownAction = value
        populateSwipeDownAction()
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            else -> getString(R.string.search)
        }
    }

    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) && !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) && !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(includeHiddenApps = true)
        dismiss()
        onNavigateToAppList?.invoke(flag)
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = prefs.appNameSwipeLeft
        binding.swipeRightApp.text = prefs.appNameSwipeRight
        binding.swipeLeftApp.setTextColor(
            requireContext().getColorFromAttr(
                if (prefs.swipeLeftEnabled) R.attr.primaryColor else R.attr.primaryColorTrans50
            )
        )
        binding.swipeRightApp.setTextColor(
            requireContext().getColorFromAttr(
                if (prefs.swipeRightEnabled) R.attr.primaryColor else R.attr.primaryColorTrans50
            )
        )
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                deviceManager.removeActiveAdmin(componentName)
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_permission_message))
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun populateLockSettings() {
        val isOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            prefs.lockModeOn && isAccessServiceEnabled(requireContext())
        else prefs.lockModeOn
        binding.toggleLock.text = getString(if (isOn) R.string.on else R.string.off)
    }

    private fun toggleHomeButtonRecents() {
        if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
        populateHomeButtonRecents()
    }

    private fun populateHomeButtonRecents() {
        binding.homeButtonRecents.text = getString(
            if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on else R.string.off
        )
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.screenTimeOnOff.text = getString(
                if (requireContext().appUsagePermissionGranted()) R.string.on else R.string.off
            )
        } else {
            binding.screenTimeOnOff.text = getString(R.string.off)
        }
    }

    private fun populateShowIcons() {
        binding.showIcons.text = getString(if (prefs.showHomeIcons) R.string.on else R.string.off)
    }

    private fun populateBattery() {
        binding.batteryLevel.text = getString(if (prefs.showBattery) R.string.on else R.string.off)
    }

    private fun populateFontFamily() {
        binding.fontFamily.text = getString(
            when (prefs.fontFamily) {
                "sans-serif" -> R.string.font_regular
                "monospace" -> R.string.font_mono
                else -> R.string.font_light
            }
        )
    }

    override fun onDestroyView() {
        if (pendingTextSizeScale > 0 && prefs.textSizeScale != pendingTextSizeScale) {
            prefs.textSizeScale = pendingTextSizeScale
            pendingTextSizeScale = -1f
            requireActivity().recreate()
        }
        super.onDestroyView()
        _binding = null
    }
}
