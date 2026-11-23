package server.living.population.records;

import client.Character;
import config.PopulationConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.living.population.Population;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Field-boss kills on the records (docs/WORLD_SYSTEMS_PLAN.md N6.1): a catalogued boss's death on an ordinary map is
 * booked through ClearLog with the top damager's party on the map as members, first hit to death as the time and the
 * boss key as the quest; the rival crews hear of it like any clear (N6.4); the switch stops the rows but not the kill's
 * listeners; uncatalogued monsters and event maps book nothing.
 */
class FieldBossKillsTest {
    private static final String STUMPY = "field.stumpy";
    private static final int STUMPY_MOB = 3220000;
    private static final int STUMPY_MAP = 101030404;
    private static final long NOW = 1_800_000_000_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final ClearLogTest.MemoryStore store = new ClearLogTest.MemoryStore();
    private final ClearLogTest.FakePorts clearPorts = new ClearLogTest.FakePorts();
    private final ClearLogTest.FakeCrews crews = new ClearLogTest.FakeCrews();
    private final FieldBosses bosses = new FieldBosses(List.of(new FieldBosses.Boss(STUMPY, "Stumpy", List.of(STUMPY_MOB),
            List.of(STUMPY_MAP), 35, 30, 60, "script", List.of("stumpy"))));
    private final Map<Integer, Character> onMap = new HashMap<>();
    private final MapleMap map = mock(MapleMap.class);
    private final List<FieldBossKills.Kill> heard = new ArrayList<>();
    private boolean recordsOn = true;
    private ClearLog clears;
    private FieldBossKills kills;

    @BeforeEach
    void setUp() {
        when(population.config()).thenReturn(config);
        clears = new ClearLog(population, store, clearPorts, new QuestLabels(List.of(), bosses));
        clears.listen(crews);
        kills = new FieldBossKills(new FieldBossKills.Ports() {
            @Override
            public FieldBosses bosses() {
                return bosses;
            }

            @Override
            public boolean recordsOn() {
                return recordsOn;
            }

            @Override
            public void book(List<Character> members, String key, String instance, long startedAtMs, long nowMs) {
                clears.cleared(members, key, instance, startedAtMs, nowMs);
            }
        });
        kills.onKill(heard::add);
        when(map.getId()).thenReturn(STUMPY_MAP);
        when(map.getCharacterById(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> onMap.get(call.<Integer>getArgument(0)));
    }

    private Character character(int id, String name, char kind, boolean here) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMapId()).thenReturn(STUMPY_MAP);
        clearPorts.kinds.put(id, kind);
        if (here) {
            onMap.put(id, chr);
        }
        return chr;
    }

    private static void party(Character... members) {
        Party party = mock(Party.class);
        List<PartyCharacter> list = new ArrayList<>();
        for (Character member : members) {
            PartyCharacter one = mock(PartyCharacter.class);
            int id = member.getId();
            when(one.getId()).thenReturn(id);
            list.add(one);
            when(member.getParty()).thenReturn(party);
        }
        when(party.getMembers()).thenReturn(list);
    }

    private static Monster boss(int mobId, long firstHitAtMs, Map<Integer, Long> damage) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.firstHitAtMs()).thenReturn(firstHitAtMs);
        damage.forEach((id, dealt) -> when(monster.damageBy(id)).thenReturn(dealt));
        return monster;
    }

    @Test
    void theTopDamagersPartyOnTheMapIsBookedWithTheFightTimeUnderTheBossKey() {
        Character ann = character(10, "Ann", Clear.AS_PLAYER, true);
        Character casse = character(900, "Casse", Clear.AS_COMPANION, true);
        Character away = character(11, "Bo", Clear.AS_PLAYER, false); // in the party, elsewhere
        character(500, "Ivo", Clear.AS_CITIZEN, true); // hit it, not in the party
        party(ann, casse, away);
        Monster stumpy = boss(STUMPY_MOB, NOW - 95_000L, Map.of(10, 6_000L, 900, 3_000L, 500, 1_000L));

        kills.killed(stumpy, map, Set.of(10, 900, 500), NOW);

        assertEquals(1, store.rows.size());
        Clear clear = store.rows.getFirst();
        assertEquals(STUMPY, clear.quest());
        assertEquals("field:" + STUMPY_MAP, clear.instance());
        assertEquals(95_000L, clear.durationMs());
        assertEquals(List.of(new Clear.Member(10, 'H', "Ann"), new Clear.Member(900, 'P', "Casse")), clear.members());
        assertEquals(95_000L, clears.playerBest(10, STUMPY));
        assertEquals(List.of(STUMPY), clears.questsWithRecords(), "the records page lists it with no special case");
        assertEquals(1, heard.size());
        assertEquals(10, heard.getFirst().topDamagerId());
        assertEquals(0.9d, heard.getFirst().share(Set.of(10, 900)), 1e-9);
    }

    @Test
    void aSoloKillerWithNoPartyIsTheOnlyMember() {
        character(10, "Ann", Clear.AS_PLAYER, true);
        kills.killed(boss(STUMPY_MOB, NOW - 30_000L, Map.of(10, 5_000L)), map, Set.of(10), NOW);

        assertEquals(List.of(new Clear.Member(10, 'H', "Ann")), store.rows.getFirst().members());
    }

    @Test
    void aTopDamagerWhoLeftTheMapGivesWayToTheNextOneThere() {
        character(10, "Ann", Clear.AS_PLAYER, false);
        character(20, "Cy", Clear.AS_PLAYER, true);
        kills.killed(boss(STUMPY_MOB, NOW - 30_000L, Map.of(10, 9_000L, 20, 1_000L)), map, Set.of(10, 20), NOW);

        assertEquals(List.of(new Clear.Member(20, 'H', "Cy")), store.rows.getFirst().members());
        assertEquals(20, heard.getFirst().topDamagerId());
    }

    @Test
    void aCitizenCrewsKillCountsForItsCrewLikeAnyClear() {
        Character nora = character(600, "Nora", Clear.AS_CITIZEN, true);
        Character tan = character(601, "Tan", Clear.AS_CITIZEN, true);
        party(nora, tan);
        crews.credit = 7;
        kills.killed(boss(STUMPY_MOB, NOW - 60_000L, Map.of(600, 4_000L, 601, 4_000L)), map, Set.of(600, 601), NOW);

        assertEquals(Set.of(600, 601), crews.asked.getFirst());
        assertEquals(7, store.rows.getFirst().crewId());
        assertEquals(60_000L, clears.crewBest(7, STUMPY), "the crew's field-boss time races its player's (N6.4)");
    }

    @Test
    void offTheSwitchBooksNothingButTheKillIsStillHeard() {
        recordsOn = false;
        character(10, "Ann", Clear.AS_PLAYER, true);
        kills.killed(boss(STUMPY_MOB, NOW - 30_000L, Map.of(10, 5_000L)), map, Set.of(10), NOW);

        assertTrue(store.rows.isEmpty());
        assertEquals(1, heard.size());
    }

    @Test
    void anUncataloguedMonsterOrAnEventMapBooksNothing() {
        character(10, "Ann", Clear.AS_PLAYER, true);
        kills.killed(boss(100100, NOW - 30_000L, Map.of(10, 5_000L)), map, Set.of(10), NOW);
        MapleMap event = mock(MapleMap.class);
        when(event.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        kills.killed(boss(STUMPY_MOB, NOW - 30_000L, Map.of(10, 5_000L)), event, Set.of(10), NOW);

        assertTrue(store.rows.isEmpty());
        assertTrue(heard.isEmpty());
    }

    @Test
    void theShippedCatalogueIsSound() {
        FieldBosses shipped = FieldBosses.load(java.nio.file.Path.of("scripts/config/population"));
        Set<String> keys = new java.util.HashSet<>();
        Set<Integer> mobs = new java.util.HashSet<>();
        assertTrue(shipped.all().size() >= 30, "the P13 and P16 bosses");
        for (FieldBosses.Boss boss : shipped.all()) {
            assertTrue(boss.key().startsWith("field.") && boss.key().length() <= 32, boss.key());
            assertTrue(keys.add(boss.key()), "twice: " + boss.key());
            assertTrue(!java.nio.file.Files.exists(java.nio.file.Path.of("scripts/event", boss.key() + ".js")), boss.key());
            assertTrue(boss.minLevel() <= boss.level() && boss.level() <= boss.maxLevel(), boss.key());
            assertTrue(Set.of("script", "life").contains(boss.respawn()), boss.key());
            assertTrue(!boss.maps().isEmpty() && !boss.mobs().isEmpty(), boss.key());
            for (int mob : boss.mobs()) {
                assertTrue(mobs.add(mob), "mob in two bosses: " + mob);
            }
        }
        assertEquals("field.stumpy", shipped.ofMob(STUMPY_MOB).key());
        assertTrue(QuestLabels.load(java.nio.file.Path.of("scripts/config/population")).field("field.mano"));
    }
}
