package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

/**
 * P6g LPQ review fixes (stage 9): Alishar's key at the leader's feet that he leaves lying is picked back up before it
 * vanishes and dropped at his feet again with K3, and the re-collect counts against PASS_RECOLLECT_MAX; a companion
 * that picks the key back up for a carrier that left becomes its carrier and brings it to him; a carrier that leaves
 * before it loots the key is replaced by the next nearest companion.
 */
class AgentLpqBossKeyRecollectTest {
    private static final int S9 = AgentLpqDefinition.stage(9).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character kes;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Kes carries Alishar's key to Crewfriend's feet at 5 s (K1); he leaves it lying. */
    private AgentLpqSession keyAtHisFeet() {
        fx.npcAt(AgentLpqDefinition.stage(9).npcId(), new Point(222, 70));
        crew = fx.crew(S9, new Point(300, 184));
        kes = crew.get(2);
        fx.moveTo(crew.getFirst(), new Point(400, 184));
        fx.moveTo(kes, new Point(950, 184));
        fx.drop(S9, AgentLpqDefinition.BOSS_KEY, 1, new Point(980, 184), false, 0, 1_000L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        session.narrateOnce("s9-start");
        fx.tick(session, 3_000L);
        fx.tick(session, 4_000L);
        fx.moveTo(kes, new Point(410, 184));
        fx.tick(session, 5_000L);
        fx.said(session, "s9-key", kes,
                "Crewfriend, alishar's key is at your feet. grab it and talk to the violet balloon.");
        return session;
    }

    private List<MapItem> lyingKeys() {
        return fx.map(S9).getDroppedItems().stream()
                .filter(drop -> drop.getItemId() == AgentLpqDefinition.BOSS_KEY).toList();
    }

    @Test
    void theCarrierPicksTheKeyBackUpBeforeItVanishesAndSaysSo() {
        AgentLpqSession session = keyAtHisFeet();

        fx.tickEvery(session, 6_000L, 160_000L, 1_000L);

        fx.items.verify(() -> AgentScriptItemActionService.dropItem(eq(fx.entry(kes)), eq(InventoryType.ETC),
                eq(AgentLpqDefinition.BOSS_KEY), anyShort()), times(2));
        assertEquals(1, session.recollects(), "the key's re-collect counts against PASS_RECOLLECT_MAX");
        fx.said(session, "s9-key-recollect", kes,
                "Crewfriend, picked the key back up so it doesn't vanish. it's at your feet again.");
        assertEquals(1, lyingKeys().size(), "the key lies at his feet again");
        assertEquals(0, kes.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false));
    }

    @Test
    void aCompanionThatPicksUpTheKeyOfACarrierThatLeftBringsItToHim() {
        AgentLpqSession session = keyAtHisFeet();
        Character cas = crew.get(5);
        fx.moveTo(cas, new Point(405, 184));
        fx.leaveParty(kes);
        fx.outside(kes, 922_010_000);
        fx.tick(session, 6_000L);
        assertEquals(0, session.keyCarrierId(), "the carrier left the run");

        fx.tickEvery(session, 7_000L, 220_000L, 1_000L);

        assertEquals(cas.getId(), session.keyCarrierId(), "the companion that picked it back up carries it now");
        assertEquals(0, cas.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false), "it does not keep the key");
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(eq(fx.entry(cas)), eq(InventoryType.ETC),
                eq(AgentLpqDefinition.BOSS_KEY), anyShort()));
        fx.said(session, "s9-key-recollect", cas,
                "Crewfriend, picked the key back up so it doesn't vanish. it's at your feet again.");
        assertEquals(1, lyingKeys().size());
        assertTrue(lyingKeys().getFirst().getPosition().distance(new Point(400, 184)) < 20, "at his feet");
        assertEquals(AgentLpqSession.Phase.STAGE_9, session.phase());
    }

    @Test
    void aCarrierThatLeavesBeforeItLootsTheKeyIsReplacedByTheNextNearest() {
        fx.npcAt(AgentLpqDefinition.stage(9).npcId(), new Point(222, 70));
        crew = fx.crew(S9, new Point(300, 184));
        kes = crew.get(2);
        Character pel = crew.get(3);
        fx.moveTo(crew.getFirst(), new Point(400, 184));
        fx.moveTo(kes, new Point(700, 184));
        fx.moveTo(pel, new Point(600, 184));
        fx.drop(S9, AgentLpqDefinition.BOSS_KEY, 1, new Point(980, 184), false, 0, 1_000L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        session.narrateOnce("s9-start");
        fx.tick(session, 3_000L);
        assertEquals(kes.getId(), session.keyCarrierId());
        assertEquals(0, kes.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false), "still walking to the key");

        fx.leaveParty(kes);
        fx.outside(kes, 922_010_000);
        fx.tick(session, 4_000L);
        fx.tick(session, 5_000L);

        assertEquals(pel.getId(), session.keyCarrierId(), "the next nearest companion carries the key");
        fx.moveTo(pel, new Point(970, 184));
        fx.tick(session, 6_000L);
        assertEquals(1, pel.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false), "and loots it");
    }
}
