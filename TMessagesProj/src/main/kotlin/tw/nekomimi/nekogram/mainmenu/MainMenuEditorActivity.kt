package tw.nekomimi.nekogram.mainmenu

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView
import xyz.nextalone.nagram.NaConfig

/**
 * Редактор главного меню: видимые пункты (тап — скрыть, удержание — порядок),
 * скрытые пункты (тап — вернуть), разделители и сброс раскладки.
 */
class MainMenuEditorActivity : BaseFragment() {

    private var listView: UniversalRecyclerView? = null
    private var resetItem: ActionBarMenuItem? = null

    /** Разделителей в списке может быть несколько, а id у них общий (-1), поэтому адаптеру
     * нужны различимые: раздаём стабильные id от -2000 вниз. */
    private val stableDividerIds = ArrayList<Int>()
    private var nextDividerId = DIVIDER_ID_BASE

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(LocaleController.getString(R.string.BebragramMainMenu))
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                }
            }
        })

        val contentView = FrameLayout(context)
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))

        val lv = UniversalRecyclerView(
            this,
            { items, adapter -> fillItems(items, adapter) },
            { item, view, position, x, y -> onItemClick(item, view, position, x, y) },
            null
        )
        lv.setSections()
        lv.adapter.setApplyBackground(false)
        lv.allowReorder(true)
        lv.listenReorder { section, reordered -> onReordered(section, reordered) }
        contentView.addView(lv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        listView = lv
        actionBar.setAdaptiveBackground(lv)

        resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset)
        resetItem?.setContentDescription(LocaleController.getString(R.string.Reset))
        resetItem?.setOnClickListener { resetMenuLayout() }
        updateResetButtonVisibility(false)

        fragmentView = contentView
        return fragmentView
    }

    private fun resetMenuLayout() {
        MainMenuLayout.reset()
        stableDividerIds.clear()
        nextDividerId = DIVIDER_ID_BASE
        update()
    }

    private fun updateResetButtonVisibility(animated: Boolean) {
        val item = resetItem ?: return
        val show = MainMenuLayout.getLayout() != MainMenuLayout.getDefaultLayout()
        if (show && item.visibility != View.VISIBLE) {
            AndroidUtilities.updateViewVisibilityAnimated(item, true, 0.5f, animated)
        } else if (!show && item.visibility == View.VISIBLE) {
            AndroidUtilities.updateViewVisibilityAnimated(item, false, 0.5f, animated)
        }
    }

    // ---- содержимое ----

    private fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        adapter.whiteSectionStart()
        items.add(UItem.asHeader(LocaleController.getString(R.string.BebragramMainMenuItems)))
        adapter.reorderSectionStart()

        var dividerIndex = 0
        for (id in MainMenuLayout.getLayout()) {
            if (id == MainMenuItem.DIVIDER.getId()) {
                while (stableDividerIds.size <= dividerIndex) {
                    stableDividerIds.add(nextDividerId--)
                }
                items.add(createMenuItem(stableDividerIds[dividerIndex], null))
                dividerIndex++
                continue
            }
            MainMenuHelper.describeItem(id)?.let { info ->
                items.add(createMenuItem(id, info))
            }
        }

        adapter.reorderSectionEnd()
        items.add(
            UItem.asButton(ID_ADD_DIVIDER, R.drawable.msg_add, LocaleController.getString(R.string.BebragramMainMenuAddDivider)).accent()
        )
        adapter.whiteSectionEnd()
        items.add(UItem.asShadow(LocaleController.getString(R.string.BebragramMainMenuItemsInfo)))

        val hidden = MainMenuLayout.getHiddenItems()
        if (hidden.isNotEmpty()) {
            adapter.whiteSectionStart()
            items.add(UItem.asHeader(LocaleController.getString(R.string.BebragramMainMenuHiddenItems)))
            for (id in hidden) {
                if (id == MainMenuItem.DIVIDER.getId()) {
                    items.add(createMenuItem(MainMenuItem.DIVIDER.getId(), null))
                } else {
                    MainMenuHelper.describeItem(id)?.let { info ->
                        items.add(createMenuItem(id, info))
                    }
                }
            }
            adapter.whiteSectionEnd()
            items.add(UItem.asShadow(null))
        }
    }

    private fun createMenuItem(id: Int, info: MainMenuHelper.MenuItemInfo?): UItem {
        if (info == null) {
            return UItem.asButton(id, R.drawable.msg_block, LocaleController.getString(R.string.BebragramMainMenuDivider))
        }
        return UItem.asButton(id, info.iconRes(), info.text())
    }

    // ---- обработка ----

    private fun onItemClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val id = item.id

        if (id == ID_ADD_DIVIDER) {
            stableDividerIds.add(nextDividerId--)
            val layout = MainMenuLayout.getLayoutMutable()
            layout.add(MainMenuItem.DIVIDER.getId())
            MainMenuLayout.save(layout, MainMenuLayout.getHiddenItems())
            update()
            return
        }

        // Разделитель из видимой секции: у него стабильный отрицательный id, надо найти,
        // какой по счёту разделитель в раскладке ему соответствует.
        if (id <= DIVIDER_ID_BASE) {
            val index = stableDividerIds.indexOf(id)
            if (index < 0) return
            val layout = MainMenuLayout.getLayoutMutable()
            var seen = 0
            for (i in layout.indices) {
                if (layout[i] == MainMenuItem.DIVIDER.getId()) {
                    if (seen == index) {
                        stableDividerIds.removeAt(index)
                        layout.removeAt(i)
                        MainMenuLayout.save(layout, MainMenuLayout.getHiddenItems())
                        update()
                        return
                    }
                    seen++
                }
            }
            return
        }

        toggleMenuItem(id)
    }

    /** Перенос пункта между «видимыми» и «скрытыми». */
    private fun toggleMenuItem(id: Int) {
        val layout = MainMenuLayout.getLayoutMutable()
        val hidden = MainMenuLayout.getHiddenItemsMutable()

        // «Настройки» нельзя убрать, если их неоткуда больше открыть.
        if (id == MainMenuItem.SETTINGS.getId() && layout.contains(id) && !hasBottomTabs()) {
            BulletinFactory.of(this)
                .createErrorBulletin(LocaleController.getString(R.string.BebragramMainMenuRemoveSettingsInfo))
                .show()
            return
        }

        if (layout.contains(id)) {
            layout.remove(id)
            if (!hidden.contains(id)) {
                hidden.add(0, id)
            }
        } else if (hidden.contains(id)) {
            hidden.remove(id)
            layout.add(id)
        }
        MainMenuLayout.save(layout, hidden)
        update()
    }

    /**
     * Перетаскивание. Адаптер отдаёт номер секции и её строки в новом порядке.
     * Секция 0 — видимые пункты, 1 — скрытые.
     */
    private fun onReordered(section: Int, reordered: ArrayList<UItem>) {
        val ids = ArrayList<Int>(reordered.size)
        for (item in reordered) {
            ids.add(if (item.id <= DIVIDER_ID_BASE) MainMenuItem.DIVIDER.getId() else item.id)
        }
        if (section == 0) {
            stableDividerIds.clear()
            for (item in reordered) {
                if (item.id <= DIVIDER_ID_BASE) {
                    stableDividerIds.add(item.id)
                }
            }
            MainMenuLayout.save(ids, MainMenuLayout.getHiddenItems())
        } else {
            MainMenuLayout.save(MainMenuLayout.getLayout(), ids)
        }
        updateResetButtonVisibility(true)
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged)
    }

    private fun update() {
        listView?.adapter?.update(true)
        updateResetButtonVisibility(true)
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged)
    }

    private fun hasBottomTabs(): Boolean {
        return !NaConfig.hideBottomNavigationBar.Bool()
    }

    companion object {
        private const val ID_ADD_DIVIDER = -200
        private const val DIVIDER_ID_BASE = -2000
    }
}
