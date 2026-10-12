package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.expeditions.Expedition;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run rules other Agent code reads from the registry (P8). A run that never targets fakes (Zakum) names the fake
 * body of its own event, so the combat selector never takes it as a secondary or projectile target; a run with marker
 * items (Zakum's Eye of Fire) keeps its INSIDE and LEAVING members off them, so the altar can take the Eye. Easy
 * Balrog's run has neither rule, and nothing holds without an open run.
 */
class AgentBossRunRegistryRulesTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final int INGRID = 12;
    private static final int SORREL = 13;
    private static final int FLINT = 14;
    private static final int STRANGER = 99;
    private static final long NOW = 1_000_000L;
    private static final int ZAKUM_BODY = 8_800_000;
    private static final int ZAKUM_ARM = 8_800_003;
    private static final int EYE = AgentZakumDefinition.EYE_ITEM_ID;

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void aZakumRunNamesTheFakeBodyOfItsOwnEventOnly() {
        Expedition zakum = mock(Expedition.class);
        open(1L, AgentBossRunCatalog.ZAKUM, zakum);
        MapleMap altar = map(event(zakum));

        assertTrue(AgentBossRunRegistry.neverTarget(part(ZAKUM_BODY, true, altar)));
        assertFalse(AgentBossRunRegistry.neverTarget(part(ZAKUM_BODY, false, altar)), "the real body is a target");
        assertFalse(AgentBossRunRegistry.neverTarget(part(ZAKUM_ARM, false, altar)));
        assertFalse(AgentBossRunRegistry.neverTarget(part(ZAKUM_BODY, true, map(event(mock(Expedition.class))))),
                "another expedition's altar, with no companion run");
        assertFalse(AgentBossRunRegistry.neverTarget(part(ZAKUM_BODY, true, map(mock(EventInstanceManager.class)))),
                "an event with no expedition");
        assertFalse(AgentBossRunRegistry.neverTarget(part(ZAKUM_BODY, true, map(null))), "a field map");
        assertFalse(AgentBossRunRegistry.neverTarget(part(ZAKUM_BODY, true, null)), "no map");
        assertFalse(AgentBossRunRegistry.neverTarget(null));
    }

    @Test
    void easyBalrogsRunKeepsTodaysTargetsAndNothingHoldsWithoutAnOpenRun() {
        Expedition easy = mock(Expedition.class);
        AgentBossRunSession session = open(1L, AgentBossRunCatalog.EASY_BALROG, easy);
        // EasyBalrogEncounterService spawns the body (8830007) fake until its seal breaks.
        Monster fake = part(AgentBalrogDefinition.BODY_MOB, true, map(event(easy)));

        assertFalse(AgentBossRunRegistry.neverTarget(fake), "Easy's fake body keeps today's path");

        Expedition zakum = mock(Expedition.class);
        AgentBossRunSession zakumRun = open(2L, AgentBossRunCatalog.ZAKUM, zakum);
        Monster zakumFake = part(ZAKUM_BODY, true, map(event(zakum)));
        assertTrue(AgentBossRunRegistry.neverTarget(zakumFake));
        AgentBossRunRegistry.remove(zakumRun);
        AgentBossRunRegistry.remove(session);
        assertFalse(AgentBossRunRegistry.neverTarget(zakumFake), "the run ended");
    }

    /**
     * P11: in a Normal Balrog run the fake body (8830000 until both claws fall) is never a target, nor are the parts the
     * boss rules catalog lists: the claw helpers 8830004/8830005 and the seal 8830006, whose death would release the
     * second claw early. The claws, the real body and the summoned Balrogs stay targets; outside the run nothing holds.
     */
    @Test
    void normalBalrogsRunNeverTargetsItsFakeBodyHelpersOrSeal() {
        Expedition normal = mock(Expedition.class);
        AgentBossRunSession session = open(1L, AgentBossRunCatalog.BALROG_NORMAL, normal);
        MapleMap battle = map(event(normal));

        assertTrue(AgentBossRunRegistry.neverTarget(part(8830000, true, battle)), "the fake body");
        for (int helperOrSeal : List.of(8830004, 8830005, 8830006)) {
            assertTrue(AgentBossRunRegistry.neverTarget(part(helperOrSeal, false, battle)), "listed: " + helperOrSeal);
        }
        for (int target : List.of(8830000, 8830001, 8830002, 6400005, 6400006)) {
            assertFalse(AgentBossRunRegistry.neverTarget(part(target, false, battle)), "a target: " + target);
        }
        assertFalse(AgentBossRunRegistry.neverTarget(part(8830006, false, map(event(mock(Expedition.class))))),
                "another expedition's seal, with no companion run");
        assertFalse(AgentBossRunRegistry.neverTarget(part(8830006, false, map(null))), "a field map");

        Expedition easy = mock(Expedition.class);
        open(2L, AgentBossRunCatalog.EASY_BALROG, easy);
        assertFalse(AgentBossRunRegistry.neverTarget(part(AgentBalrogDefinition.RELEASE_SEAL_MOB, false,
                map(event(easy)))), "Easy's seal keeps today's path");
        AgentBossRunRegistry.remove(session);
        assertFalse(AgentBossRunRegistry.neverTarget(part(8830006, false, battle)), "the run ended");
    }

    @Test
    void theEyeStaysOnTheFloorForInsideAndLeavingZakumMembersOnly() {
        AgentBossRunSession session = open(1L, AgentBossRunCatalog.ZAKUM, mock(Expedition.class));
        session.addMember(THANE, OWNER, NOW);
        session.addMember(ISOLDE, OWNER, NOW).registered();
        AgentBossRunMember ingrid = session.addMember(INGRID, OWNER, NOW);
        ingrid.registered();
        ingrid.adopted();
        AgentBossRunMember sorrel = session.addMember(SORREL, OWNER, NOW);
        sorrel.registered();
        sorrel.adopted();
        sorrel.leaving(AgentBossRunHost.Out.EXITED, NOW);
        AgentBossRunMember flint = session.addMember(FLINT, OWNER, NOW);
        flint.registered();
        flint.adopted();
        flint.leftRun(AgentBossRunHost.Out.DIED);
        MapItem eye = drop(EYE, 0);

        assertTrue(AgentBossRunRegistry.preservesMarker(agent(INGRID), eye), "INSIDE");
        assertTrue(AgentBossRunRegistry.preservesMarker(agent(SORREL), eye), "LEAVING");
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(THANE), eye), "WAITING");
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(ISOLDE), eye), "REGISTERED");
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(FLINT), eye), "OUT");
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(STRANGER), eye), "no member");
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(INGRID), drop(AgentLootEligibility.KPQ_COUPON, 0)));
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(INGRID), drop(AgentLootEligibility.KPQ_PASS, 0)));
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(INGRID), drop(0, 500)), "meso");
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(INGRID), null));
        assertFalse(AgentBossRunRegistry.preservesMarker(null, eye));

        AgentBossRunRegistry.remove(session);
        assertFalse(AgentBossRunRegistry.preservesMarker(agent(INGRID), eye), "the run ended");
    }

    @Test
    void easyBalrogMembersStillPickUpTheEye() {
        AgentBossRunSession session = open(1L, AgentBossRunCatalog.EASY_BALROG, mock(Expedition.class));
        AgentBossRunMember thane = session.addMember(THANE, OWNER, NOW);
        thane.registered();
        thane.adopted();

        assertFalse(AgentBossRunRegistry.preservesMarker(agent(THANE), drop(EYE, 0)));
    }

    private static AgentBossRunSession open(long sessionId, AgentBossRunDefinition definition, Expedition expedition) {
        AgentBossRunSession session = new AgentBossRunSession(sessionId, definition, expedition,
                new FakeBossRunPorts.Fight(), NOW);
        AgentBossRunRegistry.put(session);
        return session;
    }

    private static EventInstanceManager event(Expedition expedition) {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
        return event;
    }

    private static MapleMap map(EventInstanceManager event) {
        MapleMap map = mock(MapleMap.class);
        when(map.getEventInstance()).thenReturn(event);
        return map;
    }

    private static Monster part(int mobId, boolean fake, MapleMap map) {
        Monster part = mock(Monster.class);
        when(part.getId()).thenReturn(mobId);
        when(part.isFake()).thenReturn(fake);
        when(part.getMap()).thenReturn(map);
        return part;
    }

    private static Character agent(int agentId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(agentId);
        return agent;
    }

    private static MapItem drop(int itemId, int meso) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getMeso()).thenReturn(meso);
        when(drop.isPlayerDrop()).thenReturn(true);
        return drop;
    }
}
