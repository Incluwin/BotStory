package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.Skill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileRepository;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Orrin, Lv200 Paladin (2026-10-16): his Blast 20 book failed on 2026-10-14 among eighteen books, nobody knew, and he
 * met Pink Bean at Blast 10 of 30 with 108 SP waiting and his Blast 30 book unreadable in his bag. Once its reading is
 * done, an owned companion out with its owner now says which books failed, else which wait on an earlier one.
 */
class AgentBookSpeechTest {
    private static final int BLAST = 1221009;
    private static final int STANCE = 1221002;
    private static final int HOLY_CHARGE = 1221003;
    private static final int MAPLE_WARRIOR = 1221000;
    private static final int ACHILLES = 1220005;
    private static final int RUSH = 1221007;
    private static final int HEAVENS_HAMMER = 1221011;
    private static final Map<Integer, String> NAMES = Map.of(BLAST, "Blast", STANCE, "Power Stance",
            HOLY_CHARGE, "Holy Charge : Sword", MAPLE_WARRIOR, "Maple Warrior", ACHILLES, "Achilles", RUSH, "Rush");

    private final Character orrin = mock(Character.class);
    private final SkillGateway skills = mock(SkillGateway.class);
    /** Orrin's master levels on 2026-10-16. */
    private final Map<Integer, Integer> masters = new HashMap<>(Map.of(BLAST, 10, STANCE, 10, HOLY_CHARGE, 10,
            MAPLE_WARRIOR, 20, ACHILLES, 20, RUSH, 20));
    private final Set<Integer> waiting = new LinkedHashSet<>();
    private AgentRuntimeEntry entry;

    @BeforeEach
    void orrin() {
        when(orrin.getJob()).thenReturn(Job.PALADIN);
        when(orrin.getLevel()).thenReturn(200);
        when(orrin.getName()).thenReturn("Orrin");
        when(skills.getSkillName(anyInt())).thenAnswer(invocation -> NAMES.get(invocation.<Integer>getArgument(0)));
        when(skills.getSkill(anyInt())).thenAnswer(invocation -> {
            Skill skill = mock(Skill.class);
            when(skill.getId()).thenReturn(invocation.getArgument(0));
            return skill;
        });
        when(orrin.getMasterLevel(any(Skill.class))).thenAnswer(invocation ->
                masters.getOrDefault(invocation.<Skill>getArgument(0).getId(), 0));
        entry = new AgentRuntimeEntry(orrin, null, null);
        entry.spBuildProfileState().assign(AgentSpBuildProfileRepository.defaultRepository()
                .find("mapleroyals-optimal-2026-paladin").orElseThrow());
    }

    @AfterEach
    void uninstall() {
        AgentBookSpeech.install(null);
    }

    private String next() {
        return AgentBookSpeech.nextLine(entry.buildState(), orrin, skills, () -> waiting,
                skillId -> AgentSpBuildProfileService.plannedLevel(entry, orrin, skillId));
    }

    private static Set<String> lines(List<String> templates, String names) {
        return templates.stream().map(template -> String.format(template, names)).collect(Collectors.toSet());
    }

    @Test
    void itsBuildPlansBlastThirtyAndMapleWarriorTwenty() {
        assertEquals(30, AgentSpBuildProfileService.plannedLevel(entry, orrin, BLAST));
        assertEquals(20, AgentSpBuildProfileService.plannedLevel(entry, orrin, MAPLE_WARRIOR));
        assertEquals(0, AgentSpBuildProfileService.plannedLevel(entry, orrin, 1221004), "no Divine Charge");
    }

    @Test
    void aFailedBlastBookAsksForAnotherAndItsWaitingBlastBookIsNotNamedAgain() {
        entry.buildState().failedBookSkills().add(BLAST);
        waiting.add(BLAST); // his Blast 30 book now waits on a Blast 20 book

        String line = next();

        assertTrue(lines(AgentDialogueCatalog.bookFailedTemplates(false), "blast").contains(line), line);
        assertTrue(entry.buildState().failedBookSkills().isEmpty());
        assertNull(next(), "the waiting Blast book was the same news");
    }

    @Test
    void aFailedBookOfASkillItsBuildPlansNoHigherIsDroppedUnsaid() {
        entry.buildState().failedBookSkills().add(MAPLE_WARRIOR); // the plan stops at 20, its master level

        assertNull(next());
        assertTrue(entry.buildState().failedBookSkills().isEmpty());
    }

    @Test
    void booksThatWaitOnEarlierOnesAreNamedTogetherOnceASession() {
        waiting.add(BLAST);
        waiting.add(STANCE);

        String line = next();

        assertTrue(lines(AgentDialogueCatalog.bookWaitingTemplates(true), "blast and power stance").contains(line),
                line);
        assertNull(next(), "once a session");
    }

    @Test
    void aLineNamesAtMostThreeSkillsAndTheRestComeNext() {
        // the books Orrin's rolls failed on 2026-10-14, in order (his Maple Warrior 30 book too)
        entry.buildState().failedBookSkills().addAll(List.of(STANCE, BLAST, HOLY_CHARGE, ACHILLES, RUSH,
                MAPLE_WARRIOR));

        String first = next();
        String second = next();

        assertTrue(lines(AgentDialogueCatalog.bookFailedTemplates(true), "power stance, blast and holy charge")
                .contains(first), first);
        assertTrue(lines(AgentDialogueCatalog.bookFailedTemplates(true), "achilles and rush").contains(second),
                second);
        assertNull(next());
    }

    @Test
    void aSkillAtItsPlannedLevelOrNamelessIsNotMentioned() {
        masters.put(BLAST, 30);
        masters.put(HEAVENS_HAMMER, 10);
        waiting.add(BLAST);
        waiting.add(HEAVENS_HAMMER); // planned to 30, but no name to say it by

        assertNull(next());
    }

    @Test
    void noBookLineCarriesADigit() {
        List<String> templates = Stream.of(true, false)
                .flatMap(many -> Stream.concat(AgentDialogueCatalog.bookFailedTemplates(many).stream(),
                        AgentDialogueCatalog.bookWaitingTemplates(many).stream()))
                .toList();
        for (String template : templates) {
            assertFalse(template.matches(".*\\d.*"), template);
            assertEquals(1, template.split("%s", -1).length - 1, template);
        }
    }

    @Test
    void aSkillIsNamedAsAPlayerTypesIt() {
        assertEquals("holy charge", AgentBookSpeech.name("Holy Charge : Sword"));
        assertEquals("blast", AgentBookSpeech.name("Blast"));
        assertEquals("", AgentBookSpeech.name(null));
        assertEquals("blast", AgentBookSpeech.joined(List.of("blast")));
        assertEquals("blast and rush", AgentBookSpeech.joined(List.of("blast", "rush")));
        assertEquals("blast, rush and achilles", AgentBookSpeech.joined(List.of("blast", "rush", "achilles")));
    }

    @Test
    void onlyACompanionTheHostNamesSaysItInPartyChat() {
        AgentMasteryBooks.BookStats noBooks = (itemId, jobId) -> null;
        try (MockedStatic<AgentDialogueTransportRuntime> transport = mockStatic(AgentDialogueTransportRuntime.class)) {
            entry.buildState().failedBookSkills().add(BLAST);
            AgentBookSpeech.afterReading(entry, orrin, skills, noBooks);
            AgentBookSpeech.install(agent -> false);
            AgentBookSpeech.afterReading(entry, orrin, skills, noBooks);
            transport.verifyNoInteractions();
            assertEquals(Set.of(BLAST), entry.buildState().failedBookSkills(), "kept until it can say it");

            AgentBookSpeech.install(agent -> agent == orrin);
            AgentBookSpeech.afterReading(entry, orrin, skills, noBooks);

            transport.verify(() -> AgentDialogueTransportRuntime.sayNow(eq(orrin), eq(AgentReplyChannel.PARTY),
                    argThat(line -> line.contains("blast"))));
            assertTrue(entry.buildState().failedBookSkills().isEmpty());
        }
    }
}
