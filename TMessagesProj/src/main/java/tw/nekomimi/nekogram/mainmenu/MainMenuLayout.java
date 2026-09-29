package tw.nekomimi.nekogram.mainmenu;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.MainTabsHelper;
import xyz.nextalone.nagram.NaConfig;

/**
 * Порядок и видимость пунктов главного меню.
 *
 * В настройках лежит один строковый ключ {@code main_menu_layout}: {@code "видимые;скрытые"},
 * id через запятую. Пустая строка — «настройку не трогали», тогда берётся дефолт из
 * {@link #getDefaultLayout()}.
 */
public final class MainMenuLayout {

    private static final String PREFS = "bebragram";
    private static final String KEY = "main_menu_layout";
    private static final String SECTION_SEPARATOR = ";";
    private static final String ID_SEPARATOR = ",";

    private MainMenuLayout() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Дефолтная раскладка повторяет стоковое меню при текущих настройках: «Профиль»,
     * «Контакты», «Архив», «Закладки» и «Настройки» попадают в меню только тогда, когда
     * их нет в нижней панели.
     */
    public static List<Integer> getDefaultLayout() {
        final boolean bottomBarHidden = isBottomNavigationBarHidden();
        final ArrayList<Integer> layout = new ArrayList<>();
        if (bottomBarHidden) {
            layout.add(MainMenuItem.PROFILE.getId());
        }
        layout.add(MainMenuItem.NEW_GROUP.getId());
        layout.add(MainMenuItem.NEW_CHANNEL.getId());
        if (bottomBarHidden || MainTabsHelper.isContactsTabHidden()) {
            layout.add(MainMenuItem.CONTACTS.getId());
        }
        if (bottomBarHidden && NaConfig.INSTANCE.getHideArchive().Bool()) {
            layout.add(MainMenuItem.ARCHIVE.getId());
        }
        layout.add(MainMenuItem.SAVED.getId());
        if (bottomBarHidden && NaConfig.INSTANCE.getShowAddToBookmark().Bool()) {
            layout.add(MainMenuItem.BOOKMARKS.getId());
        }
        if (NekoConfig.showGhostInDrawer.Bool()) {
            layout.add(MainMenuItem.GHOST_MODE.getId());
        }
        if (bottomBarHidden || UserConfig.getInstance(UserConfig.selectedAccount).showCallsTab) {
            layout.add(MainMenuItem.SETTINGS.getId());
        }
        return layout;
    }

    /** Видимые пункты в порядке показа. Разделители ({@link MainMenuItem#DIVIDER}) тоже здесь. */
    public static List<Integer> getLayout() {
        return parse()[0];
    }

    /** Спрятанные пункты — их показывает только экран-редактор. */
    public static List<Integer> getHiddenItems() {
        return parse()[1];
    }

    /**
     * Записывает раскладку. Перед записью прогоняется
     * {@link #ensureSettingsVisibility(List, List)}: «Настройки» нельзя спрятать, когда
     * нижняя панель выключена — иначе до них не добраться.
     */
    public static void save(List<Integer> layout, List<Integer> hidden) {
        final ArrayList<Integer> visibleCopy = new ArrayList<>(layout);
        final ArrayList<Integer> hiddenCopy = new ArrayList<>(hidden);
        ensureSettingsVisibility(visibleCopy, hiddenCopy);
        prefs().edit().putString(KEY, serialize(visibleCopy, hiddenCopy)).apply();
    }

    /** Сбрасывает раскладку в дефолт (пустая строка = «не настраивали»). */
    public static void reset() {
        prefs().edit().putString(KEY, "").apply();
    }

    /**
     * Настраивал ли пользователь раскладку. Пока нет — потребители обязаны показывать
     * ровно то, что показывали до появления настройки.
     */
    public static boolean isCustomized() {
        final String raw = prefs().getString(KEY, "");
        return raw != null && !raw.isEmpty();
    }

    /** Все известные пункты, кроме разделителя, — для экрана-редактора. */
    public static List<Integer> getAllItemIds() {
        final ArrayList<Integer> ids = new ArrayList<>();
        for (MainMenuItem item : MainMenuItem.values()) {
            if (item != MainMenuItem.DIVIDER) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    public static ArrayList<Integer> getLayoutMutable() {
        return new ArrayList<>(getLayout());
    }

    public static ArrayList<Integer> getHiddenItemsMutable() {
        return new ArrayList<>(getHiddenItems());
    }

    // ---- внутреннее ----

    private static boolean isBottomNavigationBarHidden() {
        try {
            return NaConfig.INSTANCE.getHideBottomNavigationBar().Bool();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Возвращает {@code [видимые, скрытые]}. Неизвестные id выкидываются, новые
     * (появившиеся с обновлением) дописываются в скрытые.
     */
    private static List<Integer>[] parse() {
        final String raw = prefs().getString(KEY, "");

        final ArrayList<Integer> visible = new ArrayList<>();
        final ArrayList<Integer> hidden = new ArrayList<>();

        if (raw == null || raw.isEmpty()) {
            visible.addAll(getDefaultLayout());
        } else {
            final String[] sections = raw.split(SECTION_SEPARATOR, -1);
            readIds(sections.length > 0 ? sections[0] : "", visible, true);
            readIds(sections.length > 1 ? sections[1] : "", hidden, false);
        }

        for (Integer id : getAllItemIds()) {
            if (!visible.contains(id) && !hidden.contains(id)) {
                hidden.add(id);
            }
        }
        ensureSettingsVisibility(visible, hidden);

        @SuppressWarnings("unchecked") final List<Integer>[] result = new List[]{
                Collections.unmodifiableList(visible),
                Collections.unmodifiableList(hidden)
        };
        return result;
    }

    private static void readIds(String section, ArrayList<Integer> out, boolean allowDivider) {
        if (section == null || section.isEmpty()) {
            return;
        }
        for (String part : section.split(ID_SEPARATOR)) {
            final String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int id;
            try {
                id = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                continue;
            }
            if (id == MainMenuItem.DIVIDER.getId()) {
                if (allowDivider) {
                    out.add(id);
                }
                continue;
            }
            if (MainMenuItem.getById(id) != null && !out.contains(id)) {
                out.add(id);
            }
        }
    }

    private static void ensureSettingsVisibility(List<Integer> visible, List<Integer> hidden) {
        if (!isBottomNavigationBarHidden()) {
            return;
        }
        final Integer settings = MainMenuItem.SETTINGS.getId();
        if (visible.contains(settings)) {
            return;
        }
        hidden.remove(settings);
        visible.add(settings);
    }

    private static String serialize(List<Integer> visible, List<Integer> hidden) {
        return join(visible) + SECTION_SEPARATOR + join(hidden);
    }

    private static String join(List<Integer> ids) {
        final StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) {
                sb.append(ID_SEPARATOR);
            }
            sb.append(id);
        }
        return sb.toString();
    }
}
