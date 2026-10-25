package client.command.commands.gm1;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.knowledge.KnowledgeTestKit;
import server.living.resident.LivingTalk;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drop browser's windows over the seed drops: the item list, an item's droppers at the player's rates, a dropper's
 * own table, and Back to the screen before; nothing opens over another dialog.
 */
class DropBrowserTest {
    private static final byte MENU = 4;
    private static final int SNAIL_SHELL = 4000019;
    private static final int BLUE_SNAIL = 100101;
    private static final String SNAIL_SHELL_LABEL = "#i4000019:# #b#z4000019##k";

    private final Character chr = mock(Character.class);
    private final Client client = mock(Client.class);
    private AutoCloseable seed;

    @BeforeEach
    void wire() {
        seed = KnowledgeTestKit.publishSeed();
        when(chr.getId()).thenReturn(11);
        when(chr.getName()).thenReturn("Bea");
        when(chr.getClient()).thenReturn(client);
        when(chr.getDropRate()).thenReturn(2);
        when(chr.getBossDropRate()).thenReturn(3);
        when(chr.getCardRate(anyInt())).thenReturn(1f);
        when(chr.getExpRate()).thenReturn(1);
        when(chr.getMesoRate()).thenReturn(1);
        when(client.getPlayer()).thenReturn(chr);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
    }

    @AfterEach
    void forget() throws Exception {
        LivingTalk.forget(chr);
        seed.close();
    }

    @Test
    void anItemLeadsToItsDroppersAndADropperToItsOwnTable() {
        DropBrowser.searchItems(chr, "snail shell");
        Talk results = lastTalk();
        assertEquals(DropBrowser.NPC, results.npcId());
        assertTrue(results.text().startsWith("#eItems matching \"snail shell\"#n"), results.text());
        String row = results.labels().stream().filter(label -> label.startsWith(SNAIL_SHELL_LABEL)).findFirst()
                .orElseThrow(() -> new AssertionError("no Snail Shell row in " + results.labels()));

        answer(results.selection(row));
        Talk item = lastTalk();
        assertTrue(item.text().startsWith("#e" + SNAIL_SHELL_LABEL + "#n"), item.text());
        assertTrue(item.text().contains("At your rates (drop x2, boss drop x3)"), item.text());
        String snail = item.labels().stream().filter(label -> label.startsWith("#bSnail (Lv ")).findFirst()
                .orElseThrow(() -> new AssertionError("no Snail row in " + item.labels()));
        assertTrue(snail.matches("#bSnail \\(Lv \\d+\\)#k 1/[\\d,]+ \\([\\d.]+%\\)"), snail);

        answer(item.selection(snail));
        Talk mob = lastTalk();
        assertTrue(mob.text().startsWith("#eSnail#n (Lv "), mob.text());
        assertTrue(mob.text().contains("Seen at: "), mob.text());
        assertTrue(mob.text().contains("\r\n#eEquipment#n"), mob.text()); // a section heading, not an option
        while (mob.labels().stream().noneMatch(label -> label.startsWith(SNAIL_SHELL_LABEL + " 1/"))) {
            assertTrue(mob.labels().contains("More..."), "no Snail Shell row on any page");
            answer(mob.selection("More..."));
            mob = lastTalk();
        }

        answer(mob.selection("Back"));
        assertEquals(item.labels(), lastTalk().labels()); // the item window again (its options newly stamped)
    }

    @Test
    void aSingleMonsterOpensItsTableWithoutAList() {
        DropBrowser.mapMobs(chr, List.of(BLUE_SNAIL));

        Talk mob = lastTalk();
        assertTrue(mob.text().startsWith("#eBlue Snail#n (Lv "), mob.text());
        assertTrue(mob.labels().stream().noneMatch(label -> label.equals("Back")), mob.labels().toString());
    }

    @Test
    void eventCopiesOfAMonsterAreMarkedInTheList() {
        DropBrowser.searchMobs(chr, "Blue Snail");

        List<String> rows = lastTalk().labels();
        assertEquals("#bBlue Snail#k (Lv 2)", rows.get(0), rows.toString());
        assertTrue(rows.contains("#bBlue Snail#k (Lv 2, event)"), rows.toString());
    }

    @Test
    void nothingFoundIsSaidInChat() {
        DropBrowser.searchItems(chr, "no such item zzzz");

        verify(chr).dropMessage(eq(5), anyString());
        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anOpenDialogIsNeverCoveredByTheBrowser() {
        LivingTalk.open(chr, DropBrowser.NPC).ok("already open").show();

        DropBrowser.searchItems(chr, "snail shell");

        verify(chr).dropMessage(5, "Close the open dialog first, then try again.");
        assertEquals("already open", lastTalk().text());
    }

    @Test
    void onlyEquipsTheClientCannotDrawLoseTheirIcon() {
        assertTrue(DropBrowser.drawable(SNAIL_SHELL));
        assertTrue(DropBrowser.drawable(1302000)); // Sword: a v83 weapon type
        assertTrue(!DropBrowser.drawable(1232014)); // a post-Big Bang weapon id: no v83 weapon type
    }

    private void answer(int selection) {
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }
}
