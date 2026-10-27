package server.living.quest;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.roster.CompanionExecutor;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A quest whose world flag opens its giver's next chapter closes in the chapter it was completed in: that chapter's
 * {@code questComplete} override is for the players who take the quest afterwards, not for the one who opened it
 * (docs/REGIONAL_RESIDENTS_PLAN.md section 10; wave 3's lyra-the-dead-channel and five others had the same shape).
 */
class QuestClosingLineTest {
    private static final int ME = 865;

    @TempDir
    Path dir;

    @Test
    void theClosingLineIsReadBeforeTheChapterTurns() throws IOException {
        Files.createDirectories(dir.resolve("personas"));
        Files.createDirectories(dir.resolve("quests"));
        Files.createDirectories(dir.resolve("arcs"));
        Files.writeString(dir.resolve("personas/t.yaml"), """
                - id: tess
                  name: Tess
                  look: { gender: female }
                  title: "Tester"
                  job: BEGINNER
                  level: { min: 10, max: 10 }
                  strength: ROOKIE
                  loadout: beginner-townsfolk
                  personality: relaxed-v1
                  home:
                    town: lith-harbor
                    routine:
                      - { from: 0, to: 24, spot: "venue:by-olaf", activity: LINGER }
                  recruitable: false
                  questLines: [tess-the-beat]
                  arc: tess-the-arc
                  dialogue:
                    questComplete.tess-the-beat: ["before"]
                """);
        Files.writeString(dir.resolve("quests/t.yaml"), """
                - id: tess-the-beat
                  persona: tess
                  title: "The Beat"
                  length: SHORT
                  steps:
                    - { id: talk, type: TALK }
                    - { id: dock, type: VISIT_MAP, mapIds: [104000000] }
                  rewards: { worldFlags: [test.opened] }
                """);
        Files.writeString(dir.resolve("arcs/t.yaml"), """
                - id: tess-the-arc
                  persona: tess
                  chapters:
                    - id: chapter-1
                      title: "Before"
                    - id: chapter-2
                      title: "After"
                      enterWhen: { flags: [test.opened] }
                      dialogueOverrides:
                        questComplete.tess-the-beat: ["after"]
                """);
        LivingContent content = ContentLoader.load(dir);
        Persona tess = content.persona("tess");

        LivingWorld world = mock(LivingWorld.class);
        LivingStore store = mock(LivingStore.class);
        ArcService arcs = mock(ArcService.class);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.arcs()).thenReturn(arcs);
        when(world.bonds()).thenReturn(mock(BondService.class));
        when(world.questLog()).thenReturn(mock(QuestLog.class));
        when(world.markers()).thenReturn(mock(QuestMarkers.class));
        when(world.executor()).thenReturn(mock(CompanionExecutor.class));
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(arcs.lines(tess, "questComplete.tess-the-beat")).thenReturn(List.of("before"));
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(ME);
        when(store.questStates(ME, QuestStatus.ACTIVE)).thenReturn(
                List.of(new QuestState(ME, "tess-the-beat", 1, 0, QuestStatus.ACTIVE, 1_000L, 0L, 0)));

        new QuestLedger(world).onMapEntered(chr, 104000000);

        verify(store).setWorldFlag("test.opened", ME);
        InOrder order = inOrder(arcs);
        order.verify(arcs).lines(tess, "questComplete.tess-the-beat");
        order.verify(arcs).evaluateGates();
    }
}
