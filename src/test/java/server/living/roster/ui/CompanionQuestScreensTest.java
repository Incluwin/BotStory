package server.living.roster.ui;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.QuestLength;
import server.living.config.Content.Requirements;
import server.living.config.Content.Reward;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Story;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.resident.LivingTalk;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import testutil.NpcTalks;
import testutil.Packets;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A recruited Resident's own quests on its card (F6 design point 1): its owner can take what it offers and hand in what
 * it asked for, which chat never reached before (Fenna's friendship quests end in "DELIVER to fenna").
 */
class CompanionQuestScreensTest {
    private static final int ANN = 10;
    private static final byte MENU = 4;

    private final LivingWorld world = mock(LivingWorld.class);
    private final QuestLedger quests = mock(QuestLedger.class);
    private final LivingContent content = mock(LivingContent.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Persona fenna = mock(Persona.class);

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        when(world.quests()).thenReturn(quests);
        when(world.content()).thenReturn(content);
        when(fenna.id()).thenReturn("fenna");
        when(fenna.name()).thenReturn("Fenna");
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(owner);
    }

    @Test
    void anOwnedResidentOffersItsQuestOnItsCard() {
        Quest bow = quest("fenna-the-first-bow", "fenna", "The First Bow", step(StepType.KILL_MOBS, null));
        when(quests.available(owner, fenna)).thenReturn(List.of(bow));
        when(quests.active(owner)).thenReturn(List.of());

        assertTrue(CompanionQuestScreens.any(world, owner, fenna));
        CompanionQuestScreens.show(world, owner, fenna, () -> { });
        NpcTalks.Talk menu = lastTalk();
        assertEquals(List.of("#bThe First Bow#k", "Back"), menu.labels());

        click(menu.selection("#bThe First Bow#k"));
        verify(quests).offer(owner, fenna, bow);
    }

    @Test
    void aQuestInProgressIsHandedInFromTheCard() {
        Quest bow = quest("fenna-the-first-bow", "fenna", "The First Bow", step(StepType.DELIVER, null));
        when(content.quest(bow.id())).thenReturn(bow);
        when(quests.available(owner, fenna)).thenReturn(List.of());
        when(quests.active(owner)).thenReturn(List.of(active(bow)));
        when(quests.turnIn(owner, fenna)).thenReturn(true);

        assertTrue(CompanionQuestScreens.any(world, owner, fenna));
        CompanionQuestScreens.show(world, owner, fenna, () -> { });
        NpcTalks.Talk menu = lastTalk();
        assertEquals(List.of("#dThe First Bow (in progress)#k", "Back"), menu.labels());

        click(menu.selection("#dThe First Bow (in progress)#k"));
        verify(quests).turnIn(owner, fenna);
    }

    @Test
    void anotherResidentsStepToTalkToItCountsToo() {
        Quest story = quest("quill-a-story-worth-telling", "quill", "A Story Worth Telling", step(StepType.TALK_TO, "fenna"));
        when(content.quest(story.id())).thenReturn(story);
        when(quests.available(owner, fenna)).thenReturn(List.of());
        when(quests.active(owner)).thenReturn(List.of(active(story)));

        assertTrue(CompanionQuestScreens.any(world, owner, fenna));
    }

    @Test
    void nothingToTakeOrHandInShowsNoOption() {
        Quest other = quest("tavi-octopus-order", "tavi", "The Octopus Order", step(StepType.COLLECT_ITEM, null));
        when(content.quest(other.id())).thenReturn(other);
        when(quests.available(owner, fenna)).thenReturn(List.of());
        when(quests.active(owner)).thenReturn(List.of(active(other)));

        assertFalse(CompanionQuestScreens.any(world, owner, fenna));
    }

    @Test
    void aStoryChapterInProgressIsLeftToItsOwnCardOption() {
        Quest chapter = quest("fenna-story-3", "fenna", "The Long Road: The Old Blind", step(StepType.SCENE, null));
        when(content.quest(chapter.id())).thenReturn(chapter);
        when(content.storyOfQuest(chapter.id())).thenReturn(mock(Story.class)); // F6: the story offers and ends it
        when(quests.available(owner, fenna)).thenReturn(List.of());
        when(quests.active(owner)).thenReturn(List.of(active(chapter)));

        assertFalse(CompanionQuestScreens.any(world, owner, fenna));
    }

    @Test
    void aHireHasNoQuests() {
        Persona hire = mock(Persona.class);
        when(hire.id()).thenReturn(LivingContent.HIRE_PREFIX + "4242");

        assertFalse(CompanionQuestScreens.any(world, owner, hire));
    }

    private static Quest quest(String id, String persona, String title, Step step) {
        return new Quest(id, persona, title, QuestLength.SHORT, false,
                new Requirements(1, 200, BondStage.NONE, List.of(), List.of(), List.of()), List.of(step),
                new Reward(0, 0, List.of(), 0, List.of(), 0, List.of()), null, "test");
    }

    private static Step step(StepType type, String persona) {
        return new Step("s", type, List.of(), type == StepType.COLLECT_ITEM ? 4000001 : 0, 1,
                type == StepType.KILL_MOBS ? List.of(100100) : List.of(), List.of(),
                type == StepType.DELIVER ? "fenna" : null, persona, null, 0, 0, 0, null, null, null, null);
    }

    private static QuestState active(Quest quest) {
        return new QuestState(ANN, quest.id(), 0, 0, QuestStatus.ACTIVE, 1L, 0L, 0);
    }

    private void click(int selection) {
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private NpcTalks.Talk lastTalk() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(sent.capture());
        List<NpcTalks.Talk> talks = NpcTalks.talks(sent.getAllValues());
        return talks.get(talks.size() - 1);
    }
}
