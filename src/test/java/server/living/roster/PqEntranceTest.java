package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import config.CompanionPqKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberState;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The entrances companion runs go in by (P6b HPQ step 5): Lakelis's for KPQ and Tory's for HPQ, each with its quest's
 * definition, entry NPC, seats (KPQ three or four, HPQ three to six preferring four), the monsters a companion is
 * weighed against, the voice at the desk, and the Agent layer's own admission rules and session registry, never the
 * other quest's. An unknown key has no entrance: asking for one throws, so no other quest ever gets KPQ's rules.
 */
class PqEntranceTest {
    private AgentKpqSession kpqSession;
    private AgentHpqSession hpqSession;

    @AfterEach
    void close() {
        AgentKpqSessionRegistry.remove(kpqSession);
        AgentHpqSessionRegistry.remove(hpqSession);
    }

    @Test
    void lakelisAndToryWithTheirQuestsRules() {
        List<String> keys = PqEntrance.keys();
        assertEquals(List.of("kpq", "hpq"), keys.subList(0, 2), "KPQ's first, then HPQ's");
        // states the rule only: keys() is a map's key set, so it cannot repeat (PqEntranceRegistryTest guards register)
        assertEquals(keys.size(), new HashSet<>(keys).size(), "every key once: " + keys);
        assertEquals(CompanionPqKeys.KNOWN, keys, "the kill switch may list exactly the entrances");

        PqEntrance kpq = PqEntrance.of("kpq");
        assertEquals("kpq", kpq.key());
        assertEquals("KerningPQ", kpq.definition().eventManagerName());
        assertEquals(103_000_000, kpq.definition().recruitMapId());
        assertEquals(9_020_000, kpq.entryNpcId());
        assertEquals(new PqEntrance.SeatRules(3, 4, 4), kpq.seatRules());
        assertArrayEquals(new int[] {9_300_001, 9_300_000, 9_300_003}, kpq.dpsMobIds());
        assertSame(LakelisLines.VOICE, kpq.voice());

        PqEntrance hpq = PqEntrance.of("hpq");
        assertEquals("hpq", hpq.key());
        assertEquals("HenesysPQ", hpq.definition().eventManagerName());
        assertEquals(100_000_200, hpq.definition().recruitMapId());
        assertEquals(1_012_112, hpq.entryNpcId());
        assertEquals(new PqEntrance.SeatRules(3, 6, 4), hpq.seatRules());
        assertArrayEquals(new int[] {9_300_062, 9_300_063, 9_300_064}, hpq.dpsMobIds());
        assertSame(ToryLines.VOICE, hpq.voice());

        assertEquals(new PqEntrance.SeatRules(3, 6, 4), PqEntrance.seats("hpq"));
        assertSame(hpq, PqEntrance.of(" HPQ "), "keys are read as the kill switch reads them");
    }

    @Test
    void aKeyWithNoEntranceThrowsAndIsNeverGivenKpqsRules() {
        for (String key : new String[] {"zzpq", "nope", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> PqEntrance.of(key), String.valueOf(key));
            assertThrows(IllegalArgumentException.class, () -> PqEntrance.seats(key), String.valueOf(key));
            assertNull(PqEntrance.find(key), String.valueOf(key));
        }
    }

    @Test
    void eachEntranceReadsItsOwnQuestsSessions() {
        kpqSession = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, 1L, 10, 3, 1_000L);
        kpqSession.addMember(10, AgentKpqMemberState.MemberType.HUMAN);
        kpqSession.addMember(900, AgentKpqMemberState.MemberType.AGENT);
        AgentKpqSessionRegistry.registerComplete(kpqSession);
        hpqSession = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 1L, 20, 3, 1_000L);
        hpqSession.addMember(20, AgentHpqMemberState.MemberType.HUMAN);
        hpqSession.addMember(901, AgentHpqMemberState.MemberType.AGENT);
        AgentHpqSessionRegistry.registerComplete(hpqSession);
        hpqSession.setLeadership(901, 901); // the lead passed inside the run
        PqEntrance kpq = PqEntrance.of("kpq");
        PqEntrance hpq = PqEntrance.of("hpq");

        assertEquals(new PqEntrance.LiveSession(kpqSession.sessionId(), 10), kpq.session(10, kpqSession.sessionId()));
        assertNull(kpq.session(10, "another"), "another run of the same leader");
        assertNull(hpq.session(10, kpqSession.sessionId()), "not HPQ's");
        assertEquals(new PqEntrance.LiveSession(hpqSession.sessionId(), 901), hpq.session(20, hpqSession.sessionId()));
        assertNull(kpq.session(20, hpqSession.sessionId()), "not KPQ's");

        assertEquals("operator-session", kpq.registrationBlocker(10, List.of(10, 900)));
        assertEquals("", hpq.registrationBlocker(10, List.of(10, 900)));
        assertEquals("member-901", hpq.registrationBlocker(30, List.of(30, 901)));
        assertEquals("", kpq.registrationBlocker(30, List.of(30, 901)));

        assertTrue(kpq.holds(900, kpqSession.sessionId()));
        assertFalse(hpq.holds(900, kpqSession.sessionId()));
        assertTrue(hpq.holds(901, hpqSession.sessionId()));
        assertFalse(hpq.holds(901, "another"));
        assertFalse(kpq.holds(901, hpqSession.sessionId()));
    }

    @Test
    void aMembersProblemIsItsQuestsAdmissionWords() {
        Character mira = member("Mira", 100_000_200, 25);
        PqEntrance kpq = PqEntrance.of("kpq");
        PqEntrance hpq = PqEntrance.of("hpq");

        assertEquals("", hpq.memberProblem(mira, false));
        assertEquals("Mira is not at the Kerning KPQ entrance", kpq.memberProblem(mira, false));
        assertEquals("Mira must be level 10-255 for HPQ", hpq.memberProblem(member("Mira", 100_000_200, 8), false));
        assertEquals("Mira is not at the Henesys PQ entrance", hpq.memberProblem(member("Mira", 103_000_000, 25), true));
        assertEquals("", kpq.memberProblem(member("Mira", 103_000_000, 25), true));
    }

    @Test
    void onlyKpqIsAdmittedOnTheLeadersIoThread() {
        assertTrue(PqEntrance.of("kpq").admitsOnIo(), "as before: admitted and started in one I/O step");
        assertFalse(PqEntrance.of("hpq").admitsOnIo(), "HPQ's admission runs on companion-ops");
    }

    @Test
    void extrasWaitBesideLakelisAndOnTheFloorBelowToryOffHerLedge() {
        MapleMap kerning = mock(MapleMap.class);
        NPC lakelis = mock(NPC.class);
        when(lakelis.getPosition()).thenReturn(new Point(1400, 120));
        when(kerning.getNPCById(9_020_000)).thenReturn(lakelis);
        PqEntrance kpq = PqEntrance.of("kpq");
        assertEquals(new Point(1440, 120), kpq.beside(kerning, 0));
        assertEquals(new Point(1470, 120), kpq.beside(kerning, 1));

        MapleMap park = mock(MapleMap.class);
        NPC tory = mock(NPC.class);
        when(tory.getPosition()).thenReturn(new Point(1434, 364));
        when(park.getNPCById(1_012_112)).thenReturn(tory);
        when(park.getPointBelow(any())).thenAnswer(inv -> {
            Point at = inv.getArgument(0);
            return at.x >= 1357 && at.x <= 1523 ? new Point(at.x, 364) : new Point(at.x, 574);
        });
        PqEntrance hpq = PqEntrance.of("hpq");
        assertEquals(new Point(1554, 574), hpq.beside(park, 0), "past the end of her ledge, on the park floor");
        assertEquals(new Point(1584, 574), hpq.beside(park, 1));
        assertEquals(new Point(1614, 574), hpq.beside(park, 2));

        doReturn(null).when(park).getPointBelow(any());
        assertEquals(new Point(1554, 364), hpq.beside(park, 0), "no ground found: the point itself");
        assertNull(hpq.beside(kerning, 0), "Tory is not on that map");
        assertNull(kpq.beside(null, 0));
    }

    /** A party member named {@code name} on {@code mapId} at {@code level}, with room in its Etc tab. */
    private static Character member(String name, int mapId, int level) {
        Character chr = mock(Character.class);
        when(chr.getName()).thenReturn(name);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getLevel()).thenReturn(level);
        Inventory inventory = mock(Inventory.class);
        when(chr.getInventory(InventoryType.ETC)).thenReturn(inventory);
        when(chr.getInventory(InventoryType.USE)).thenReturn(inventory);
        return chr;
    }
}
