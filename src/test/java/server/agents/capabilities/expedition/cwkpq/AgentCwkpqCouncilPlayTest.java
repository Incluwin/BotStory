package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B7: only the human leader can talk to the Battle Statue, so with the hall empty a companion calls him,
 * once per step; with monsters up a magician takes the master its element hurts most and everyone else fights the
 * boss track's priority fight under the cwkpq boss rules.
 */
class AgentCwkpqCouncilPlayTest {
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final AgentBossFight fight = mock(AgentBossFight.class);
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<String> party = new ArrayList<>();
    private final AgentCwkpqRun run = new AgentCwkpqRun(Map.of(), () -> fight);
    private AgentCwkpqPorts ports;

    @BeforeEach
    void theHall() {
        ports = new AgentCwkpqPorts() {
            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entries.get(characterId);
            }

            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public PartyQuestGateway partyQuest() {
                return mock(PartyQuestGateway.class);
            }

            @Override
            public AgentCwkpqSigilCast.Casting casting() {
                return mock(AgentCwkpqSigilCast.Casting.class);
            }

            @Override
            public void sayInParty(Character member, String line) {
                party.add(member.getName() + ": " + line);
            }

            @Override
            public boolean dropOne(Character member, int itemId) {
                return false;
            }

            @Override
            public boolean pickUp(Character member, int itemId) {
                return false;
            }

            @Override
            public server.maps.MapItem nearestLoot(Character member, int seekPx) {
                return null;
            }
        };
    }

    @Test
    void anEmptyHallCallsTheLeaderToTheStatueOncePerStep() {
        Character orrin = member(10, "Orrin", Job.HERO);
        when(map.countMonsters()).thenReturn(0);

        AgentCwkpqCouncilPlay.council(ports, List.of(orrin), event, run, 1_000L);
        AgentCwkpqCouncilPlay.council(ports, List.of(orrin), event, run, 2_000L);
        when(event.getIntProperty("glpq6")).thenReturn(1);
        AgentCwkpqCouncilPlay.council(ports, List.of(orrin), event, run, 3_000L);
        when(event.getIntProperty("glpq6")).thenReturn(3);
        AgentCwkpqCouncilPlay.council(ports, List.of(orrin), event, run, 4_000L);

        assertEquals(List.of("Orrin: " + AgentCwkpqCouncilPlay.READY_LINE, "Orrin: " + AgentCwkpqCouncilPlay.CLEAR_LINE),
                party);
        verify(fight, never()).tick(any(), any(), anyLong());
        verify(fight, never()).tick(any(), any(), any(), anyLong());
    }

    @Test
    void aFireMageTakesRellikAndTheRestFightThePriorityFight() {
        Character magna = member(10, "Magna", Job.FP_ARCHMAGE);
        Character orrin = member(11, "Orrin", Job.HERO);
        when(map.countMonsters()).thenReturn(4);
        when(event.getIntProperty("glpq6")).thenReturn(2);
        when(gateway.liveMonsterCounts(any())).thenReturn(Map.of(9_400_590, 1, 9_400_591, 1, 9_400_592, 1,
                9_400_593, 1));

        AgentCwkpqCouncilPlay.council(ports, List.of(magna, orrin), event, run, 1_000L);

        verify(gateway).grind(entries.get(10), Set.of(9_400_592), Set.of(9_400_590, 9_400_591, 9_400_593));
        verify(fight).tick(List.of(orrin), List.of(magna, orrin), event, 1_000L); // its status line names Magna too
    }

    @Test
    void aMageWithNoneOfItsMastersUpFightsWithTheRest() {
        Character magna = member(10, "Magna", Job.IL_ARCHMAGE);
        when(map.countMonsters()).thenReturn(30);
        when(event.getIntProperty("glpq6")).thenReturn(1);
        when(gateway.liveMonsterCounts(any())).thenReturn(Map.of(9_400_594, 10, 9_400_582, 20)); // the guards

        AgentCwkpqCouncilPlay.council(ports, List.of(magna), event, run, 1_000L);

        verify(fight).tick(List.of(magna), List.of(magna), event, 1_000L);
    }

    @Test
    void withOnlyMagesOnTheirMastersTheFightStillReportsThem() {
        Character magna = member(10, "Magna", Job.FP_ARCHMAGE);
        Character ingrid = member(11, "Ingrid", Job.IL_ARCHMAGE);
        when(map.countMonsters()).thenReturn(4);
        when(event.getIntProperty("glpq6")).thenReturn(2);
        when(gateway.liveMonsterCounts(any())).thenReturn(Map.of(9_400_592, 1, 9_400_593, 1));

        AgentCwkpqCouncilPlay.council(ports, List.of(magna, ingrid), event, run, 1_000L);

        verify(fight).tick(List.of(), List.of(magna, ingrid), event, 1_000L);
    }

    @Test
    void eachMageLineTakesTheMasterItsElementHurts() throws IOException {
        assertEquals(List.of(9_400_592, 9_400_597), AgentCwkpqCouncilPlay.magePreference(Job.FP_WIZARD.getId()));
        assertEquals(List.of(9_400_593, 9_400_596), AgentCwkpqCouncilPlay.magePreference(Job.IL_MAGE.getId()));
        assertEquals(List.of(9_400_591, 9_400_596, 9_400_597),
                AgentCwkpqCouncilPlay.magePreference(Job.BISHOP.getId()));
        assertEquals(List.of(), AgentCwkpqCouncilPlay.magePreference(Job.HERO.getId()));
        assertEquals("F3", element(9_400_592, 'F'), "Rellik is fire-weak");
        assertEquals("I3", element(9_400_593, 'I'), "Hsalf is ice-weak");
        assertEquals("", element(9_400_591, 'H'), "Red Nirg is the only master not holy-immune");
        assertEquals("H1", element(9_400_590, 'H'));
    }

    @Test
    void theBossRulesPutRedNirgLastAndNeverTargetTheInvincibleOnes() {
        AgentBossRules.Preferences masters = AgentBossRules.preferences(AgentBossRules.of("cwkpq"),
                List.of(9_400_591, 9_400_592), false);
        assertEquals(Set.of(9_400_592), masters.preferred(), "Rellik before Red Nirg");
        AgentBossRules.Preferences guards = AgentBossRules.preferences(AgentBossRules.of("cwkpq"),
                List.of(9_400_594, 9_400_598, 9_400_595), false);
        assertEquals(Set.of(9_400_594), guards.preferred(), "the invincible summon and stirge are never targets");
    }

    private Character member(int id, String name, Job job) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getJob()).thenReturn(job);
        when(member.getMap()).thenReturn(map);
        entries.put(id, mock(AgentRuntimeEntry.class));
        run.adopt(id, entries.size() - 1);
        return member;
    }

    /** The mob's elemAttr entry for {@code element} ("F3"), or "" when it has none. */
    private static String element(int mobId, char element) throws IOException {
        String mob = Files.readString(Path.of("wz/Mob.wz/" + mobId + ".img.xml"), StandardCharsets.UTF_8);
        Matcher attr = Pattern.compile("name=\"elemAttr\" value=\"([A-Z0-9]*)\"").matcher(mob);
        if (!attr.find()) {
            return "";
        }
        Matcher one = Pattern.compile(element + "\\d").matcher(attr.group(1));
        return one.find() ? one.group() : "";
    }
}
