package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberState;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each quest's entrance is built in its own file and registered in its own slot: KPQ's and HPQ's first, every key
 * once (a second registration of a key throws, in any case or spacing), and each entrance with its quest's own
 * admission (one without any is refused when it registers) and its own registry's operator and member lookups. Later
 * quests add their keys after HPQ's, so this pins no full list.
 */
class PqEntranceRegistryTest {
    private AgentKpqSession kpqSession;
    private AgentHpqSession hpqSession;

    @AfterEach
    void close() {
        AgentKpqSessionRegistry.remove(kpqSession);
        AgentHpqSessionRegistry.remove(hpqSession);
    }

    @Test
    void kpqAndHpqComeFirstAndEveryKeyOnce() {
        List<String> keys = PqEntrance.keys();

        assertEquals(List.of("kpq", "hpq"), keys.subList(0, 2));
        // states the rule only: keys() is a map's key set, so it cannot repeat; aKeyRegisteredTwiceThrows guards it
        assertEquals(keys.size(), new HashSet<>(keys).size(), "every key once: " + keys);
        for (String key : keys) {
            assertEquals(key, PqEntrance.of(key).key(), "registered under its own key");
        }
    }

    @Test
    void aKeyRegisteredTwiceThrows() {
        Map<String, PqEntrance> entrances = new LinkedHashMap<>();
        PqEntrance kpq = KpqEntrance.create();
        PqEntrance.register(entrances, kpq);
        PqEntrance.register(entrances, HpqEntrance.create());

        assertThrows(IllegalStateException.class, () -> PqEntrance.register(entrances, KpqEntrance.create()));
        assertEquals(List.of("kpq", "hpq"), List.copyOf(entrances.keySet()));
        assertSame(kpq, entrances.get("kpq"), "the first registration stands");
    }

    @Test
    void aKeyIsRegisteredAsItIsLookedUp() {
        Map<String, PqEntrance> entrances = new LinkedHashMap<>();
        PqEntrance.register(entrances, entrance(" KPQ "));

        assertEquals(List.of("kpq"), List.copyOf(entrances.keySet()));
        assertThrows(IllegalStateException.class, () -> PqEntrance.register(entrances, KpqEntrance.create()));
    }

    @Test
    void anEntranceWithoutItsQuestsAdmissionIsRefused() {
        Map<String, PqEntrance> entrances = new LinkedHashMap<>();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> PqEntrance.register(entrances, entrance(" KPQ ", null)));
        assertEquals("no admission for the companion party-quest entrance kpq", refused.getMessage());
        assertTrue(entrances.isEmpty(), "nothing registered");
    }

    @Test
    void eachEntranceLooksUpItsRunsOperatorAndItsMembersApart() {
        kpqSession = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, 1L, 40, 3, 1_000L); // 40 runs it, not in it
        kpqSession.addMember(41, AgentKpqMemberState.MemberType.HUMAN);
        kpqSession.addMember(940, AgentKpqMemberState.MemberType.AGENT);
        AgentKpqSessionRegistry.registerComplete(kpqSession);
        hpqSession = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 1L, 50, 3, 1_000L);
        hpqSession.addMember(51, AgentHpqMemberState.MemberType.HUMAN);
        hpqSession.addMember(950, AgentHpqMemberState.MemberType.AGENT);
        hpqSession.setLeadership(51, 950);
        AgentHpqSessionRegistry.registerComplete(hpqSession);
        PqEntrance kpq = PqEntrance.of("kpq");
        PqEntrance hpq = PqEntrance.of("hpq");

        assertEquals(new PqEntrance.LiveSession(kpqSession.sessionId(), 41), kpq.session(40, kpqSession.sessionId()));
        assertNull(kpq.session(940, kpqSession.sessionId()), "a member is not the run's operator");
        assertTrue(kpq.holds(940, kpqSession.sessionId()));
        assertFalse(kpq.holds(40, kpqSession.sessionId()), "the operator is not one of its members");

        assertEquals(new PqEntrance.LiveSession(hpqSession.sessionId(), 51), hpq.session(50, hpqSession.sessionId()));
        assertNull(hpq.session(950, hpqSession.sessionId()), "a member is not the run's operator");
        assertTrue(hpq.holds(950, hpqSession.sessionId()));
        assertFalse(hpq.holds(50, hpqSession.sessionId()), "the operator is not one of its members");
    }

    /** An entrance under {@code key} as written, with an admission that admits no one and nothing else of its own. */
    private static PqEntrance entrance(String key) {
        return entrance(key, (leader, members, seed, nowMs) -> new PqEntryPort.Admitted(false, "", null));
    }

    private static PqEntrance entrance(String key, PqEntrance.Admit admit) {
        return new PqEntrance(key, 9_020_000, new PqEntrance.SeatRules(3, 4, 4), new int[0], null, null, null, null,
                null, null, false, null, null, null, admit);
    }
}
