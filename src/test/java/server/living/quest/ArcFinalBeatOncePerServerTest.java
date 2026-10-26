package server.living.quest;

import client.Character;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The last beat of each wave-2 and wave-3 regional arc (docs/REGIONAL_RESIDENTS_PLAN.md sections 5 and 9), and the
 * wave-3 middle beats whose lines read wrong on a second run, set a world flag that turns the arc for everyone, so
 * each happens once per server: its {@code requires.notFlags} names that flag, and the ledger stops offering it the
 * moment anyone has done it.
 */
class ArcFinalBeatOncePerServerTest {
    private static final List<String> FINAL_BEATS = List.of("hessa-carry-it-home", "oksun-dig-the-root",
            "meiwen-the-seventh-form", "nadira-a-new-spring", "gilda-the-other-door",
            "monty-cup-night", "lyra-the-trace", "bomi-the-first-answer", "delmar-the-wreck-lantern",
            "rosnah-the-lantern-home", "chiyo-the-silent-bell", "daichi-the-hideout", "chiyo-the-bell-rings");

    @Test
    void eachFinalBeatIsBarredByTheWorldFlagItSets() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        for (String id : FINAL_BEATS) {
            Quest quest = content.quest(id);
            assertTrue(quest != null, id + " ships");
            List<String> sets = quest.rewards().worldFlags();
            assertFalse(sets.isEmpty(), id + " sets a world flag");
            assertTrue(quest.requires().notFlags().stream().anyMatch(sets::contains),
                    id + " is barred by the world flag it sets: notFlags " + quest.requires().notFlags() + ", sets " + sets);
        }
    }

    @Test
    void theLedgerStopsOfferingTheBeatOnceAnyoneHasDoneIt() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        Persona nadira = content.persona("nadira");
        LivingWorld world = mock(LivingWorld.class);
        when(world.content()).thenReturn(content);
        ArcService arcs = mock(ArcService.class);
        when(arcs.questUnlocked(any(), anyString())).thenReturn(true);
        when(world.arcs()).thenReturn(arcs);
        Set<String> worldFlags = new HashSet<>(Set.of("magatia.bench.crossed"));
        LivingStore store = mock(LivingStore.class);
        when(store.worldFlags()).thenReturn(worldFlags);
        when(store.questState(7, "nadira-the-tunnel-mouth"))
                .thenReturn(new QuestState(7, "nadira-the-tunnel-mouth", 0, 0, QuestStatus.COMPLETED, 0, 0, 0));
        Bond player = Bond.fresh(7, BondService.PLAYER_BUCKET, 0L);
        player.flags().add("ariant.heard-well"); // the rumor's listener flag
        when(store.bond(7, BondService.PLAYER_BUCKET)).thenReturn(player);
        when(world.store()).thenReturn(store);
        when(world.bonds()).thenReturn(new BondService(world));
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(7);
        when(chr.getLevel()).thenReturn(50);
        QuestLedger ledger = new QuestLedger(world);

        assertTrue(offered(ledger, chr, nadira), "offered while the well is still dry");

        worldFlags.add("ariant.well.flowing"); // another player brought the pump and the still down

        assertFalse(offered(ledger, chr, nadira), "not offered once the well flows");
    }

    private static boolean offered(QuestLedger ledger, Character chr, Persona persona) {
        return ledger.available(chr, persona).stream().anyMatch(quest -> quest.id().equals("nadira-a-new-spring"));
    }
}
