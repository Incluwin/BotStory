package server.living.roster;

import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lpq.AgentLpqSession;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.TapOut;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The companion-run fixture plays LPQ (P6g LPQ C5.2; integration rule 16): atEntrance("lpq") takes Ann and her
 * companions to the Red Sign on the 101st floor of Eos Tower, the sign standing where LpqFixtureEntrance puts it and the
 * ground below the sign's ledge its steps (the fixture's npcAt and groundBelow dispatch), so the extras' spots are on the
 * steps. Her Yes there admits a real LPQ session through LPQ's fixture admission and starts LudiPQ, and a companion
 * that taps out comes out on the tower's exit map (the fixture entrance's exitMap) and is held there.
 */
class LpqFixtureEntranceTest {
    private static final int SIGN_FLOOR = 221_024_500;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona tobi;

    @BeforeEach
    void annAtTheRedSignWithFiveCompanions() {
        CompanionPqFixture.stand(game.ann, 40, Job.CLERIC);
        fx.atEntrance("lpq");
        fx.companion("mira", "Mira", 900, 38, Job.HUNTER);
        tobi = fx.companion("tobi", "Tobi", 901, 41, Job.SPEARMAN);
        fx.companion("pip", "Pip", 902, 39, Job.FP_WIZARD);
        fx.companion("casse", "Casse", 903, 42, Job.BANDIT);
        fx.companion("rin", "Rin", 904, 36, Job.ASSASSIN);
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void atEntranceTakesAnnAndHerCompanionsToTheRedSign() {
        MapleMap floor = game.map(SIGN_FLOOR);

        assertEquals("lpq", fx.pqKey);
        assertEquals(SIGN_FLOOR, game.ann.getMapId());
        for (int id = 900; id <= 904; id++) {
            assertEquals(SIGN_FLOOR, game.bots.get(id).getMapId(), "companion " + id);
        }
        assertEquals(LpqFixtureEntrance.RED_SIGN_AT, floor.getNPCById(2_040_034).getPosition(),
                "the sign stands where LPQ's fixture entrance puts it");
        assertEquals(new Point(79, 1714), floor.getPointBelow(new Point(79, 1664)), "the first step below the ledge");
        assertEquals(new Point(189, 1670), floor.getPointBelow(new Point(189, 1600)), "the sign's ledge");
        assertEquals(new Point(79, 1714), PqEntrance.of("lpq").beside(floor, 0));
        assertEquals(new Point(-1, 1787), PqEntrance.of("lpq").beside(floor, 2), "the steps further down");
    }

    @Test
    void aYesAtTheSignAdmitsAnLpqSessionAndATapOutComesOutOnTheTowersExitMap() {
        fx.ready();

        CompanionRun run = fx.yes();

        assertNotNull(run, game.calls.toString());
        assertTrue(game.calls.contains("start lpq"), game.calls.toString());
        AgentLpqSession session = AgentLpqSessionRegistry.forOperator(ANN);
        assertNotNull(session, "LPQ's own registry holds the run");
        assertSame(session, fx.doors.others.get("lpq"));
        assertEquals(6, session.members().size(), "Ann and her five companions");
        assertEquals("pq:lpq:" + session.sessionId(), run.key);

        int exit = fx.fixtureEntrance("lpq").exitMap();
        assertEquals(922_010_000, exit, "the tower's exit map");
        game.annEntersInstance(922_010_100);
        game.bot(tobi).changeMap(game.map(exit), new Point(100, 200));
        fx.lifecycle.tapOut(new TapOut("lpq", run.sessionId, 901, exit, 3_000L));

        assertEquals(RosterState.HOLD, game.row(tobi).state(), "held where it came out while Ann is inside");
        assertEquals(exit, game.row(tobi).holdMapId());
    }
}
