package tw.nekomimi.nekogram.mainmenu;

import android.os.Bundle;
import android.text.TextUtils;
import android.app.Activity;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionIntroActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.web.SearchEngine;

import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import tw.nekomimi.nekogram.ui.BookmarkManagerActivity;

/**
 * Резолвер пунктов главного меню: id из {@link MainMenuLayout} → иконка, подпись и действие.
 */
public final class MainMenuHelper {

    private MainMenuHelper() {
    }

    public record MenuContext(int currentAccount, BaseFragment fragment) {
    }

    public record MenuItemInfo(int iconRes, CharSequence text, Runnable onClick, Runnable onLongClick) {
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment) {
        return new MenuContext(currentAccount, fragment);
    }

    /**
     * Разделитель ставится «отложенно»: висящие в начале и в конце схлопываются.
     */
    public static void addConfiguredItemOptions(ItemOptions io, MenuContext ctx) {
        boolean hasAnyItem = false;
        boolean dividerPending = false;
        final List<Integer> layout = MainMenuLayout.getLayout();
        for (int i = 0; i < layout.size(); i++) {
            final Integer id = layout.get(i);
            if (id == null) {
                continue;
            }
            if (id == MainMenuItem.DIVIDER.getId()) {
                if (hasAnyItem) {
                    dividerPending = true;
                }
                continue;
            }
            if (dividerPending) {
                io.addGap();
                dividerPending = false;
            }
            if (addConfiguredItemOption(io, ctx, id)) {
                hasAnyItem = true;
            }
        }
    }

    private static boolean addConfiguredItemOption(ItemOptions io, MenuContext ctx, int id) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return false;
        }
        if (item == MainMenuItem.ARCHIVE && !hasArchivedChats(ctx.currentAccount())) {
            return false;
        }
        final MenuItemInfo info = resolveMenuItem(id, ctx);
        if (info == null || info.onClick() == null) {
            return false;
        }
        io.add(info.iconRes(), info.text(), info.onClick());
        bindLongClick(io, info.onLongClick());
        return true;
    }

    /** Длинное нажатие закрывает меню. */
    private static void bindLongClick(ItemOptions io, Runnable onLongClick) {
        if (onLongClick == null) {
            return;
        }
        final ActionBarMenuSubItem last = io.getLast();
        if (last == null) {
            return;
        }
        last.setOnLongClickListener(v -> {
            io.dismiss();
            onLongClick.run();
            return true;
        });
    }

    public static MenuItemInfo resolveMenuItem(int id, MenuContext ctx) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null || ctx.fragment() == null) {
            return null;
        }
        final int currentAccount = ctx.currentAccount();
        final BaseFragment fragment = ctx.fragment();
        switch (item) {
            case PROFILE:
                return new MenuItemInfo(R.drawable.left_status_profile, LocaleController.getString(R.string.MyProfile), () -> {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                    args.putBoolean("my_profile", true);
                    fragment.presentFragment(new ProfileActivity(args));
                }, null);
            case ARCHIVE:
                return new MenuItemInfo(R.drawable.msg_archive, LocaleController.getString(R.string.ArchivedChats), () -> {
                    final Bundle args = new Bundle();
                    args.putInt("folderId", 1);
                    fragment.presentFragment(new DialogsActivity(args));
                }, null);
            case NEW_GROUP:
                return new MenuItemInfo(R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup),
                        () -> fragment.presentFragment(new GroupCreateActivity(new Bundle())), null);
            case CONTACTS:
                return new MenuItemInfo(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), () -> {
                    final Bundle args = new Bundle();
                    args.putBoolean("needPhonebook", true);
                    args.putBoolean("needFinishFragment", false);
                    fragment.presentFragment(new ContactsActivity(args));
                }, null);
            case CALLS:
                return new MenuItemInfo(R.drawable.msg_calls, LocaleController.getString(R.string.Calls),
                        () -> fragment.presentFragment(new CallLogActivity()), null);
            case NEW_CHANNEL:
                return new MenuItemInfo(R.drawable.msg_channel, LocaleController.getString(R.string.NewChannel),
                        () -> presentChannelCreate(fragment), null);
            case SAVED:
                return new MenuItemInfo(R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), () -> {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                    fragment.presentFragment(new ChatActivity(args));
                }, null);
            case SETTINGS:
                return new MenuItemInfo(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new SettingsActivity()), null);
            case BOOKMARKS:
                return new MenuItemInfo(R.drawable.msg_fave, LocaleController.getString(R.string.BookmarksManager),
                        () -> fragment.presentFragment(new BookmarkManagerActivity()), null);
            case BROWSER:
                return new MenuItemInfo(R.drawable.msg2_language, LocaleController.getString(R.string.BrowserSettingsTitle),
                        () -> openBrowserHomepage(fragment), null);
            case GHOST_MODE:
                return new MenuItemInfo(R.drawable.ayu_ghost, ghostModeTitle(),
                        () -> toggleGhostMode(fragment, currentAccount),
                        () -> fragment.presentFragment(new GhostModeActivity()));
            default:
                return null;
        }
    }

    /** Заголовок зависит от состояния: пункт и показывает его, и переключает. */
    private static CharSequence ghostModeTitle() {
        return LocaleController.getString(NekoConfig.isGhostModeActive()
                ? R.string.DisableGhostMode
                : R.string.EnableGhostMode);
    }

    private static void toggleGhostMode(BaseFragment fragment, int currentAccount) {
        final boolean wasActive = NekoConfig.isGhostModeActive();
        NekoConfig.toggleGhostMode();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        BulletinFactory.of(fragment)
                .createSuccessBulletin(LocaleController.getString(
                        wasActive ? R.string.GhostModeDisabled : R.string.GhostModeEnabled))
                .show();
    }

    private static void presentChannelCreate(BaseFragment fragment) {
        final android.content.SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (BuildVars.DEBUG_VERSION || !prefs.getBoolean("channel_intro", false)) {
            fragment.presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
            prefs.edit().putBoolean("channel_intro", true).apply();
        } else {
            final Bundle args = new Bundle();
            args.putInt("step", 0);
            fragment.presentFragment(new ChannelCreateActivity(args));
        }
    }

    private static void openBrowserHomepage(BaseFragment fragment) {
        final SearchEngine engine = SearchEngine.getCurrent();
        final Activity activity = fragment.getParentActivity();
        if (engine == null || activity == null || TextUtils.isEmpty(engine.search_url)) {
            return;
        }
        Browser.openInTelegramBrowser(activity, engine.search_url, null);
    }

    /**
     * Иконка и подпись пункта без действия — для экрана-редактора раскладки,
     * где фрагмента-получателя ещё нет. {@link MainMenuItem#DIVIDER} возвращает {@code null}.
     */
    public static MenuItemInfo describeItem(int id) {
        final MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return null;
        }
        return switch (item) {
            case PROFILE -> new MenuItemInfo(R.drawable.left_status_profile, LocaleController.getString(R.string.MyProfile), null, null);
            case ARCHIVE -> new MenuItemInfo(R.drawable.msg_archive, LocaleController.getString(R.string.ArchivedChats), null, null);
            case NEW_GROUP -> new MenuItemInfo(R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup), null, null);
            case CONTACTS -> new MenuItemInfo(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), null, null);
            case NEW_CHANNEL -> new MenuItemInfo(R.drawable.msg_channel, LocaleController.getString(R.string.NewChannel), null, null);
            case CALLS -> new MenuItemInfo(R.drawable.msg_calls, LocaleController.getString(R.string.Calls), null, null);
            case SAVED -> new MenuItemInfo(R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), null, null);
            case SETTINGS -> new MenuItemInfo(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), null, null);
            case BOOKMARKS -> new MenuItemInfo(R.drawable.msg_fave, LocaleController.getString(R.string.BookmarksManager), null, null);
            case BROWSER -> new MenuItemInfo(R.drawable.msg2_language, LocaleController.getString(R.string.BrowserSettingsTitle), null, null);
            case GHOST_MODE -> new MenuItemInfo(R.drawable.ayu_ghost, ghostModeTitle(), null, null);
            default -> null;
        };
    }

    public static boolean hasArchivedChats(int currentAccount) {
        try {
            final List<TLRPC.Dialog> archived = MessagesController.getInstance(currentAccount).getDialogs(1);
            return archived != null && !archived.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
