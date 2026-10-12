package server.living.resident;

import java.util.List;

/**
 * A {@link LivingTalk} window as a native page (docs/BOT_MANAGEMENT_P10_PLAN.md, "Step 7: native pages"): the window the
 * NPC dialog would show, as data for a client window that draws it itself. A menu page's entries are exactly the lines
 * the NPC window's menu text carries, in its order: headings, the options of the current menu page with their NPC
 * selection values, then "More..." / "Back to the top..." and "Back" as options with theirs. The text never carries a
 * #L option.
 *
 * @param stamp   the talk's stamp for this page; every reply names it
 * @param kind    the buttons the page offers
 * @param flags   {@link #HAS_PREV} and {@link #HAS_NEXT} on an OK page; 0 otherwise
 * @param text    the window text, with the NPC markup (#e #n #b #k #r #d #g, \r\n)
 * @param entries a menu page's lines (empty for any other kind)
 */
public record NativeTalkPage(int stamp, Kind kind, int flags, String text, List<Entry> entries) {
    /** An OK page has Prev: an earlier say-page, or a parent screen ({@link LivingTalk#back}). */
    public static final int HAS_PREV = 1;
    /** An OK page has Next: a later say-page, or the prompt after the pages. */
    public static final int HAS_NEXT = 1 << 1;

    public NativeTalkPage {
        entries = List.copyOf(entries);
    }

    /** The page's buttons; the code is the PAGE packet's {@code kind} byte. */
    public enum Kind {
        OK(0), YES_NO(1), ACCEPT_DECLINE(2), MENU(3);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /**
     * A line of a menu page: a heading (plain text, which cannot be chosen; selection 0) or an option, answered with
     * its NPC selection value.
     */
    public record Entry(boolean option, int selection, String label) {
        public static Entry heading(String label) {
            return new Entry(false, 0, label);
        }

        public static Entry option(int selection, String label) {
            return new Entry(true, selection, label);
        }
    }
}
