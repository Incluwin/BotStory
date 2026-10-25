package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentClaimGuard;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GPQ plan B4: a join-after-start run (Sharenian). Its owner went in alone when the guild was called; while the event
 * takes joiners (canJoin 1) each registered companion on the camp walks to Shuang and, in her reach, joins by her join
 * choice, one conversation per tick across the run; a joined member stands in the event on its first map and is
 * adopted there. Nobody misses the start while joining is open; once it closes, a member still outside misses it.
 */
class AgentBossRunJoinAfterStartTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final long NOW = 1_000_000L;
    private static final int CAMP = 101_030_104;
    private static final int EXCAVATION = 990_000_000;
    private static final int THRONE = 990_000_900;
    private static final int SHUANG = 9_040_000;
    private static final Point SHUANG_AT = new Point(-58, 1305);

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final AgentBossRunDefinition sharenian = new AgentBossRunDefinition("gpq", null, CAMP, THRONE,
            990_001_000, SHUANG, List.of(1), -1, -1, () -> fight, false, List.of(), false, true,
            AgentBossRunDefinition.DeathMode.HOLD_UNTIL_REVIVABLE);
    private final Map<String, String> properties = new HashMap<>();
    private EventInstanceManager event;

    @BeforeEach
    void setUp() {
        event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(null);
        when(event.getPlayerCount()).thenReturn(1);
        when(event.getPlayerById(anyInt())).thenReturn(null);
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get((String) call.getArgument(0)));
        Character owner = ports.human(OWNER);
        when(event.getPlayers()).thenReturn(List.of(owner));
        when(event.getPlayerById(OWNER)).thenReturn(owner);
        properties.put(AgentBossRunDefinition.CAN_JOIN_PROPERTY, "1");
        ports.npcs.put(SHUANG, SHUANG_AT);
        ports.runNpc = (agent, npcId, selections) -> {
            if (npcId == SHUANG && selections.length == 1 && selections[0] == 1
                    && "1".equals(properties.get(AgentBossRunDefinition.CAN_JOIN_PROPERTY))) {
                ports.eventOf.put(agent.getId(), event); // eim.registerPlayer warps it in
                ports.place(agent.getId(), EXCAVATION, 0, 161, 70);
            }
            return true;
        };
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return true;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return true;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentClaimGuard.install(null);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void aJoinAfterStartRunIsTheOneWithAnEntryNpcAndJoinChoicesButNoExpedition() {
        assertTrue(sharenian.joinsAfterStart());
        assertTrue(AgentBossRunCatalog.GPQ.joinsAfterStart());
        for (AgentBossRunDefinition definition : AgentBossRunCatalog.all()) {
            assertEquals(definition == AgentBossRunCatalog.GPQ, definition.joinsAfterStart(), definition.key());
        }
    }

    @Test
    void eachMemberWalksToShuangAndJoinsOneConversationPerTickThenIsAdoptedInside() {
        AgentBossRunSession session = admitted();
        ports.place(THANE, CAMP, 600, 1305, 70);
        ports.place(ISOLDE, CAMP, 700, 1305, 70);

        session.tick(ports, host, NOW + 500L);
        assertEquals(2, ports.count("navigate"), "both walk to her: " + ports.calls);
        assertEquals(0, ports.count("runNpc"));

        ports.positionOf.put(THANE, new Point(-20, 1305));
        ports.positionOf.put(ISOLDE, new Point(-100, 1305));
        ports.calls.clear();
        session.tick(ports, host, NOW + 1_000L);
        assertEquals(List.of("runNpc " + THANE + " " + SHUANG + " 1"), runNpcCalls(), "one talk this tick");

        ports.calls.clear();
        session.tick(ports, host, NOW + 1_500L);
        assertEquals(List.of("runNpc " + ISOLDE + " " + SHUANG + " 1"), runNpcCalls(), "the next one's turn");
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase(), "adopted on the first map");

        session.tick(ports, host, NOW + 2_000L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ISOLDE).phase());
        assertTrue(fight.calls.contains("adopt " + THANE + " 0"), fight.calls.toString());
    }

    @Test
    void nobodyMissesTheStartWhileJoiningIsOpenButAMemberStillOutsideMissesItOnceItCloses() {
        AgentBossRunSession session = admitted();
        ports.place(THANE, CAMP, 600, 1305, 70);
        ports.place(ISOLDE, CAMP, -20, 1305, 70);
        session.tick(ports, host, NOW + 500L); // Isolde joins
        session.tick(ports, host, NOW + 1_000L);

        session.tick(ports, host, NOW + AgentBossRunSession.startGraceMs() + 1_000L);
        assertEquals(AgentBossRunMember.Phase.REGISTERED, session.member(THANE).phase(), "still walking to her");
        assertEquals(null, session.member(THANE).out());

        properties.put(AgentBossRunDefinition.CAN_JOIN_PROPERTY, "0"); // the three minutes are over
        ports.calls.clear();
        session.tick(ports, host, NOW + AgentBossRunSession.startGraceMs() + 1_500L);
        assertEquals(0, ports.count("runNpc"), "no talk once she takes no joiners");
        assertEquals(AgentBossRunHost.Out.MISSED_START, session.member(THANE).out());
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(ISOLDE).phase());
    }

    @Test
    void aMemberThatTalkedWaitsBeforeTalkingAgain() {
        ports.runNpc = (agent, npcId, selections) -> true; // she turns it away (no guild of its owner's, say)
        AgentBossRunSession session = admitted();
        ports.place(THANE, CAMP, -20, 1305, 70);

        session.tick(ports, host, NOW + 500L);
        session.tick(ports, host, NOW + 1_000L);
        session.tick(ports, host, NOW + 500L + AgentBossRunSession.reenterTalkEveryMs());

        assertEquals(2, ports.count("runNpc " + THANE), ports.calls.toString());
    }

    private List<String> runNpcCalls() {
        return ports.calls.stream().filter(call -> call.startsWith("runNpc")).toList();
    }

    private AgentBossRunSession admitted() {
        long sessionId = AgentBossRunService.openForEvent(ports, ports.human(OWNER), sharenian, event,
                List.of(THANE, ISOLDE), NOW).sessionId();
        ports.calls.clear();
        return AgentBossRunRegistry.byId(sessionId);
    }
}
