package tw.nekomimi.nekogram.mainmenu;

/**
 * Реестр пунктов главного меню («⋮» на списке чатов).
 * Значения id хранятся в настройках, поэтому менять их нельзя.
 */
public enum MainMenuItem {

    /** Разделитель между группами пунктов, а не сам пункт. */
    DIVIDER(-1),
    PROFILE(18),
    ARCHIVE(14),
    NEW_GROUP(2),
    CONTACTS(6),
    NEW_CHANNEL(3),
    CALLS(10),
    SAVED(11),
    SETTINGS(8),
    BOOKMARKS(108),
    BROWSER(101),
    GHOST_MODE(107);

    private final int id;

    MainMenuItem(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /** Линейный поиск по значениям. */
    public static MainMenuItem getById(int id) {
        for (MainMenuItem item : values()) {
            if (item.id == id) {
                return item;
            }
        }
        return null;
    }
}
