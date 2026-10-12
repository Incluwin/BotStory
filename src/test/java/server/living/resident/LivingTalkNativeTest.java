package server.living.resident;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.resident.LivingTalk.Option;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LivingTalk's native pages (docs/BOT_MANAGEMENT_P10_PLAN.md, "Step 7: native pages"): a native menu carries exactly the
 * lines the NPC window's menu text carries (headings, options with their selection values, "More..." paging, "Back"),
 * a reply goes through the same answer code only with the page's own stamp, ending a page closes it (never
 * enableActions) unless the answer opened another, a number box or a client without pages gets the NPC window, and
 * native pages and NPC windows never block each other.
 */
class LivingTalkNativeTest {
    private static final byte MENU = 4;
    private static final byte CLOSE = (byte) 255;
    private static final int NPC = 9040011;

    private final Character chr = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Recorder renderer = new Recorder();

    /** The native Crew window, as LivingTalk sees it: every page and close it was asked to send. */
    private static final class Recorder implements NativeTalkRenderer {
        boolean draws = true;
        final List<NativeTalkPage> pages = new ArrayList<>();
        final List<Integer> closes = new ArrayList<>();

        @Override
        public boolean draws(Character chr) {
            return draws;
        }

        @Override
        public void page(Character chr, NativeTalkPage page) {
            pages.add(page);
        }

        @Override
        public void close(Character chr, int stamp) {
            closes.add(stamp);
        }

        NativeTalkPage last() {
            assertFalse(pages.isEmpty(), "no native page was drawn");
            return pages.get(pages.size() - 1);
        }
    }

    @BeforeEach
    void wire() {
        when(chr.getId()).thenReturn(10);
        when(chr.getName()).thenReturn("Ann");
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        LivingTalk.nativeRenderer(renderer);
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(chr);
        LivingTalk.endNative(chr);
        LivingTalk.clearNativeRenderer(renderer);
    }

    // ---- parity with the NPC window --------------------------------------------------------------

    @Test
    void aNativeMenuIsTheNpcMenusLinesPageByPageIncludingMoreAndBack() {
        String prompt = "#eWho?#n\r\n";
        List<Option> options = longMenuWithHeadings();
        LivingTalk.open(chr, NPC).menu(prompt, options).back(() -> { }).show();
        LivingTalk.openNative(chr, NPC).menu(prompt, options).back(() -> { }).show();

        for (int page = 0; page < 3; page++) { // two pages, then "Back to the top..." wraps to the first
            Talk npc = lastTalk();
            NativeTalkPage shown = renderer.last();
            assertEquals(NativeTalkPage.Kind.MENU, shown.kind());
            assertEquals(prompt, shown.text(), "the text never carries an option");
            assertEquals(npc.text(), npcText(shown, npcStamp(npc)), "page " + page);
            List<String> optionLabels = shown.entries().stream().filter(NativeTalkPage.Entry::option)
                    .map(NativeTalkPage.Entry::label).toList();
            assertEquals(npc.labels(), optionLabels);
            for (NativeTalkPage.Entry entry : shown.entries()) {
                if (entry.option()) {
                    assertEquals(shown.stamp(), entry.selection() / 1000, "stamped with its page");
                    assertEquals(npc.selection(entry.label()) % 1000, entry.selection() % 1000, entry.label());
                } else {
                    assertEquals(0, entry.selection());
                }
            }
            String more = page % 2 == 0 ? "More..." : "Back to the top...";
            int npcMore = npc.selection(more);
            int nativeMore = optionSelection(shown, more);
            assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(npcMore))));
            assertTrue(LivingTalk.respondNative(chr, shown.stamp(), (byte) 1, nativeMore));
        }
        assertEquals(List.of("row 0", "row 1", "row 2", "row 3", "row 4", "row 5", "row 6", "row 7", "row 8", "row 9",
                "More...", "Back"), renderer.pages.get(0).entries().stream().filter(NativeTalkPage.Entry::option)
                .map(NativeTalkPage.Entry::label).toList());
        assertEquals(List.of("(late heading)", "row 10", "row 11", "row 12", "Back to the top...", "Back"),
                renderer.pages.get(1).entries().stream().map(NativeTalkPage.Entry::label).toList(),
                "a heading shows on the page of the option after it");
        assertTrue(renderer.closes.isEmpty(), "paging is the answer to the click: the page stays");
    }

    @Test
    void anOptionsAnswerRunsAsTheNpcWindowsDoes() {
        AtomicInteger picked = new AtomicInteger(-1);
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            int chosen = index;
            options.add(new Option("row " + index, () -> picked.set(chosen)));
        }
        LivingTalk.openNative(chr, NPC).menu("pick", options).show();
        respond(renderer.last(), "More...");

        respond(renderer.last(), "row 11");

        assertEquals(11, picked.get());
        assertEquals(List.of(renderer.last().stamp()), renderer.closes, "the page closes after its answer");
        assertFalse(LivingTalk.nativeActive(chr));
    }

    // ---- replies -----------------------------------------------------------------------------------

    @Test
    void aReplyWithAnotherStampDoesNothingAndThePageStays() {
        AtomicInteger runs = new AtomicInteger();
        LivingTalk.openNative(chr, NPC).menu("pick", List.of(new Option("go", runs::incrementAndGet))).show();
        NativeTalkPage shown = renderer.last();
        int go = optionSelection(shown, "go");

        assertFalse(LivingTalk.respondNative(chr, shown.stamp() - 1, (byte) 1, go));
        assertFalse(LivingTalk.respondNative(chr, shown.stamp() + 1, (byte) 1, go));
        assertFalse(LivingTalk.respondNative(chr, 0, CLOSE, -1));

        assertEquals(0, runs.get());
        assertTrue(renderer.closes.isEmpty());
        assertTrue(LivingTalk.nativeActive(chr));
        assertTrue(LivingTalk.respondNative(chr, shown.stamp(), (byte) 1, go));
        assertEquals(1, runs.get());
    }

    @Test
    void aClickOnAnOlderPageOfTheSameMenuNeverPicksItsOption() {
        AtomicInteger picked = new AtomicInteger(-1);
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            int chosen = index;
            options.add(new Option("row " + index, () -> picked.set(chosen)));
        }
        LivingTalk.openNative(chr, NPC).menu("pick", options).show();
        NativeTalkPage first = renderer.last();
        respond(first, "More...");

        assertFalse(LivingTalk.respondNative(chr, first.stamp(), (byte) 1, optionSelection(first, "row 2")));

        assertEquals(-1, picked.get());
        assertTrue(LivingTalk.nativeActive(chr));
    }

    @Test
    void closeEndsThePageWithoutRunningAnythingEvenOnAYesNo() {
        AtomicInteger yes = new AtomicInteger();
        AtomicInteger no = new AtomicInteger();
        LivingTalk.openNative(chr, NPC).yesNo("sure?", yes::incrementAndGet, no::incrementAndGet).show();
        NativeTalkPage ask = renderer.last();
        assertEquals(NativeTalkPage.Kind.YES_NO, ask.kind());
        assertEquals("sure?", ask.text());
        assertTrue(ask.entries().isEmpty());

        assertTrue(LivingTalk.respondNative(chr, ask.stamp(), CLOSE, -1));

        assertEquals(0, yes.get() + no.get());
        assertEquals(List.of(ask.stamp()), renderer.closes);

        LivingTalk.openNative(chr, NPC).yesNo("sure?", yes::incrementAndGet, no::incrementAndGet).show();
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 0, -1));
        assertEquals(1, no.get());
        LivingTalk.openNative(chr, NPC).acceptDecline("hire?", yes::incrementAndGet, no::incrementAndGet).show();
        assertEquals(NativeTalkPage.Kind.ACCEPT_DECLINE, renderer.last().kind());
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 1, -1));
        assertEquals(1, yes.get());
    }

    @Test
    void sayPagesCarryPrevAndNextAndAParentGivesTheOkPrev() {
        LivingTalk.openNative(chr, NPC).say("one").say("two").ok("done").show();
        NativeTalkPage one = renderer.last();
        assertEquals(NativeTalkPage.Kind.OK, one.kind());
        assertEquals("one", one.text());
        assertEquals(NativeTalkPage.HAS_NEXT, one.flags());

        assertTrue(LivingTalk.respondNative(chr, one.stamp(), (byte) 1, -1));
        NativeTalkPage two = renderer.last();
        assertEquals(NativeTalkPage.HAS_PREV | NativeTalkPage.HAS_NEXT, two.flags());
        assertTrue(LivingTalk.respondNative(chr, two.stamp(), (byte) 0, -1));
        assertEquals("one", renderer.last().text(), "Prev");
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 1, -1));
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 1, -1));
        NativeTalkPage done = renderer.last();
        assertEquals("done", done.text());
        assertEquals(NativeTalkPage.HAS_PREV, done.flags(), "as the NPC window's 01 00 after pages");
        assertTrue(LivingTalk.respondNative(chr, done.stamp(), (byte) 1, -1));
        assertEquals(List.of(done.stamp()), renderer.closes);

        AtomicInteger parent = new AtomicInteger();
        LivingTalk.openNative(chr, NPC).ok("alone").show();
        assertEquals(0, renderer.last().flags());
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 1, -1));
        LivingTalk.openNative(chr, NPC).ok("with a parent").back(parent::incrementAndGet).show();
        assertEquals(NativeTalkPage.HAS_PREV, renderer.last().flags());
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 0, -1));
        assertEquals(1, parent.get());
    }

    // ---- ending a page -----------------------------------------------------------------------------

    @Test
    void anAnswerClosesThePageUnlessItOpenedAnotherAndNeverSendsEnableActions() {
        List<Option> options = List.of(
                new Option("nothing", () -> { }),
                new Option("next page", () -> LivingTalk.openNative(chr, NPC).ok("the next page").show()),
                new Option("closes", null));
        LivingTalk.openNative(chr, NPC).menu("pick", options).show();
        NativeTalkPage first = renderer.last();

        respond(first, "next page");
        assertTrue(renderer.closes.isEmpty(), "the next page replaced it");
        assertEquals("the next page", renderer.last().text());
        assertTrue(LivingTalk.respondNative(chr, renderer.last().stamp(), (byte) 1, -1));
        assertEquals(List.of(renderer.last().stamp()), renderer.closes);

        LivingTalk.openNative(chr, NPC).menu("pick", options).show();
        respond(renderer.last(), "nothing");
        LivingTalk.openNative(chr, NPC).menu("pick", options).show();
        respond(renderer.last(), "closes");

        assertEquals(3, renderer.closes.size());
        verify(client, never()).sendPacket(any()); // no NPC window was open: nothing to re-enable
    }

    @Test
    void anAnswerThatOpensAnNpcWindowClosesThePageAndTheWindowIsTheAnswerToTheClick() {
        when(client.canClickNPC()).thenReturn(false); // inside the click throttle: only an answer to a click may open
        LivingTalk.openNative(chr, NPC).menu("pick", List.of(new Option("terms",
                () -> LivingTalk.open(chr, NPC).ok("the hire terms").show()))).show();

        respond(renderer.last(), "terms");

        assertEquals("the hire terms", lastTalk().text());
        assertEquals(List.of(renderer.last().stamp()), renderer.closes);
        assertTrue(LivingTalk.active(client));
    }

    // ---- falling back to the NPC window ------------------------------------------------------------

    @Test
    void aNumberBoxIsTheNpcWindow() {
        LivingTalk.openNative(chr, NPC).number("how many?", 1, 1, 10, value -> { }).show();

        assertTrue(renderer.pages.isEmpty());
        assertEquals(3, lastTalk().msgType()); // the stock number box
        assertTrue(LivingTalk.active(client));
        assertFalse(LivingTalk.nativeActive(chr));
    }

    @Test
    void withoutARendererOrForAClientThatDrawsNoPagesItIsTheNpcWindow() {
        renderer.draws = false; // a protocol-1 client
        LivingTalk.openNative(chr, NPC).speaker(2).menu("pick", List.of(new Option("go", null))).show();

        assertTrue(renderer.pages.isEmpty());
        Talk npc = lastTalk();
        assertEquals(MENU, npc.msgType());
        assertEquals(2, npc.speaker());
        assertEquals(List.of("go"), npc.labels());
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1,
                Packets.buildInPacket(out -> out.writeInt(npc.selection("go")))));
        assertFalse(LivingTalk.active(client));

        renderer.draws = true;
        LivingTalk.clearNativeRenderer(renderer);
        clearInvocations(client);
        LivingTalk.openNative(chr, NPC).ok("no window draws pages").show();
        assertTrue(renderer.pages.isEmpty());
        assertEquals("no window draws pages", lastTalk().text());
    }

    // ---- native pages and NPC windows --------------------------------------------------------------

    @Test
    void aNativePageAndAnNpcWindowNeverBlockEachOther() {
        LivingTalk.open(chr, NPC).ok("an NPC window").show();
        assertTrue(LivingTalk.active(client));

        LivingTalk.openNative(chr, NPC).ok("a native page").show();
        assertEquals("a native page", renderer.last().text());
        assertTrue(LivingTalk.active(client), "the NPC window still waits for its own reply");

        assertTrue(LivingTalk.respond(client, (byte) 0, (byte) 1, Packets.buildInPacket(out -> { })));
        assertFalse(LivingTalk.active(client));
        assertTrue(LivingTalk.nativeActive(chr), "the NPC reply left the page alone");
        assertTrue(LivingTalk.mayShow(chr), "a native page never blocks an NPC window");
    }

    @Test
    void aWarpKeepsThePageAndClosingTheWindowEndsIt() {
        MapleMap elsewhere = mock(MapleMap.class);
        LivingTalk.openNative(chr, NPC).ok("a native page").show();
        NativeTalkPage shown = renderer.last();

        when(chr.getMap()).thenReturn(elsewhere);
        LivingTalk.forget(chr); // a warp
        assertTrue(LivingTalk.nativeActive(chr), "the native window survives warps");

        LivingTalk.endNative(chr);
        assertFalse(LivingTalk.nativeActive(chr));
        assertFalse(LivingTalk.respondNative(chr, shown.stamp(), (byte) 1, -1));
        assertTrue(renderer.closes.isEmpty(), "the window is gone: nothing to send");
    }

    @Test
    void aPageLeftFromAnEarlierLoginIsNeverAnswered() {
        LivingTalk.openNative(chr, NPC).ok("from the old login").show();
        NativeTalkPage shown = renderer.last();
        Character again = mock(Character.class);
        when(again.getId()).thenReturn(10);
        when(again.getClient()).thenReturn(client);

        LivingTalk.endNative(again); // only that login's own page
        assertTrue(LivingTalk.nativeActive(chr));
        assertFalse(LivingTalk.respondNative(again, shown.stamp(), (byte) 1, -1));
        assertFalse(LivingTalk.nativeActive(chr), "dropped once the new login looked it up");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** 13 options with a heading first, one right after an option, one carried to the second page, one starting \r\n. */
    private static List<Option> longMenuWithHeadings() {
        List<Option> options = new ArrayList<>();
        options.add(Option.heading("#eFirst#n"));
        for (int index = 0; index < 13; index++) {
            if (index == 3) {
                options.add(Option.heading("a heading after an option"));
            }
            if (index == 5) {
                options.add(Option.heading("\r\nalready on its own line"));
            }
            if (index == 10) {
                options.add(Option.heading("(late heading)"));
            }
            options.add(new Option("row " + index, () -> { }));
        }
        return options;
    }

    /**
     * The NPC menu text the page's entries stand for, its selections re-stamped with the NPC window's stamp: the
     * prompt, each option as \r\n#L{selection}#{label}#l, each heading as \r\n{label} (after a blank line when it
     * follows an option and does not start a line itself). The DLL draws the same lines.
     */
    private static String npcText(NativeTalkPage page, int npcStamp) {
        StringBuilder text = new StringBuilder(page.text());
        boolean afterOption = false;
        for (NativeTalkPage.Entry entry : page.entries()) {
            if (entry.option()) {
                int selection = npcStamp * 1000 + entry.selection() % 1000;
                text.append("\r\n#L").append(selection).append("#").append(entry.label()).append("#l");
                afterOption = true;
            } else {
                if (afterOption && !entry.label().startsWith("\r\n")) {
                    text.append("\r\n");
                }
                text.append("\r\n").append(entry.label());
                afterOption = false;
            }
        }
        return text.toString();
    }

    private static int npcStamp(Talk npc) {
        return npc.selection(npc.labels().get(0)) / 1000;
    }

    private static int optionSelection(NativeTalkPage page, String label) {
        return page.entries().stream().filter(entry -> entry.option() && entry.label().equals(label))
                .mapToInt(NativeTalkPage.Entry::selection).findFirst()
                .orElseThrow(() -> new AssertionError("no option '" + label + "' in " + page.entries()));
    }

    private void respond(NativeTalkPage page, String label) {
        assertTrue(LivingTalk.respondNative(chr, page.stamp(), (byte) 1, optionSelection(page, label)));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }
}
