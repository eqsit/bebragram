package tw.nekomimi.nekogram.tor

import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/** Tor settings page (ported from inugram), opened from the Bebragram connection settings. */
class TorSettingsActivity : UniversalFragment() {
    private val modes = listOf("webtunnel", "snowflake")
    private val labels = listOf("WebTunnel", "Snowflake")
    private var ticker: Runnable? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.BebragramTorNetwork)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
        TorProxyHelper.prefetchBridges()
        scheduleTick()
    }

    override fun onPause() {
        super.onPause()
        ticker?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        ticker = null
    }

    /** Tor bootstraps in the background, so keep the status row live while the page is open. */
    private fun scheduleTick() {
        ticker?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        val runnable = Runnable {
            listView?.adapter?.update(true)
            if (TorConfig.enabled && TorProxyHelper.status != "ON") scheduleTick()
        }
        ticker = runnable
        AndroidUtilities.runOnUIThread(runnable, 1000)
    }

    private fun statusText(): String = when {
        TorProxyHelper.status == "STARTING" && TorProxyHelper.progress in 0..99 -> "Starting… ${TorProxyHelper.progress}%"
        TorProxyHelper.status == "ON" -> "Connected"
        TorProxyHelper.status == "STOPPING" && TorConfig.enabled -> "Restarting…"
        TorProxyHelper.status == "STOPPING" -> "Stopping…"
        TorProxyHelper.status == "WAITING_FOR_NETWORK" -> "Waiting for internet…"
        TorProxyHelper.status == "WAITING_FOR_VPN" -> "Waiting for VPN to turn off…"
        else -> TorProxyHelper.status
    }

    private fun bridgeSummary(): String {
        val manual = TorConfig.bridgesFor(TorConfig.mode)
        return when {
            manual.isNotBlank() && !TorConfig.autoManagedFor(TorConfig.mode) ->
                LocaleController.getString(R.string.BebragramTorCustom)
            TorConfig.mode == "direct" -> LocaleController.getString(R.string.BebragramTorNotNeeded)
            else -> LocaleController.getString(R.string.BebragramTorAutoBridges)
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asCheck(ID_ENABLE, LocaleController.getString(R.string.BebragramTorEnable))
            .setChecked(TorConfig.enabled))
        items.add(UItem.asCheck(ID_AUTOSTART, LocaleController.getString(R.string.BebragramTorAutostart))
            .setChecked(TorConfig.autostart))
        items.add(UItem.asButton(ID_MODE, LocaleController.getString(R.string.BebragramTorMode),
            labels.getOrElse(modes.indexOf(TorConfig.mode)) { "Direct" }))
        items.add(UItem.asButton(ID_BRIDGES, LocaleController.getString(R.string.BebragramTorBridgeLines), bridgeSummary()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.BebragramTorHelp)))
        items.add(UItem.asButton(ID_FETCH, LocaleController.getString(R.string.BebragramTorFetch)).accent())
        items.add(UItem.asButton(ID_AUTO, LocaleController.getString(R.string.BebragramTorAutoRefresh), autoRefreshValue()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.BebragramTorFetchInfo)))
        items.add(UItem.asButton(ID_STATUS, LocaleController.getString(R.string.BebragramTorStatus), statusText()))
        items.add(UItem.asButton(ID_LOG, LocaleController.getString(R.string.BebragramTorLog),
            LocaleController.getString(R.string.BebragramTorLogCopy)))
        TorProxyHelper.lastError?.let {
            items.add(UItem.asButton(ID_ERROR, LocaleController.getString(R.string.BebragramTorError), it))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            ID_ENABLE -> {
                if (TorConfig.enabled) TorProxyHelper.stop() else TorProxyHelper.start()
                listView.adapter.update(true)
            }
            ID_AUTOSTART -> {
                TorConfig.autostart = !TorConfig.autostart
                listView.adapter.update(true)
            }
            ID_MODE -> showModeOptions(view)
            ID_BRIDGES -> showBridgeDialog()
            ID_FETCH -> fetchBridgesNow()
            ID_AUTO -> showAutoRefreshDialog()
            ID_LOG, ID_ERROR -> showLogDialog()
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    /** Fetches a few responsive candidates from the configured GitHub bridge lists. */
    private fun fetchBridgesNow() {
        BulletinFactory.of(this)
            .createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.BebragramTorFetching)).show()
        TorProxyHelper.refreshBridges { count ->
            if (count > 0) {
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.copy,
                    LocaleController.formatString(R.string.BebragramTorFetched, count)
                ).show()
            } else {
                BulletinFactory.of(this).createErrorBulletin(
                    LocaleController.getString(R.string.BebragramTorFetchFailed)
                ).show()
            }
            listView?.adapter?.update(true)
        }
    }

    private fun autoRefreshValue(): CharSequence {
        val hours = TorConfig.bridgeAutoRefreshHours
        return if (hours <= 0) LocaleController.getString(R.string.BebragramTorAutoOff)
        else LocaleController.formatString(R.string.BebragramTorHours, hours)
    }

    private fun showAutoRefreshDialog() {
        val ctx = parentActivity ?: return
        val choices = arrayOf(
            LocaleController.getString(R.string.BebragramTorAutoOff),
            "6", "12", "24", "48", "72",
            LocaleController.getString(R.string.BebragramTorAutoCustom),
        )
        AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.BebragramTorAutoRefresh))
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> setAutoRefreshHours(0)
                    in 1..5 -> setAutoRefreshHours(intArrayOf(6, 12, 24, 48, 72)[which - 1])
                    else -> showCustomHoursDialog()
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun showCustomHoursDialog() {
        val ctx = parentActivity ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = LocaleController.getString(R.string.BebragramTorAutoCustomHint)
        }
        showDialog(AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.BebragramTorAutoRefresh))
            .setView(input)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val hours = input.text.toString().trim().toIntOrNull()
                if (hours != null && hours in 1..720) setAutoRefreshHours(hours)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create())
    }

    private fun setAutoRefreshHours(hours: Int) {
        TorConfig.bridgeAutoRefreshHours = hours
        if (hours > 0) {
            TorConfig.setAutoManagedFor(TorConfig.mode, true)
            TorConfig.setLastBridgeRefreshFor(TorConfig.mode, 0L)
            fetchBridgesNow()
        } else {
            listView?.adapter?.update(true)
        }
    }

    private fun showModeOptions(anchor: View) {
        val options = ItemOptions.makeOptions(this, anchor)
        modes.forEachIndexed { index, mode ->
            options.addChecked(index == modes.indexOf(TorConfig.mode), labels[index]) {
                if (modes[index] == TorConfig.mode) return@addChecked
                if (TorConfig.enabled) TorProxyHelper.stop()
                TorConfig.mode = mode
                TorProxyHelper.prefetchBridges()
                (anchor as? TextCell)?.setValue(labels[index], true)
                listView.adapter.update(true)
            }
        }
        options.show()
    }

    private fun showBridgeDialog() {
        val ctx = parentActivity ?: return
        val input = EditText(ctx).apply {
            hint = LocaleController.getString(R.string.BebragramTorBridgeHint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 12
            setText(TorConfig.bridgesFor(TorConfig.mode))
        }
        showDialog(AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.BebragramTorBridgeLines))
            .setMessage(LocaleController.getString(R.string.BebragramTorBridgeHintInfo))
            .setView(input)
            .setPositiveButton(LocaleController.getString(R.string.Save)) { _, _ ->
                val text = input.text.toString().trim()
                try {
                    if (text.isNotEmpty()) TorBridgeConfig.transports(TorConfig.mode, text)
                    if (TorConfig.enabled) TorProxyHelper.stop()
                    TorConfig.setBridgesFor(TorConfig.mode, text)
                    TorConfig.setAutoManagedFor(TorConfig.mode, false)
                    listView.adapter.update(true)
                } catch (e: Exception) {
                    showError(e.localizedMessage ?: "Invalid bridge")
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create())
    }

    /** Everything the Tor code did, ready to paste into a bug report. */
    private fun showLogDialog() {
        val ctx = parentActivity ?: return
        val label = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = TorLog.snapshot()
        }
        val scroll = ScrollView(ctx).apply { addView(label) }
        showDialog(AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.BebragramTorLog))
            .setView(scroll, AndroidUtilities.dp(220f))
            .setPositiveButton(LocaleController.getString(R.string.BebragramTorLogCopy)) { _, _ ->
                val clipboard = ctx.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Bebragram Tor log", TorLog.snapshot()))
                BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.BebragramTorLogCopied)).show()
            }
            .setNeutralButton(LocaleController.getString(R.string.Delete)) { _, _ -> TorLog.clear() }
            .setNegativeButton(LocaleController.getString(R.string.Close), null)
            .create())
    }

    private fun showError(message: String) {
        val ctx = parentActivity ?: return
        showDialog(AlertDialog.Builder(ctx).setTitle(LocaleController.getString(R.string.BebragramTorNetwork))
            .setMessage(message).setPositiveButton(LocaleController.getString(R.string.OK), null).create())
    }

    companion object {
        private const val ID_ENABLE = 1
        private const val ID_AUTOSTART = 2
        private const val ID_MODE = 3
        private const val ID_BRIDGES = 4
        private const val ID_STATUS = 5
        private const val ID_LOG = 6
        private const val ID_ERROR = 7
        private const val ID_FETCH = 8
        private const val ID_AUTO = 9
    }
}
