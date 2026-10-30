package server.living.story;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.bond.Friendship;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.QuestLength;
import server.living.config.Content.Requirements;
import server.living.config.Content.Reward;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryChoice;
import server.living.config.Content.StoryOption;
import server.living.config.Content.StoryRival;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.resident.LivingTalk;
import server.living.roster.Commitments;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.awt.Point;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A personal arc as its owner meets it (F6.1, F6.6): the card offers the open chapter and nothing else, the chapter's
 * conversation runs to its choice and starts the quest from the last click, the companion says a chapter is open once
 * (at a calm moment, never again at a login) and the race shows on both rivals' cards only once both are the owner's.
 */
class StoryArcsTest {
    private static final byte SAY = 0;
    private static final byte MENU = 4;
    private static final int ANN = 10;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final BondService bonds = mock(BondService.class);
    private final Friendship friendship = mock(Friendship.class);
    private final QuestLedger quests = mock(QuestLedger.class);
    private final Character ann = mock(Character.class);
    private final Client client = mock(Client.class);
    private final MapleMap town = mock(MapleMap.class);
    private final Character fennaBot = mock(Character.class);
    private final Stagehand stagehand = mock(Stagehand.class);
    private final Map<String, Set<String>> flags = new HashMap<>();
    private final Map<String, QuestState> rows = new HashMap<>();
    private final Persona fenna = persona("fenna", "Fenna");
    private final Persona dorran = persona("dorran", "Dorran");
    private Story story;
    private StoryArcs arcs;

    private static Persona persona(String id, String name) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(persona.homeTown()).thenReturn("henesys");
        return persona;
    }

    private static Quest quest(String id, StepType... steps) {
        List<Step> list = java.util.Arrays.stream(steps).map(type -> new Step(type.name().toLowerCase(), type, List.of(),
                0, 1, List.of(), List.of(), null, null, null, 0, 0, 0, null, null, null, null,
                type == StepType.SCENE ? "blind" : null)).toList();
        return new Quest(id, "fenna", id, QuestLength.SHORT, false,
                new Requirements(1, 200, BondStage.NONE, List.of(), List.of(), List.of(), 0), list,
                new Reward(0, 0, List.of(), 0, List.of(), 0, List.of()), null, "test");
    }

    @BeforeEach
    void wire() {
        config.PERSONAL_ARCS_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.bonds()).thenReturn(bonds);
        when(world.friendship()).thenReturn(friendship);
        when(world.quests()).thenReturn(quests);
        when(world.commitments()).thenReturn(new Commitments());

        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getHp()).thenReturn(50);
        when(ann.getMap()).thenReturn(town);
        when(ann.getPosition()).thenReturn(new Point(0, 0));
        when(ann.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        when(town.isTown()).thenReturn(true);
        when(fennaBot.getMap()).thenReturn(town);
        when(fennaBot.getId()).thenReturn(901);

        story = new Story("fenna-long-road", "fenna", List.of(
                new StoryChapter("one", 3, "fenna-story-1", 31600, List.of("got a minute later?"),
                        List.of("the road was his.", "the cart went over."),
                        new StoryChoice("say something.", List.of(
                                new StoryOption("I'm sorry.", "story.fenna.sorry", List.of("don't be.", "anyway.")),
                                new StoryOption("So you keep it open.", "story.fenna.for-him", List.of("for him."))))),
                new StoryChapter("two", 6, "fenna-story-2", 31601, List.of("a part i left out."), List.of("i missed."), null),
                new StoryChapter("three", 9, "fenna-story-3", 31602, List.of("a place to see."), List.of("the blind."), null)),
                new StoryRival("dorran", 6, "two", "rivals-fenna-dorran", 31609, List.of("a bet needs settling."),
                        List.of("one beast. whoever brings down more of it wins.")),
                Map.of(), "test");
        when(content.stories()).thenReturn(Map.of(story.id(), story));
        when(content.persona("fenna")).thenReturn(fenna);
        when(content.persona("dorran")).thenReturn(dorran);
        when(content.quest("fenna-story-1")).thenReturn(quest("fenna-story-1", StepType.TALK));
        when(content.quest("fenna-story-2")).thenReturn(quest("fenna-story-2", StepType.TALK));
        when(content.quest("fenna-story-3")).thenReturn(quest("fenna-story-3", StepType.TALK, StepType.SCENE));
        when(content.quest("rivals-fenna-dorran")).thenReturn(quest("rivals-fenna-dorran", StepType.SCENE));
        when(quests.offerable(any(), any(), any())).thenReturn(true);
        when(store.questState(eq(ANN), anyString())).thenAnswer(inv -> rows.get(inv.<String>getArgument(1)));
        when(bonds.peek(eq(ANN), anyString())).thenAnswer(inv -> {
            String personaId = inv.getArgument(1);
            Bond bond = Bond.fresh(ANN, personaId, 0L);
            bond.flags().addAll(flags.getOrDefault(personaId, Set.of()));
            return bond;
        });
        when(bonds.setFlag(eq(ANN), anyString(), anyString())).thenAnswer(inv -> {
            flags.computeIfAbsent(inv.getArgument(1), id -> new HashSet<>()).add(inv.getArgument(2));
            return null;
        });

        own("fenna", 3);
        RosterEntry entry = new RosterEntry(ANN, "fenna", RosterState.ACTIVE, LivingStore.Assignment.TRAIN, 0, 0, 0, 0L,
                0L, 0L, 0L, 0L, 0L, 0, null);
        when(roster.entry(ANN, "fenna")).thenReturn(entry);
        when(roster.companion(entry)).thenReturn(fennaBot);
        when(roster.roster(ann)).thenReturn(List.of(entry));
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(roster.sayInParty(any(), anyString())).thenReturn(true);
        arcs = new StoryArcs(world, new StoryScenes(world, stagehand, () -> 0L));
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(ann);
    }

    private void own(String personaId, int level) {
        when(roster.ownerOf(personaId)).thenReturn(ANN);
        when(friendship.level(ANN, personaId)).thenReturn(level);
    }

    private void done(String chapterId) {
        flags.computeIfAbsent("fenna", id -> new HashSet<>()).add(StoryState.doneFlag(story, chapterId));
    }

    private List<String> labels(Persona persona) {
        return arcs.cardOptions(ann, persona).stream().map(LivingTalk.Option::label).toList();
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }

    private void answer(byte lastMsg, int action, Integer selection) {
        assertTrue(LivingTalk.respond(client, lastMsg, (byte) action, Packets.buildInPacket(out -> {
            if (selection != null) {
                out.writeInt(selection);
            }
        })));
    }

    @Test
    void theCardOffersTheOpenChapterOnlyToItsOwnerAtItsLevel() {
        assertEquals(List.of(StoryArcs.SOMETHING), labels(fenna));

        when(friendship.level(ANN, "fenna")).thenReturn(2);
        assertEquals(List.of(), labels(fenna), "Lv 2: chapter one is Lv 3");

        when(friendship.level(ANN, "fenna")).thenReturn(9);
        when(roster.ownerOf("fenna")).thenReturn(77);
        assertEquals(List.of(), labels(fenna), "someone else's companion");

        when(roster.ownerOf("fenna")).thenReturn(ANN);
        config.PERSONAL_ARCS_ENABLED = false;
        assertEquals(List.of(), labels(fenna), "switched off");
    }

    @Test
    void chaptersGoInOrderWhateverTheFriendship() {
        when(friendship.level(ANN, "fenna")).thenReturn(10);
        assertEquals("one", StoryState.current(story, arcs.facts(ANN, story)).id());
        done("one");
        assertEquals("two", StoryState.current(story, arcs.facts(ANN, story)).id());
        done("two");
        done("three");
        assertEquals(List.of(), labels(fenna), "the arc is told; the race needs Dorran");
    }

    @Test
    void theChapterConversationRunsToItsChoiceAndTheLastClickStartsTheQuest() {
        arcs.cardOptions(ann, fenna).get(0).action().run();
        assertEquals("the road was his.", lastTalk().text());
        assertTrue(flags.get("fenna").contains("story.fenna-long-road.one.told"), "told: no nudge for it later");

        answer(SAY, 1, null);
        assertEquals("the cart went over.", lastTalk().text());
        answer(SAY, 1, null);
        Talk choice = lastTalk();
        assertTrue(choice.labels().containsAll(List.of("I'm sorry.", "So you keep it open.")), choice.labels().toString());

        answer(MENU, 1, choice.selection("I'm sorry."));
        assertTrue(flags.get("fenna").contains("story.fenna.sorry"));
        assertEquals("don't be.", lastTalk().text());
        verify(quests, never()).begin(any(), any(), any());

        answer(SAY, 1, null);
        Talk last = lastTalk();
        assertEquals(StoryTalk.GO_ON, last.labels().get(0));
        Quest chapterOne = content.quest("fenna-story-1");
        answer(MENU, 1, last.selection(StoryTalk.GO_ON));
        verify(quests).begin(ann, fenna, chapterOne);
        verify(store).event(ANN, "fenna", "story.chapter", "fenna-story-1");
    }

    @Test
    void aChapterNotOfferableNowOpensNothing() {
        when(quests.offerable(any(), any(), any())).thenReturn(false);
        arcs.cardOptions(ann, fenna).get(0).action().run();
        verify(client, never()).sendPacket(any());
        assertFalse(flags.containsKey("fenna"));
    }

    @Test
    void anActiveChapterOffersTheSceneOnItsSceneStepAndOtherwiseItsTurnIn() {
        when(friendship.level(ANN, "fenna")).thenReturn(9);
        done("one");
        done("two");
        rows.put("fenna-story-3", new QuestState(ANN, "fenna-story-3", 0, 0, QuestStatus.ACTIVE, 1L, 0L, 0));
        assertEquals(List.of(StoryArcs.ABOUT_EARLIER), labels(fenna));

        rows.put("fenna-story-3", new QuestState(ANN, "fenna-story-3", 1, 0, QuestStatus.ACTIVE, 1L, 0L, 0));
        assertEquals(List.of(StoryArcs.READY), labels(fenna));

        when(roster.ownerOf("fenna")).thenReturn(0); // released mid-chapter: it waits
        assertEquals(List.of(), labels(fenna));
    }

    @Test
    void anOpenChapterIsSaidOnceInPartyChatAtACalmMomentThenNeverAgainAtALogin() {
        when(town.isTown()).thenReturn(false);
        when(town.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of(mock(server.maps.MapObject.class)));
        arcs.friendshipRose(ANN, "fenna");
        arcs.tick();
        verify(roster, never()).sayInParty(any(), anyString());
        assertTrue(arcs.any(), "still waiting for a calm moment");

        when(town.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of());
        arcs.tick();
        verify(roster).sayInParty(fennaBot, "got a minute later?");
        verify(ann).dropMessage(5, "Fenna wants a word. Double-click them.");
        assertTrue(flags.get("fenna").contains("story.fenna-long-road.one.told"));
        assertFalse(arcs.any());

        arcs.loggedIn(ann);
        arcs.tick();
        verify(roster, times(1)).sayInParty(any(), anyString());
        assertEquals(List.of(StoryArcs.SOMETHING), labels(fenna), "still on the card");
    }

    @Test
    void noNudgeWhileAWindowWaitsOrTheCompanionIsElsewhere() {
        when(fennaBot.getMap()).thenReturn(mock(MapleMap.class));
        arcs.friendshipRose(ANN, "fenna");
        arcs.tick();
        when(fennaBot.getMap()).thenReturn(town);
        LivingTalk.open(ann, 9040011).menu("a window", List.of(new LivingTalk.Option("x", () -> { }))).show();
        arcs.tick();
        verify(roster, never()).sayInParty(any(), anyString());

        LivingTalk.forget(ann);
        arcs.tick();
        verify(roster).sayInParty(fennaBot, "got a minute later?");
    }

    @Test
    void theRaceShowsOnBothRivalsCardsOnceBothAreTheOwnersAtLevelSixAfterChapterTwo() {
        own("fenna", 6);
        done("one");
        assertEquals(List.of(StoryArcs.SOMETHING), labels(fenna), "chapter two is open; no Dorran yet");

        own("dorran", 6);
        assertFalse(labels(fenna).contains("About you and Dorran..."), "chapter two not done");
        done("two");
        assertEquals(List.of("About you and Dorran..."), labels(fenna), "chapter three is Lv 9");
        assertEquals(List.of("About you and Fenna..."), labels(dorran));

        when(friendship.level(ANN, "dorran")).thenReturn(5);
        assertEquals(List.of(), labels(dorran), "Dorran's friendship is short of 6");

        when(friendship.level(ANN, "dorran")).thenReturn(6);
        flags.computeIfAbsent("dorran", id -> new HashSet<>()).add(StoryState.rivalryFlag("fenna", "dorran"));
        assertEquals(List.of(), labels(fenna), "raced already, from either side");
    }

    @Test
    void theRaceIsToldByTheRivalWhoseCardItWasAndStartedAsTheStoryCompanionsQuest() {
        own("fenna", 6);
        own("dorran", 6);
        done("one");
        done("two");
        arcs.cardOptions(ann, dorran).get(0).action().run();
        Talk talk = lastTalk();
        assertTrue(talk.text().startsWith("one beast. whoever brings down more of it wins."), talk.text());
        assertTrue(flags.get("fenna").contains("story.fenna-long-road.rival.told"));

        Quest race = content.quest("rivals-fenna-dorran");
        answer(MENU, 1, talk.selection(StoryTalk.LETS_GO));
        verify(quests).begin(ann, fenna, race);
    }

    /** Opens the card's only story option and clicks through to its last page's single option. */
    private void tellAndStart(String expectedLast) {
        List<LivingTalk.Option> options = arcs.cardOptions(ann, fenna);
        assertEquals(1, options.size(), options.toString());
        options.get(0).action().run();
        Talk talk = lastTalk();
        while (talk.msgType() == SAY) {
            answer(SAY, 1, null);
            talk = lastTalk();
        }
        if (!talk.labels().contains(expectedLast)) {
            answer(MENU, 1, talk.selection(talk.labels().get(0))); // the first reply of the choice
            talk = lastTalk();
            while (talk.msgType() == SAY) {
                answer(SAY, 1, null);
                talk = lastTalk();
            }
        }
        answer(MENU, 1, talk.selection(expectedLast));
    }

    @Test
    void anArcRunsFromLevelThreeThroughItsChaptersToItsCapstoneSceneWithItsOwner() {
        server.living.roster.CompanionExecutor executor = mock(server.living.roster.CompanionExecutor.class);
        when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        when(world.executor()).thenReturn(executor);
        when(fennaBot.getHp()).thenReturn(100);
        when(content.scene("blind")).thenReturn(new server.living.config.Content.Scene("blind", 101040001, 0, 0,
                List.of("fenna"), List.of(), 10, List.of(), Map.of(), "test"));
        org.mockito.Mockito.doAnswer(inv -> {
            Quest quest = inv.getArgument(2);
            int scene = quest.steps().get(quest.steps().size() - 1).type() == StepType.SCENE ? quest.steps().size() - 1 : 0;
            rows.put(quest.id(), new QuestState(ANN, quest.id(), scene, 0, QuestStatus.ACTIVE, 1L, 0L, 0));
            return null;
        }).when(quests).begin(any(), any(), any());

        tellAndStart(StoryTalk.GO_ON); // Lv 3: chapter one
        rows.put("fenna-story-1", new QuestState(ANN, "fenna-story-1", 0, 0, QuestStatus.COMPLETED, 1L, 2L, 0));
        assertEquals(List.of(), labels(fenna), "chapter two waits for Lv 6");

        when(friendship.level(ANN, "fenna")).thenReturn(6);
        tellAndStart(StoryTalk.GO_ON);
        rows.put("fenna-story-2", new QuestState(ANN, "fenna-story-2", 0, 0, QuestStatus.COMPLETED, 1L, 2L, 0));

        when(friendship.level(ANN, "fenna")).thenReturn(9);
        tellAndStart(StoryTalk.LETS_GO);
        Talk ready = lastTalk();
        assertEquals(YES_NO_TYPE, ready.msgType(), "the scene's yes/no, as the answer to that click");
        answer(YES_NO_TYPE, 1, null);

        verify(stagehand).open(eq(ann), eq(101040001), anyInt(), any());
        verify(store).event(ANN, "fenna", "story.chapter", "fenna-story-3");
    }

    private static final byte YES_NO_TYPE = 1;

    @Test
    void theGmReportShowsEachChapterStageAndTheRace() {
        List<String> lines = arcs.report(ANN);
        assertEquals("Personal arcs on, attended errands OFF.", lines.get(0));
        assertEquals("fenna-long-road (fenna, on their roster, friendship Lv 3): one Lv 3 READY two Lv 6 LOCKED"
                + " three Lv 9 LOCKED; race with dorran (Lv 0, not theirs) LOCKED", lines.get(1));
        assertEquals(2, lines.size(), "no scene, no attended errand");
    }

    @Test
    void switchingOffForgetsWhatWasWaiting() {
        arcs.friendshipRose(ANN, "fenna");
        assertTrue(arcs.any());
        config.PERSONAL_ARCS_ENABLED = false;
        arcs.tick();
        assertFalse(arcs.any());
        arcs.friendshipRose(ANN, "fenna");
        assertFalse(arcs.any());
        verify(roster, never()).sayInParty(any(), anyString());
        verify(roster, never()).onlineCharacter(anyInt());
    }

    private static double anyDouble() {
        return org.mockito.ArgumentMatchers.anyDouble();
    }
}
