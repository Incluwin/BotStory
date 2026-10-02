package server.living.resident;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.roster.RosterService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A named resident answers content questions from server knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 6.4 and its review)
 * before its quest and contract patterns can read "job" in "job advancement" as a request for work or "join" in
 * "join kpq" as a hire, in its own register, after a think-and-type delay; and the line it hears keeps its
 * punctuation but loses the resident's name.
 */
class ResidentContentHookTest {
    private static final int HENESYS = 100_000_000;
    private static final long THINK_FLOOR_MS = 600;

    @Test
    void aNamedResidentAnswersContentQuestionsInsteadOfOfferingWork() throws Exception {
        LivingWorld world = mock(LivingWorld.class);
        LivingContent content = mock(LivingContent.class);
        ResidentRuntime residents = mock(ResidentRuntime.class);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(world.residents()).thenReturn(residents);
        Persona mira = persona();
        when(content.personaByName("Mira")).thenReturn(mira);
        when(content.questsOf("mira")).thenReturn(List.of());
        Character speaker = mock(Character.class);
        when(speaker.getId()).thenReturn(1);
        when(speaker.getName()).thenReturn("Visitor");
        when(speaker.getLevel()).thenReturn(12);
        when(speaker.getJob()).thenReturn(Job.BOWMAN);
        when(speaker.getMapId()).thenReturn(HENESYS);
        TimerManager timer = mock(TimerManager.class);
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed();
             MockedStatic<TimerManager> timers = mockStatic(TimerManager.class)) {
            timers.when(TimerManager::getInstance).thenReturn(timer);
            ResidentDialogue dialogue = new ResidentDialogue(world);

            for (String line : List.of("Mira, wheres the job advancement npc?", "how do i join kpq Mira", "Mira where do i join kpq?")) {
                assertTrue(dialogue.onWhisper(speaker, "Mira", line), line);
            }

            ArgumentCaptor<Runnable> sends = ArgumentCaptor.forClass(Runnable.class);
            ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);
            verify(timer, atLeastOnce()).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), sends.capture(), delays.capture());
            assertTrue(delays.getAllValues().stream().allMatch(delay -> delay >= THINK_FLOOR_MS), delays.getAllValues().toString());
            sends.getAllValues().forEach(Runnable::run);
            ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
            verify(residents, atLeastOnce()).reply(eq(mira), eq(speaker), said.capture());
            String all = String.join(" | ", said.getAllValues());
            assertTrue(all.toLowerCase(Locale.ROOT).contains("athena"), all);
            assertTrue(all.toLowerCase(Locale.ROOT).contains("kerning"), "kpq is joined in kerning: " + all);
            assertTrue(said.getAllValues().stream().allMatch(text -> java.lang.Character.isUpperCase(text.charAt(0))), all);
            verify(world, never()).quests();
            verify(world, never()).contracts();
        }
        verify(timer, never()).schedule(eq(TimerManager.SchedulerLane.CORE), org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void withKnowledgeOffAManyWordTopicIsNotAskedAbout() throws Exception {
        LivingWorld world = mock(LivingWorld.class);
        LivingContent content = mock(LivingContent.class);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(content.personaByName("mia wolfe")).thenReturn(persona());
        ResidentDialogue dialogue = new ResidentDialogue(world);
        // nothing loaded: "what about tomorrow then" keeps its old way to the judged intent, a persona's full name does not
        assertFalse(dialogue.longTopics("tomorrow then"));
        assertTrue(dialogue.longTopics("mia wolfe"));
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertTrue(dialogue.longTopics("steely throwing knives"), "knowledge on for residents: anything can be asked about");
        }
    }

    @Test
    void theHeardLineKeepsPunctuationButNotTheName() {
        assertEquals("where is the potion shop?", ResidentDialogue.withoutName("Mira, where is the potion shop?", "Mira"));
        assertEquals("where is the potion shop?", ResidentDialogue.withoutName("where is the potion shop? mira", "Mira"));
        assertEquals("is miranda around?", ResidentDialogue.withoutName("is miranda around?", "Mira"), "only the whole name");
    }

    private static Persona persona() {
        return new Persona("mira", "Mira", "", "BOWMAN", new Content.LevelBand(30, 40), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
    }
}
