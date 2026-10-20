package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionType;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan C3: Jack's hall lets nobody in without a Crimsonwood Keystone, and a bench ally loads straight onto it, so
 * the picker does not offer a benched companion known to hold none (read as its owner came into the hall), and the
 * call reads its bag once more: one with none stays at camp and whispers so; one with a keystone sets off as before.
 * With the keystone errands off, allies come as they do to every other boss.
 */
class CompanionBossAlliesKeystoneTest {
    private static final int HALL = 610_030_020;
    private static final int HALL_OF_MASTERY = 610_030_010;
    private static final int MAGNA = 911;
    private static final int KEYSTONE = CompanionKeystoneGate.KEYSTONE_ITEM_ID;

    private final BossAllyHarness h = new BossAllyHarness();
    private Persona magna;

    @BeforeEach
    void annIsOnJacksListWithMagnaBenchedNearby() {
        h.game.config.COMPANION_BOSS_ENABLED_TYPES = "cwkpq";
        h.game.config.COMPANION_BOSS_ALLY_TYPES = "cwkpq";
        h.game.config.COMPANION_KEYSTONE_ERRANDS_ENABLED = true;
        MapleMap hall = h.game.map(HALL);
        h.game.moveAnn(HALL, 976, 94);
        when(h.game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.CWKPQ)).thenReturn(h.expedition);
        when(h.expedition.getType()).thenReturn(ExpeditionType.CWKPQ);
        when(h.expedition.getMaxSize()).thenReturn(ExpeditionType.CWKPQ.getMaxSize());
        when(h.expedition.getRecruitingMap()).thenReturn(hall);
        net.server.channel.Channel channel = h.room.getChannelServer();
        when(hall.getChannelServer()).thenReturn(channel);
        when(h.game.world.bossRuns()).thenReturn(h.bossRuns);
        magna = h.benched("magna", "Magna", MAGNA, HALL_OF_MASTERY);
        h.game.store.levels.put(MAGNA, 120);
        Character bot = h.game.bots.get(MAGNA);
        when(bot.getLevel()).thenReturn(120);
        h.game.roster();
    }

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void aBenchedCompanionKnownToHoldNoKeystoneIsNotOffered() {
        assertNull(row().why(), "never read: offered, and the call reads its bag");

        when(h.game.store.store.holdsItem(MAGNA, KEYSTONE)).thenReturn(false);
        h.bossRuns.ownerAtKeystoneGate(LifecycleHarness.ANN, HALL);

        assertEquals(CompanionBossAllies.NotOffered.NO_KEYSTONE, row().why());
    }

    @Test
    void aCalledAllyWithNoKeystoneStaysAtCampAndWhispersSo() {
        when(h.game.store.store.holdsItem(MAGNA, KEYSTONE)).thenReturn(false);

        h.allies.send(h.game.ann, "cwkpq", List.of(magna.id()), h.now);

        List<String> said = h.said("Magna");
        assertEquals(1, said.size(), h.whispers.toString());
        assertTrue(BossRunLines.BY_BOSS.get("cwkpq").get(BossRunLines.ALLY_NO_KEYSTONE).contains(said.get(0)));
        assertTrue(h.bench.isEmpty(), "its bench slice goes on: " + h.bench);
    }

    @Test
    void aCalledAllyWithAKeystoneSetsOffAsBefore() {
        when(h.game.store.store.holdsItem(MAGNA, KEYSTONE)).thenReturn(true);

        h.allies.send(h.game.ann, "cwkpq", List.of(magna.id()), h.now);

        assertEquals(1, h.bench.size(), h.bench.toString());
        assertTrue(h.whispers.stream().noneMatch(whisper -> whisper.contains("keystone")), h.whispers.toString());
    }

    @Test
    void withTheKeystoneErrandsOffAlliesComeAsToEveryOtherBoss() {
        h.game.config.COMPANION_KEYSTONE_ERRANDS_ENABLED = false;
        when(h.game.store.store.holdsItem(MAGNA, KEYSTONE)).thenReturn(false);
        h.bossRuns.ownerAtKeystoneGate(LifecycleHarness.ANN, HALL);

        assertNull(row().why());
        h.allies.send(h.game.ann, "cwkpq", List.of(magna.id()), h.now);
        assertEquals(1, h.bench.size(), h.bench.toString());
    }

    private CompanionBossAllies.Row row() {
        CompanionBossAllies.Picker picker = h.allies.picker(h.game.ann, "cwkpq", h.now);
        return picker.row(magna.id());
    }
}
