package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Companions stand off Tory (P6b HPQ step 6, the step 0 live finding): following their owner onto her ledge in Henesys
 * Park they crowded it and hid her, so while the owner stands at her (on her ledge, or jumping on it) each of the
 * owner's companions waits on the park floor past the ledge's end, at the spots the extras of a run wait at
 * (PqEntrance.beside), one spot each in the order they came out. Anywhere else, off her ledge, another owner's
 * companion, Lakelis's entrance, Tory with the kill switch not listing hpq, and Tory while the background population
 * runs her: beside the owner, as before.
 */
class ToryStandOffTest {
    private static final int BEN = 10;
    private static final int ANN = 11;
    private static final int CASSE = 901;
    private static final int MIRA = 902;
    private static final int PARK = 100_000_200;
    private static final int KERNING = 103_000_000;
    private static final Point ON_HER_LEDGE = new Point(1434, 364);

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final MapleMap park = mock(MapleMap.class);
    private final Character ben = mock(Character.class);
    private final CompanionFollowGuard guard = new CompanionFollowGuard(world);

    @BeforeEach
    void benLeadsCasseAndMiraOntoToryLedge() {
        when(world.roster()).thenReturn(roster);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(config);
        config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        NPC tory = mock(NPC.class);
        when(tory.getPosition()).thenReturn(new Point(1434, 352)); // Map.wz 100000200 life 3: x 1434, y 352, cy 364
        when(park.getId()).thenReturn(PARK);
        when(park.getNPCById(1_012_112)).thenReturn(tory);
        when(park.getPointBelow(any())).thenAnswer(inv -> {
            Point at = inv.getArgument(0);
            return at.x > 1523 ? new Point(at.x, 574) : new Point(at.x, 364); // the park floor past her ledge
        });
        when(ben.getId()).thenReturn(BEN);
        when(ben.getMapId()).thenReturn(PARK);
        when(ben.getMap()).thenReturn(park);
        when(ben.getPosition()).thenReturn(new Point(ON_HER_LEDGE));
        when(roster.hasCompanions(ben)).thenReturn(true);
        companion(CASSE, "casse", BEN);
        companion(MIRA, "mira", BEN);
        register(BEN, CASSE);
        register(BEN, MIRA);
    }

    @AfterEach
    void unregister() {
        AgentRuntimeRegistry.unregisterLeader(BEN);
        AgentRuntimeRegistry.unregisterLeader(ANN);
    }

    @Test
    void onHerLedgeTheOwnersCompanionsWaitOnTheParkFloorPastItsEndOneSpotEach() {
        assertEquals(new Point(1554, 574), guard.waitsAt(CASSE, ben));
        assertEquals(new Point(1584, 574), guard.waitsAt(MIRA, ben));

        when(ben.getPosition()).thenReturn(new Point(1360, 364)); // the ledge's left end
        assertEquals(new Point(1554, 574), guard.waitsAt(CASSE, ben));
        when(ben.getPosition()).thenReturn(new Point(1520, 364)); // its right end
        assertEquals(new Point(1584, 574), guard.waitsAt(MIRA, ben));
        when(ben.getPosition()).thenReturn(new Point(1440, 290)); // jumping on it
        assertEquals(new Point(1554, 574), guard.waitsAt(CASSE, ben));
    }

    @Test
    void offHerLedgeTheyFollowBesideTheOwnerAsBefore() {
        when(ben.getPosition()).thenReturn(new Point(1300, 574)); // the park floor
        assertNull(guard.waitsAt(CASSE, ben));
        when(ben.getPosition()).thenReturn(new Point(1400, 484)); // the step below her ledge
        assertNull(guard.waitsAt(CASSE, ben));
        when(ben.getPosition()).thenReturn(new Point(1600, 304)); // the platform up to her right
        assertNull(guard.waitsAt(CASSE, ben));
        when(ben.getPosition()).thenReturn(new Point(200, 364)); // across the park at her height
        assertNull(guard.waitsAt(CASSE, ben));
    }

    @Test
    void onlyTheOwnersOwnCompanionsStandOff() {
        companion(MIRA, "mira", ANN); // Ann's companion, following Ben for now
        assertNull(guard.waitsAt(MIRA, ben));
        assertNull(guard.waitsAt(4242, ben), "a plain Agent or a citizen");

        when(roster.hasCompanions(ben)).thenReturn(false);
        assertNull(guard.waitsAt(CASSE, ben), "no companions at all");
    }

    @Test
    void theKillSwitchAndTheBackgroundPopulationLeaveToryStock() {
        config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        assertNull(guard.waitsAt(CASSE, ben));

        config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        try (MockedStatic<AgentHpqPopulationRuntime> population = mockStatic(AgentHpqPopulationRuntime.class)) {
            population.when(AgentHpqPopulationRuntime::backgroundEnabled).thenReturn(true);
            assertNull(guard.waitsAt(CASSE, ben));
        }
        assertEquals(new Point(1554, 574), guard.waitsAt(CASSE, ben));
    }

    @Test
    void lakelisEntranceIsUnchanged() {
        MapleMap kerning = mock(MapleMap.class);
        NPC lakelis = mock(NPC.class);
        when(lakelis.getPosition()).thenReturn(new Point(1400, 120));
        when(kerning.getId()).thenReturn(KERNING);
        when(kerning.getNPCById(9_020_000)).thenReturn(lakelis);
        when(ben.getMapId()).thenReturn(KERNING);
        when(ben.getMap()).thenReturn(kerning);
        when(ben.getPosition()).thenReturn(new Point(1400, 120));

        assertNull(guard.waitsAt(CASSE, ben));
    }

    @Test
    void aMapWithoutHerOrAnOwnerNowhereNamesNoSpot() {
        when(park.getNPCById(1_012_112)).thenReturn(null);
        assertNull(guard.waitsAt(CASSE, ben));

        when(ben.getMap()).thenReturn(null);
        assertNull(guard.waitsAt(CASSE, ben));
        assertNull(guard.waitsAt(CASSE, null));
    }

    private void companion(int characterId, String personaId, int ownerId) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(personaId);
        when(residents.personaOfCharacterId(characterId)).thenReturn(persona);
        when(roster.cachedOwnerOf(personaId)).thenReturn(ownerId);
    }

    private static void register(int ownerId, int characterId) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(ownerId);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(characterId);
        AgentRuntimeRegistry.registerEntry(ownerId, new AgentRuntimeEntry(bot, owner, null));
    }
}
