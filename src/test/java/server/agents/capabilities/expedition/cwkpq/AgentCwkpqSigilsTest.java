package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sigil table is the server's own data: each archetype's stage 2/3 and stage 4 sigils list exactly its skills
 * (Reactor.wz activeSkillID), and the names Jack reads out are String.wz's. A member turns its archetype's sigil only
 * once it holds one of them; a Crusader with no Shout or Charged Blow yet turns none.
 */
class AgentCwkpqSigilsTest {
    private static final Pattern SKILL_LIST = Pattern.compile("(?s)<imgdir name=\"activeSkillID\">(.*?)</imgdir>");
    private static final Pattern VALUE = Pattern.compile("value=\"(\\d+)\"");

    @Test
    void eachArchetypesSigilsListExactlyItsSkills() throws IOException {
        for (Archetype archetype : Archetype.values()) {
            Set<Integer> skills = new TreeSet<>(archetype.skillIds());
            assertEquals(skills, activeSkills(archetype.sigilReactorId()), archetype + " stage 2 and 3");
            assertEquals(skills, activeSkills(archetype.witSigilReactorId()), archetype + " stage 4");
        }
    }

    @Test
    void everySigilsHitAreaIsTheOneTheTableHolds() throws IOException {
        Pattern lt = Pattern.compile("name=\"lt\" x=\"(-?\\d+)\" y=\"(-?\\d+)\"");
        Pattern rb = Pattern.compile("name=\"rb\" x=\"(-?\\d+)\" y=\"(-?\\d+)\"");
        for (Archetype archetype : Archetype.values()) {
            for (int reactorId : new int[] {archetype.sigilReactorId(), archetype.witSigilReactorId()}) {
                String reactor = Files.readString(Path.of("wz/Reactor.wz/" + reactorId + ".img.xml"),
                        StandardCharsets.UTF_8);
                Matcher left = lt.matcher(reactor);
                Matcher right = rb.matcher(reactor);
                assertEquals(true, left.find() && right.find(), reactorId + "");
                assertEquals(List.of(AgentCwkpqSigils.AREA_LEFT, AgentCwkpqSigils.AREA_TOP, AgentCwkpqSigils.AREA_RIGHT,
                        AgentCwkpqSigils.AREA_BOTTOM), List.of(Integer.parseInt(left.group(1)),
                        Integer.parseInt(left.group(2)), Integer.parseInt(right.group(1)),
                        Integer.parseInt(right.group(2))), reactorId + "");
            }
        }
        assertEquals(new java.awt.Rectangle(-62, 67, 248, 141), AgentCwkpqSigils.area(new java.awt.Point(63, 86)));
    }

    @Test
    void jackNamesTheSkillsAsTheGameDoes() throws IOException {
        String strings = Files.readString(Path.of("wz/String.wz/Skill.img.xml"), StandardCharsets.UTF_8);
        for (Archetype archetype : Archetype.values()) {
            Set<String> names = new LinkedHashSet<>();
            for (int skillId : archetype.skillIds()) {
                Matcher name = Pattern.compile("(?s)<imgdir name=\"" + skillId
                        + "\">\\s*<string name=\"name\" value=\"([^\"]+)\"").matcher(strings);
                if (name.find()) {
                    names.add(name.group(1));
                }
            }
            List<String> spoken = new ArrayList<>(names);
            String joined = spoken.size() == 1 ? spoken.getFirst()
                    : String.join(", ", spoken.subList(0, spoken.size() - 1)) + " or " + spoken.getLast();
            assertEquals(joined, archetype.skillNames(), archetype.toString());
        }
    }

    @Test
    void theArchetypeIsTheJobNicheAndBeginnersAndGmsHaveNone() {
        assertSame(Archetype.WARRIOR, AgentCwkpqSigils.archetypeOf(112));
        assertSame(Archetype.MAGICIAN, AgentCwkpqSigils.archetypeOf(232));
        assertSame(Archetype.BOWMAN, AgentCwkpqSigils.archetypeOf(321));
        assertSame(Archetype.THIEF, AgentCwkpqSigils.archetypeOf(412));
        assertSame(Archetype.PIRATE, AgentCwkpqSigils.archetypeOf(522));
        assertNull(AgentCwkpqSigils.archetypeOf(0));
        assertNull(AgentCwkpqSigils.archetypeOf(900));
        assertNull(AgentCwkpqSigils.archetypeOf(1000), "a Cygnus Knight is no Explorer niche here");
    }

    @Test
    void aMemberTurnsItsSigilOnlyOnceItHoldsOneOfItsSkills() {
        Character crusader = member(Job.CRUSADER);
        assertNull(AgentCwkpqSigils.turns(crusader), "no Shout or Charged Blow yet (C0: about Lv100 under shaping)");
        when(crusader.getSkillLevel(1_111_008)).thenReturn(1);
        assertSame(Archetype.WARRIOR, AgentCwkpqSigils.turns(crusader));
        assertEquals(1_111_008, AgentCwkpqSigils.bestSkill(crusader, Archetype.WARRIOR));

        Character outlaw = member(Job.OUTLAW);
        when(outlaw.getSkillLevel(1_111_008)).thenReturn(1); // not its archetype's: it never counts
        assertNull(AgentCwkpqSigils.turns(outlaw));
    }

    @Test
    void coverageCountsEveryArchetypeAndNamesTheOnesNobodyCanTurn() {
        Character bishop = member(Job.BISHOP);
        when(bishop.getSkillLevel(2_321_003)).thenReturn(1);
        Character hermit = member(Job.HERMIT);
        when(hermit.getSkillLevel(4_111_005)).thenReturn(20);
        Character priest = member(Job.PRIEST);
        when(priest.getSkillLevel(2_001_005)).thenReturn(1);

        Map<Archetype, Integer> coverage = AgentCwkpqSigils.coverage(List.of(bishop, hermit, priest, member(Job.HERO)));
        assertEquals(Map.of(Archetype.WARRIOR, 0, Archetype.MAGICIAN, 2, Archetype.BOWMAN, 0, Archetype.THIEF, 1,
                Archetype.PIRATE, 0), coverage);
    }

    private static Character member(Job job) {
        Character member = mock(Character.class);
        when(member.getJob()).thenReturn(job);
        when(member.getSkillLevel(anyInt())).thenReturn(0);
        return member;
    }

    private static Set<Integer> activeSkills(int reactorId) throws IOException {
        String reactor = Files.readString(Path.of("wz/Reactor.wz/" + reactorId + ".img.xml"), StandardCharsets.UTF_8);
        Matcher list = SKILL_LIST.matcher(reactor);
        Set<Integer> skills = new TreeSet<>();
        while (list.find()) {
            Matcher value = VALUE.matcher(list.group(1));
            while (value.find()) {
                skills.add(Integer.parseInt(value.group(1)));
            }
        }
        return skills;
    }
}
