package server.living.population.needs;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.CitizenSessions;
import server.living.population.Population;
import server.living.population.PresenceScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A rival crew's help request (docs/BOT_CONCEPT_PLAN.md F7.4a) is a PARTY need on the needs board: posted by the crew's
 * leader for Zakum with its player as the guest, one at a time, listed only for that player ("wants you along for
 * zakum"), never for anyone else, and gone at its deadline like any party request.
 */
class NeedHelpWantedTest {
    private static final int KIM = 5001;
    private static final int ANN = 10;
    private static final int BO = 11;
    private static final int DOOR = 211042300;
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final Rows store = new Rows();
    private final Character ann = mock(Character.class);
    private final Character bo = mock(Character.class);
    private NeedLedger ledger;
    private CitizenSession leader;

    @BeforeEach
    void setUp() {
        config.NEEDS_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        Citizen kim = new Citizen(KIM, "Kim", "evening", 0, 0, "el-nath", "fighter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        when(population.citizen(KIM)).thenReturn(kim);
        leader = CitizenSessions.loaded(kim, 0L, NOW + 600 * MINUTE);
        when(ann.getId()).thenReturn(ANN);
        when(bo.getId()).thenReturn(BO);
        ledger = new NeedLedger(population, store, null, new NoItems());
    }

    @Test
    void theCrewsLeaderPostsAPartyNeedInvitingItsPlayer() {
        Need need = ledger.helpWanted(leader, "zakum", DOOR, ANN, NOW + 30 * MINUTE, NOW);

        assertNotNull(need);
        assertEquals(NeedKind.PARTY, need.kind());
        assertEquals(KIM, need.citizenId());
        assertEquals(DOOR, need.mapId());
        assertEquals("zakum|guest:10", need.detail());
        assertEquals(ANN, NeedLedger.guestOf(need));
        assertEquals("you along for zakum", ledger.what(need));
        assertEquals("zakum", ledger.forWords(need));
        assertNull(ledger.helpWanted(leader, "zakum", DOOR, ANN, NOW + 30 * MINUTE, NOW), "one at a time");
    }

    @Test
    void onlyItsPlayerSeesItOnTheBoard() {
        ledger.helpWanted(leader, "zakum", DOOR, ANN, NOW + 30 * MINUTE, NOW);
        NeedScreens screens = new NeedScreens(population, ledger);

        assertEquals(1, screens.listed(ann, 211000000, NOW).size());
        assertTrue(screens.listed(bo, 211000000, NOW).isEmpty());
    }

    @Test
    void anOrdinaryPartyNeedInvitesNoOne() {
        ledger.partyShort(leader, "kpq", 103000000, 2, "", NOW);

        Need need = ledger.open().getFirst();
        assertEquals(0, NeedLedger.guestOf(need));
        assertEquals("2 more", ledger.what(need));
    }

    @Test
    void itEndsAtItsDeadline() {
        ledger.helpWanted(leader, "zakum", DOOR, ANN, NOW + 30 * MINUTE, NOW);

        ledger.expire(NOW + 31 * MINUTE);

        assertTrue(ledger.open().isEmpty());
        assertEquals(Need.State.GONE, store.rows.values().iterator().next().state());
    }

    @Test
    void theBoardOffPostsNothing() {
        config.NEEDS_ENABLED = false;

        assertNull(ledger.helpWanted(leader, "zakum", DOOR, ANN, NOW + 30 * MINUTE, NOW));
    }

    /** No item names: a party request names none. */
    private static final class NoItems implements NeedLedger.Items {
        @Override
        public String name(int itemId) {
            return null;
        }

        @Override
        public int successRate(int scrollId) {
            return 0;
        }

        @Override
        public boolean canApplyScroll(int scrollId, int equipId) {
            return false;
        }

        @Override
        public boolean tradeable(int itemId) {
            return false;
        }

        @Override
        public boolean baseOrBetter(int itemId, Map<String, Object> stats) {
            return false;
        }
    }

    /** living_need in memory. */
    private static final class Rows extends NeedStore {
        final Map<Integer, Need> rows = new HashMap<>();
        private int next = 1;

        @Override
        public List<Need> live(long nowMs) {
            return new ArrayList<>();
        }

        @Override
        public List<Need> endedSince(long sinceMs) {
            return new ArrayList<>();
        }

        @Override
        public Set<Integer> gearAsked(int citizenId) {
            return new HashSet<>();
        }

        @Override
        public int insert(Need need) {
            int id = next++;
            rows.put(id, need.withId(id));
            return id;
        }

        @Override
        public void update(Need need) {
            rows.put(need.id(), need);
        }

        @Override
        public Set<Integer> escrowHolders() {
            return new HashSet<>();
        }
    }
}
