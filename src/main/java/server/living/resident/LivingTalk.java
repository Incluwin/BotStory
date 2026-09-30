package server.living.resident;

import client.Character;
import client.Client;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.npc.NPCConversationManager;
import scripting.npc.NpcPromptState;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * A tiny NPC-window conversation for Residents: the client renders any NPC id we send, so a
 * Persona borrows a townsperson's portrait (plan 5.2) and quest text gets the familiar dialog
 * instead of chat bubbles. Pages first (Next / Prev), then one final prompt: OK, Yes/No,
 * Accept/Decline (the quest-start window), a menu or a number box. A screen with a parent declares {@link #back}, so
 * every sub-page returns to it as the answer to a click. Responses arrive through {@code NPCMoreTalkHandler} ->
 * {@link #respond}.
 *
 * <p>The v83 client drops the connection when a second NPC_TALK arrives while a window waits for the
 * player's answer (docs/BOT_MANAGEMENT_P0_RESULTS.md, probe 6). So a window is only ever sent as the answer
 * to the player's click, or when nothing is on screen: {@link #show} refuses (and re-enables the client's
 * actions) while a talk or an NPC script window awaits a reply, and a talk is never re-rendered in place.
 * The stock script starters ask {@link #active} the same way. Every path ends in a window or in enableActions.</p>
 *
 * <p>A talk whose window can no longer be on screen is dropped wherever it is looked up, so it never blocks
 * the next window or eats a stock window's reply: one left from an earlier login, one shown on a map the player
 * has since left (a window sent just before a warp), and one a stock script window has replaced.</p>
 */
public final class LivingTalk {
    private static final Logger log = LoggerFactory.getLogger(LivingTalk.class);
    private static final Map<Integer, LivingTalk> OPEN = new ConcurrentHashMap<>();
    /** Menu options per window; a longer menu gets a "More..." option that shows the next ones. */
    static final int MENU_PAGE_SIZE = 10;
    /**
     * A menu selection is {@code stamp * OPTION_SLOTS + option id} (at most 9 digits, like the map ids stock scripts
     * put in #L); the last slot is "More...".
     */
    private static final int OPTION_SLOTS = 1000;
    private static final int MORE_ID = OPTION_SLOTS - 1;
    /** The "Back" option of a screen with a parent ({@link #back}). */
    private static final int BACK_ID = OPTION_SLOTS - 2;
    /** The message type of the stock number box (getNPCTalkNum) and of its reply. */
    private static final byte NUMBER_TYPE = 3;
    private static final int MAX_STAMP = 200_000;
    private static final AtomicInteger STAMPS = new AtomicInteger();
    /**
     * Speaker bit 0x04 makes the v83 client read a template id int after the speaker byte, which this packet
     * does not carry: it misreads the text and crashes (P0 probe 5). It is refused, never sent.
     */
    private static final int SPEAKER_TEMPLATE_BIT = 0x04;
    /** The character whose window answer is being handled on this thread: its next window is that answer. */
    private static final ThreadLocal<Integer> ANSWERING = new ThreadLocal<>();
    private static final Runnable HEADING = () -> { };

    /** A menu option; a null action just closes the window. */
    public record Option(String label, Runnable action) {
        /** A line of text between options (a block title); it cannot be chosen. */
        public static Option heading(String text) {
            return new Option(text, HEADING);
        }

        boolean selectable() {
            return action != HEADING;
        }
    }

    private enum Kind { OK, YES_NO, ACCEPT_DECLINE, MENU, NUMBER }

    private final Character chr;
    private final int npcId;
    private byte speaker;
    private final List<String> pages = new ArrayList<>();
    private int page;
    private Kind kind = Kind.OK;
    private String promptText;
    private Runnable onYes;
    private Runnable onNo;
    private final List<Option> options = new ArrayList<>();
    private int menuPage;
    private int numberDefault;
    private int numberMin;
    private int numberMax;
    private IntConsumer onNumber;
    /** The parent screen: re-opened by "Back" (a menu) or Prev (an OK window). */
    private Runnable back;
    /** The message type of the window on screen; an answer of any other type belongs to an older window. */
    private byte awaitingType;
    /** Stamps this window's menu selections, so a click on an older window can never pick one of its options. */
    private int stamp;
    /** The map the player stood on when the talk was shown; a warp closes the client's window. */
    private MapleMap shownOn;

    private LivingTalk(Character chr, int npcId) {
        this.chr = chr;
        this.npcId = npcId;
    }

    /** Starts building a talk for the player; nothing is sent (or replaced) until {@link #show}. */
    public static LivingTalk open(Character chr, int npcId) {
        return new LivingTalk(chr, npcId);
    }

    /** True while the player has a talk window on screen awaiting its reply; the stock script starters ask this first. */
    public static boolean active(Client client) {
        Character player = client == null ? null : client.getPlayer();
        return player != null && liveTalk(player) != null;
    }

    /**
     * True when a window could open for the player now: no talk or NPC script window awaits a reply and, unless
     * this answers the player's own click, the 500 ms NPC click throttle has passed. It sends and disposes
     * nothing, so a screen checks it before it builds any rows.
     */
    public static boolean mayShow(Character chr) {
        Client client = chr == null ? null : chr.getClient();
        if (client == null || liveTalk(chr) != null || scriptAwaitsReply(client)) {
            return false;
        }
        return Integer.valueOf(chr.getId()).equals(ANSWERING.get()) || client.canClickNPC();
    }

    /**
     * True while a talk or a stock NPC or quest script window awaits the player's reply: {@link #mayShow} without the
     * click throttle, for a window of another kind (the trade window) that must never open over a dialog.
     */
    public static boolean awaitsReply(Character chr) {
        Client client = chr == null ? null : chr.getClient();
        return client != null && (liveTalk(chr) != null || scriptAwaitsReply(client));
    }

    /**
     * A stock NPC script hands the player's click to a living-world screen (the Lakelis hook in 9020000.js): on the
     * player's own I/O thread, with no window awaiting a reply, {@code screen} runs as the answer to that click, so the
     * 500 ms throttle the click itself just stamped does not refuse its window. Afterwards exactly one window is on
     * screen, or none and the client's actions are back (the script's conversation disposed); never a second
     * NPC_TALK. False, with nothing sent, when the click cannot be answered here: the script goes on as stock.
     */
    public static boolean answerScriptClick(Character chr, Runnable screen) {
        Client client = chr == null ? null : chr.getClient();
        if (client == null || !client.onIoThread()) {
            return false;
        }
        Integer outer = ANSWERING.get();
        ANSWERING.set(chr.getId());
        try {
            if (!mayShow(chr)) {
                return false; // a window awaits its reply
            }
            try {
                screen.run();
            } catch (RuntimeException failure) {
                log.warn("living talk for {} could not answer a script click", chr.getName(), failure);
            }
            if (liveTalk(chr) == null) {
                NPCConversationManager script = client.getCM();
                if (script == null) {
                    client.sendPacket(PacketCreator.enableActions());
                } else if (!script.awaitingReply()) {
                    script.dispose(); // which re-enables the client's actions
                }
            }
            return true;
        } finally {
            if (outer == null) {
                ANSWERING.remove();
            } else {
                ANSWERING.set(outer);
            }
        }
    }

    /** The player's talk whose window is still on screen; a stale one (see the class notes) is dropped. */
    private static LivingTalk liveTalk(Character player) {
        LivingTalk talk = OPEN.get(player.getId());
        if (talk == null || talk.chr == player && player.getMap() == talk.shownOn && !scriptAwaitsReply(player.getClient())) {
            return talk;
        }
        OPEN.remove(player.getId(), talk); // an earlier login, a warp since, or a stock window replaced it
        return null;
    }

    /** True while a stock NPC or quest script window is on screen awaiting the player's answer. */
    private static boolean scriptAwaitsReply(Client client) {
        return client != null && (awaiting(client.getQM()) || awaiting(client.getCM()));
    }

    private static boolean awaiting(NPCConversationManager script) {
        return script != null && script.awaitingReply();
    }

    public static void closeFor(Character chr) {
        LivingTalk talk = OPEN.remove(chr.getId());
        if (talk != null) {
            talk.release();
        }
    }

    /** Drops the player's talk without a packet: a map change or a logout already closed the window. */
    public static void forget(Character chr) {
        OPEN.remove(chr.getId());
    }

    // ---- building ------------------------------------------------------------------------

    /**
     * The NPC_TALK speaker byte (P0 probe 5): 0 or 8 = the NPC portrait on the left, 1 = no End Chat,
     * 2 = the viewing player's own avatar on the right, 3 = the avatar with no End Chat. Anything with
     * bit 0x04 crashes the client and is rejected.
     */
    public LivingTalk speaker(int value) {
        if (value < 0 || value > 0xFF || (value & SPEAKER_TEMPLATE_BIT) != 0) {
            throw new IllegalArgumentException("NPC talk speaker " + value + " is not a byte without bit 0x04");
        }
        speaker = (byte) value;
        return this;
    }

    public LivingTalk say(String text) {
        if (text != null && !text.isBlank()) {
            pages.add(text);
        }
        return this;
    }

    public LivingTalk ok(String text) {
        kind = Kind.OK;
        promptText = text;
        return this;
    }

    public LivingTalk yesNo(String text, Runnable yes, Runnable no) {
        kind = Kind.YES_NO;
        promptText = text;
        onYes = yes;
        onNo = no;
        return this;
    }

    /** The window NPCs use to start a quest: Accept runs {@code accept}, Decline {@code decline}. */
    public LivingTalk acceptDecline(String text, Runnable accept, Runnable decline) {
        yesNo(text, accept, decline);
        kind = Kind.ACCEPT_DECLINE;
        return this;
    }

    /** Past {@link #MENU_PAGE_SIZE} options the menu pages with "More..."; {@link Option#heading} lines ride along. */
    public LivingTalk menu(String text, List<Option> choices) {
        kind = Kind.MENU;
        promptText = text;
        options.clear();
        options.addAll(choices);
        return this;
    }

    /**
     * The stock number box (getNPCTalkNum): {@code text} over a box that starts at {@code def} and takes {@code min} to
     * {@code max}. OK runs {@code onNumber} with the value, as the answer to the click. End Chat closes, and so does a
     * value outside the range (the client never sends one), as the stock handler disposes a script over it.
     */
    public LivingTalk number(String text, int def, int min, int max, IntConsumer onNumber) {
        if (min > max) {
            throw new IllegalArgumentException("number box " + min + ".." + max + " is empty");
        }
        kind = Kind.NUMBER;
        promptText = text;
        numberMin = min;
        numberMax = max;
        numberDefault = Math.max(min, Math.min(max, def));
        this.onNumber = onNumber;
        return this;
    }

    /**
     * The screen this one was opened from: a menu gets "Back" as its last option (on every page, after "More...") and
     * an OK window a Prev button, and each runs {@code parent}, which re-opens the parent as the answer to the click (a
     * number box has no button for it). Null (the default) leaves the window without one.
     */
    public LivingTalk back(Runnable parent) {
        back = parent;
        return this;
    }

    /**
     * Sends the first page or the prompt when there are no pages. Refused with enableActions while the
     * player has another window awaiting a reply, and (unless this answers the player's click) within
     * 500 ms of the last NPC click, like stock NPC clicks. A leftover script conversation that sent no
     * window is disposed first, only on the player's own I/O thread; anywhere else it is refused.
     */
    public void show() {
        Client client = chr.getClient();
        if (client == null) {
            return;
        }
        if ((pages.isEmpty() && promptText == null) || !mayOpen(client)) {
            client.sendPacket(PacketCreator.enableActions());
            return;
        }
        client.setClickedNPC();
        shownOn = chr.getMap();
        OPEN.put(chr.getId(), this);
        page = 0;
        menuPage = 0;
        try {
            render();
        } catch (RuntimeException failure) {
            log.warn("living talk for {} could not be shown", chr.getName(), failure);
            if (OPEN.remove(chr.getId(), this)) {
                client.sendPacket(PacketCreator.enableActions()); // no window went out: nothing may wait for its reply
            }
        }
    }

    private boolean mayOpen(Client client) {
        if (!mayShow(chr)) {
            return false; // a window on screen: a second NPC_TALK disconnects the client
        }
        if ((client.getQM() != null || client.getCM() != null) && !client.onIoThread()) {
            return false; // a script the player's own thread is starting may not have sent its window yet
        }
        // script conversations with no window on screen
        if (client.getQM() != null) {
            client.getQM().dispose();
        }
        if (client.getCM() != null) {
            client.getCM().dispose();
        }
        return true;
    }

    private void render() {
        Client client = chr.getClient();
        if (client == null) {
            OPEN.remove(chr.getId(), this);
            return;
        }
        stamp = STAMPS.updateAndGet(value -> value >= MAX_STAMP ? 1 : value + 1);
        if (page < pages.size()) {
            boolean hasPrev = page > 0;
            boolean hasNext = page + 1 < pages.size() || promptText != null;
            String end = hasPrev ? (hasNext ? "01 01" : "01 00") : (hasNext ? "00 01" : "00 00");
            send(client, (byte) 0, pages.get(page), end);
            return;
        }
        switch (kind) {
            case OK -> send(client, (byte) 0, promptText == null ? "" : promptText,
                    pages.isEmpty() && back == null ? "00 00" : "01 00");
            case YES_NO -> send(client, (byte) 1, promptText, "");
            case ACCEPT_DECLINE -> send(client, (byte) 0x0C, promptText, "");
            case MENU -> send(client, (byte) 4, menuText(), "");
            case NUMBER -> {
                awaitingType = NUMBER_TYPE;
                client.sendPacket(PacketCreator.getNPCTalkNum(npcId, promptText == null ? "" : promptText, numberDefault,
                        numberMin, numberMax, speaker));
            }
        }
    }

    private void send(Client client, byte msgType, String text, String end) {
        awaitingType = msgType;
        client.sendPacket(PacketCreator.getNPCTalk(npcId, msgType, text, end, speaker));
    }

    private String menuText() {
        StringBuilder text = new StringBuilder(promptText == null ? "" : promptText);
        int pageCount = menuPageCount();
        int from = menuPage * MENU_PAGE_SIZE;
        int to = pageCount > 1 ? from + MENU_PAGE_SIZE : Integer.MAX_VALUE;
        int next = 0; // position of the next selectable option; a heading shows on the page of the option after it
        boolean afterOption = false; // the last line written is a #L option
        for (int id = 0; id < options.size(); id++) {
            Option option = options.get(id);
            boolean onPage = next >= from && next < to;
            if (!option.selectable()) {
                if (onPage) {
                    // the v83 client draws a plain line right after a #L option half a line up, over that option
                    // (found live 2026-09-29/30): a blank line first keeps it on a line of its own
                    if (afterOption && !option.label().startsWith("\r\n")) {
                        text.append("\r\n");
                    }
                    text.append("\r\n").append(option.label());
                    afterOption = false;
                }
                continue;
            }
            if (onPage) {
                text.append("\r\n#L").append(selectionOf(id)).append("#").append(option.label()).append("#l");
                afterOption = true;
            }
            next++;
        }
        if (pageCount > 1) { // on the last page it wraps to the first
            text.append("\r\n#L").append(selectionOf(MORE_ID)).append("#")
                    .append(menuPage + 1 < pageCount ? "More..." : "Back to the top...").append("#l");
        }
        if (back != null) {
            text.append("\r\n#L").append(selectionOf(BACK_ID)).append("#Back#l");
        }
        return text.toString();
    }

    private int menuPageCount() {
        long selectable = options.stream().filter(Option::selectable).count();
        return (int) Math.max(1, (selectable + MENU_PAGE_SIZE - 1) / MENU_PAGE_SIZE);
    }

    private int selectionOf(int optionId) {
        return stamp * OPTION_SLOTS + optionId;
    }

    // ---- responses -----------------------------------------------------------------------

    /**
     * Called by the packet handler after {@code lastMsg} and {@code action} were read; reads the
     * rest of the packet itself. Returns true when the response belonged to an open talk.
     */
    public static boolean respond(Client client, byte lastMsg, byte action, InPacket packet) {
        Character player = client == null ? null : client.getPlayer();
        LivingTalk talk = player == null ? null : liveTalk(player);
        if (talk == null) {
            return false; // no talk, or a stale one: the answer belongs to a stock window
        }
        int selection = -1;
        if (lastMsg == 2) {
            if (action != 0 && packet.available() > 0) {
                packet.readString();
            }
        } else if (packet.available() >= 4) {
            selection = packet.readInt();
        } else if (packet.available() > 0) {
            selection = packet.readUnsignedByte();
        }
        Integer outer = ANSWERING.get();
        ANSWERING.set(player.getId());
        try {
            talk.handle(lastMsg, action, selection);
        } catch (RuntimeException failure) {
            log.warn("living talk response failed for {}", player.getName(), failure);
            talk.close();
        } finally {
            if (outer == null) {
                ANSWERING.remove();
            } else {
                ANSWERING.set(outer);
            }
        }
        return true;
    }

    private void handle(byte lastMsg, byte action, int selection) {
        if (lastMsg != awaitingType) {
            return; // the answer to an older window (a double click); this one is still on screen
        }
        if (page < pages.size()) {
            if (action == 1) {
                page++;
                if (page < pages.size() || promptText != null) {
                    render();
                } else {
                    close();
                }
            } else if (action == 0 && page > 0) {
                page--;
                render();
            } else {
                close();
            }
            return;
        }
        switch (kind) {
            case OK -> {
                if (action == 0 && back != null) {
                    finish(back); // Prev: the parent screen, as the answer to the click
                } else {
                    close();
                }
            }
            case YES_NO, ACCEPT_DECLINE -> finish(action == 1 ? onYes : onNo);
            case MENU -> choose(action, selection);
            case NUMBER -> answerNumber(action, selection);
        }
    }

    /**
     * A number box's reply, checked as the stock handler checks a script's (NpcPromptState.number): OK with a value in
     * range runs the callback, anything else closes. The box has only OK and End Chat, so a reply that is not OK is End
     * Chat (or ESC) and ends the chat; the screen before it offers Back. A reply is taken however soon it comes (Enter on
     * the default at once): the client has closed its box by then, and a reply meant for an older window is another
     * type or stamp, already ignored in {@link #handle}.
     */
    private void answerNumber(byte action, int selection) {
        if (action != 1 || !NpcPromptState.number(numberMin, numberMax).accepts(NUMBER_TYPE, action, selection)) {
            close(); // End Chat, or a value the box never offered
            return;
        }
        IntConsumer callback = onNumber;
        finish(callback == null ? null : () -> callback.accept(selection));
    }

    private void choose(byte action, int selection) {
        if (action != 1 || selection < 0) {
            close(); // End Chat
            return;
        }
        int clicked = selection / OPTION_SLOTS;
        int id = selection % OPTION_SLOTS;
        if (clicked != stamp) {
            if (clicked > 0 && clicked < stamp) {
                return; // a click on an older window: never its option, never twice; this window stays
            }
            close();
            return;
        }
        if (id == MORE_ID) {
            menuPage = (menuPage + 1) % menuPageCount();
            render(); // the answer to the click: the next page of the same menu
            return;
        }
        if (id == BACK_ID && back != null) {
            finish(back); // the answer to the click: the parent screen
            return;
        }
        Option chosen = id < options.size() && options.get(id).selectable() ? options.get(id) : null;
        finish(chosen == null ? null : chosen.action());
    }

    /** Closes this window and runs the answer, which may open the next window; otherwise the client's actions come back. */
    private void finish(Runnable next) {
        if (!OPEN.remove(chr.getId(), this)) {
            return;
        }
        try {
            if (next != null) {
                next.run();
            }
        } finally {
            release();
        }
    }

    /** Ends the talk and re-enables the client's actions unless another talk was opened meanwhile. */
    public void close() {
        if (OPEN.remove(chr.getId(), this)) {
            release();
        }
    }

    private void release() {
        Client client = chr.getClient();
        if (client != null && !OPEN.containsKey(chr.getId())) {
            client.sendPacket(PacketCreator.enableActions());
        }
    }
}
