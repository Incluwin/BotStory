package server.living.story;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterListener;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.QuestLength;
import server.living.config.Content.Requirements;
import server.living.config.Content.Reward;
import server.living.config.Content.Scene;
import server.living.config.Content.SceneBeat;
import server.living.config.Content.SceneFoe;
import server.living.config.Content.SceneSpawn;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryRival;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.Commitments;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A SCENE step played out (F6.2) and the race (F6.6): who goes in and in what order, the beats and their waits, the
 * finish (unregister before the warp out, the copy let go, the step done only once no window is open) and every early
 * end, which leaves the step on SCENE. No channel: the stage is a fake {@link Stagehand}.
 */
class StoryScenesTest {
    private static final int ANN = 10;
    private static final int FENNA_ID = 901;
    private static final int DORRAN_ID = 902;
    private static final int EXIT_MAP = 104000000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final BondService bonds = mock(BondService.class);
    private final QuestLedger quests = mock(QuestLedger.class);
    private final Commitments commitments = new Commitments();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final FakeHands hands = new FakeHands();
    private final MapleMap field = mock(MapleMap.class);
    private final MapleMap exitMap = mock(MapleMap.class);
    private final Character ann = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Map<Integer, AtomicReference<MapleMap>> maps = new HashMap<>();
    private final Character fenna = bot(FENNA_ID, "Fenna", 30);
    private final Character dorran = bot(DORRAN_ID, "Dorran", 36);
    private StoryScenes scenes;

    /** The stage, recording what was done to it in order. */
    final class FakeHands implements Stagehand {
        final List<String> log = new ArrayList<>();
        final MapleMap stage = mock(MapleMap.class);
        final List<MonsterListener> listeners = new ArrayList<>();
        final List<Monster> spawned = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        boolean ended;
        SceneInstance.Exits exits;
        long damageFenna;
        long damageDorran;

        @Override
        public Stage open(Character owner, int mapId, int exitMapId, SceneInstance.Exits sceneExits) {
            exits = sceneExits;
            log.add("open " + mapId + " exit " + exitMapId);
            return new Stage() {
                @Override
                public MapleMap map() {
                    return stage;
                }

                @Override
                public void register(Character chr) {
                    log.add("register " + chr.getId());
                }

                @Override
                public void unregister(Character chr) {
                    log.add("unregister " + chr.getId());
                }

                @Override
                public void end() {
                    log.add("end");
                    ended = true;
                }

                @Override
                public boolean ended() {
                    return ended;
                }
            };
        }

        @Override
        public Monster spawn(Stage on, int mobId, Point at, Function<Monster, MonsterListener> listener) {
            Monster mob = mock(Monster.class);
            when(mob.getId()).thenReturn(mobId);
            when(mob.getMaxHp()).thenReturn(10_000L);
            when(mob.damageBy(FENNA_ID)).thenAnswer(inv -> damageFenna);
            when(mob.damageBy(DORRAN_ID)).thenAnswer(inv -> damageDorran);
            listeners.add(listener.apply(mob));
            spawned.add(mob);
            log.add("spawn " + mobId);
            return mob;
        }

        @Override
        public void say(Character speaker, String line) {
            said.add(speaker.getName() + ": " + line);
        }

        @Override
        public void warp(Character chr, MapleMap to, Point at) {
            log.add("warp " + chr.getId() + (to == stage ? " in" : " out"));
            maps.get(chr.getId()).set(to);
        }

        @Override
        public MapleMap channelMap(Character owner, int mapId) {
            return exitMap;
        }
    }

    private Character bot(int id, String name, int level) {
        Character bot = mock(Character.class);
        AtomicReference<MapleMap> map = new AtomicReference<>(field);
        maps.put(id, map);
        when(bot.getId()).thenReturn(id);
        when(bot.getName()).thenReturn(name);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getHp()).thenReturn(100);
        when(bot.getMap()).thenAnswer(inv -> map.get());
        when(bot.getPosition()).thenReturn(new Point(120, 200));
        return bot;
    }

    @BeforeEach
    void wire() {
        config.PERSONAL_ARCS_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.bonds()).thenReturn(bonds);
        when(world.quests()).thenReturn(quests);
        when(world.commitments()).thenReturn(commitments);
        when(world.residents()).thenReturn(mock(ResidentRuntime.class));
        CompanionExecutor executor = mock(CompanionExecutor.class);
        when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return CompletableFuture.completedFuture(null);
        });
        when(world.executor()).thenReturn(executor);

        AtomicReference<MapleMap> annMap = new AtomicReference<>(field);
        maps.put(ANN, annMap);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getHp()).thenReturn(50);
        when(ann.getMap()).thenAnswer(inv -> annMap.get());
        when(ann.getMapId()).thenReturn(EXIT_MAP);
        when(ann.getPosition()).thenReturn(new Point(100, 200));
        when(ann.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        companion("fenna", fenna);
        companion("dorran", dorran);
        when(roster.sayInParty(any(), anyString())).thenReturn(true);
        scenes = new StoryScenes(world, hands, clock::get);
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(ann);
    }

    private void companion(String personaId, Character bot) {
        RosterEntry entry = new RosterEntry(ANN, personaId, RosterState.ACTIVE, LivingStore.Assignment.TRAIN, 0, 0, 0, 0L,
                0L, 0L, 0L, 0L, 0L, 0, null);
        when(roster.entry(ANN, personaId)).thenReturn(entry);
        when(roster.companion(entry)).thenReturn(bot);
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(personaId);
        when(content.persona(personaId)).thenReturn(persona);
    }

    private static SceneBeat line(String by, String text) {
        return new SceneBeat(by, text, null, null, 0, null, 0, List.of(), false);
    }

    private static SceneBeat optional(String by, String text) {
        return new SceneBeat(by, text, by, null, 0, null, 0, List.of(), false);
    }

    /** Fenna's capstone quest on its SCENE step, playing {@code beats} with {@code cast}. */
    private Story capstone(List<String> cast, SceneBeat... beats) {
        return staged("fenna-story-3", new Scene("blind", 101040001, 0, 0, cast, List.of(beats), 10, List.of(), Map.of(),
                "test"));
    }

    private Story staged(String questId, Scene scene) {
        Step talk = new Step("talk", StepType.TALK, List.of(), 0, 1, List.of(), List.of(), null, null, null, 0, 0, 0,
                null, null, null, null);
        Step stage = new Step("scene", StepType.SCENE, List.of(), 0, 1, List.of(), List.of(), null, null, null, 0, 0, 0,
                null, null, null, null, scene.id());
        Quest quest = new Quest(questId, "fenna", questId, QuestLength.SHORT, false,
                new Requirements(1, 200, BondStage.NONE, List.of(), List.of(), List.of(), 9), List.of(talk, stage),
                new Reward(0, 0, List.of(), 0, List.of(), 0, List.of()), null, "test");
        when(content.quest(questId)).thenReturn(quest);
        when(content.scene(scene.id())).thenReturn(scene);
        when(store.questState(ANN, questId)).thenReturn(new QuestState(ANN, questId, 1, 0, QuestStatus.ACTIVE, 1L, 0L, 0));
        Story story = new Story("fenna-long-road", "fenna",
                List.of(new StoryChapter("three", 9, "fenna-story-3", 31602, List.of("n"), List.of(), null)),
                new StoryRival("dorran", 6, null, "rivals-fenna-dorran", 31609, List.of("n"), List.of()),
                Map.of("scene.abort", List.of("another day, then."), "rival.call", List.of("go get Dorran.")), "test");
        when(content.story(story.id())).thenReturn(story);
        return story;
    }

    private void tickAfter(long ms) {
        clock.addAndGet(ms);
        scenes.tick();
    }

    @Test
    void theOwnerAndTheCastPresentAreRegisteredThenWarpedIn() {
        Story story = capstone(List.of("fenna", "dorran"), line("fenna", "this is it."));
        when(dorran.getMap()).thenReturn(mock(MapleMap.class)); // Dorran is elsewhere: not cast

        assertNull(scenes.start(ANN, story, "fenna-story-3"));

        assertEquals(List.of("open 101040001 exit " + EXIT_MAP, "register 10", "register 901", "warp 10 in", "warp 901 in"),
                hands.log);
        assertTrue(commitments.isCommitted(FENNA_ID));
        assertFalse(commitments.isCommitted(DORRAN_ID));
        assertTrue(scenes.running(ANN));
        verify(store).event(eq(ANN), eq("fenna"), eq("story.scene.start"), contains("blind"));
    }

    @Test
    void linesPlayInOrderAMonsterBeatWaitsForEveryKillAndTheEndIsCleanThenTheStepIsDone() {
        Story story = capstone(List.of("fenna", "dorran"), line("fenna", "this is it."),
                new SceneBeat(null, null, null, null, 0, null, 0, List.of(new SceneSpawn(2230102, 2, 0, 0)), false),
                line("fenna", "{owner}. i didn't miss."), optional("dorran", "not bad, hunter."));
        when(dorran.getMap()).thenReturn(mock(MapleMap.class));
        scenes.start(ANN, story, "fenna-story-3");

        tickAfter(1000);
        assertEquals(List.of(), hands.said, "settling in first");
        tickAfter(StoryScenes.SETTLE_MS);
        assertEquals(List.of("Fenna: this is it."), hands.said);
        tickAfter(StoryScenes.LINE_MAX_MS);
        assertEquals(2, hands.spawned.size());
        tickAfter(StoryScenes.LINE_MAX_MS);
        assertEquals(1, hands.said.size(), "waiting for both boars");
        hands.listeners.get(0).monsterKilled(0);
        tickAfter(1000);
        assertEquals(1, hands.said.size(), "one boar still up");
        hands.listeners.get(1).monsterKilled(0);
        tickAfter(1000);
        assertEquals("Fenna: Ann. i didn't miss.", hands.said.get(1));
        tickAfter(StoryScenes.LINE_MAX_MS);
        assertEquals(2, hands.said.size(), "Dorran did not come: his line is skipped");
        tickAfter(StoryScenes.LINE_MAX_MS);

        int out = hands.log.indexOf("warp 10 out");
        assertTrue(hands.log.indexOf("unregister 10") >= 0 && hands.log.indexOf("unregister 10") < out,
                "unregistered before the warp out: " + hands.log);
        assertTrue(hands.log.indexOf("unregister 901") < hands.log.indexOf("warp 901 out"));
        assertTrue(hands.ended);
        assertFalse(commitments.isCommitted(FENNA_ID));
        assertFalse(scenes.running(ANN));
        verify(quests, never()).sceneDone(any(), anyString());

        tickAfter(StoryScenes.CLOSE_DELAY_MS);
        verify(quests).sceneDone(ann, "fenna-story-3");
        assertFalse(scenes.any());
    }

    @Test
    void theClosingWindowNeverOpensOverAnother() {
        Story story = capstone(List.of("fenna"), line("fenna", "this is it."));
        scenes.start(ANN, story, "fenna-story-3");
        tickAfter(StoryScenes.SETTLE_MS);
        tickAfter(StoryScenes.LINE_MAX_MS); // the last beat played: out
        LivingTalk.open(ann, 9040011).menu("a window on screen", List.of(new LivingTalk.Option("x", () -> { }))).show();

        tickAfter(StoryScenes.CLOSE_DELAY_MS);
        tickAfter(5_000);
        verify(quests, never()).sceneDone(any(), anyString());

        LivingTalk.forget(ann); // closed
        tickAfter(1_000);
        verify(quests).sceneDone(ann, "fenna-story-3");
    }

    @Test
    void theOwnerLeavingEndsItEarlyAndTheStepStaysForAnotherTry() {
        Story story = capstone(List.of("fenna"), line("fenna", "a"), line("fenna", "b"));
        scenes.start(ANN, story, "fenna-story-3");
        tickAfter(StoryScenes.SETTLE_MS);
        maps.get(ANN).set(exitMap); // a return scroll
        hands.exits.left(ann);

        tickAfter(1000);
        assertTrue(hands.ended);
        assertFalse(hands.log.contains("warp 10 out"), "already off the stage");
        assertTrue(hands.log.contains("warp 901 out"));
        assertFalse(commitments.isCommitted(FENNA_ID));
        tickAfter(StoryScenes.CLOSE_DELAY_MS);
        verify(quests, never()).sceneDone(any(), anyString());
        verify(roster).sayInParty(fenna, "another day, then.");
        verify(store).event(eq(ANN), eq("fenna"), eq("story.scene.abort"), contains("the owner left"));
    }

    @Test
    void aDisconnectTheDeadlineALostCompanionAndTheSwitchEachEndIt() {
        Story story = capstone(List.of("fenna"), line("fenna", "a"), new SceneBeat(null, null, null, null, 0, null, 600,
                List.of(), false));

        scenes.start(ANN, story, "fenna-story-3");
        hands.exits.disconnected(ann);
        tickAfter(1000);
        assertFalse(scenes.running(ANN), "disconnected");

        scenes.start(ANN, story, "fenna-story-3");
        tickAfter(10 * 60_000L + 1);
        assertFalse(scenes.running(ANN), "past maxMinutes");

        maps.get(FENNA_ID).set(field);
        maps.get(ANN).set(field);
        scenes.start(ANN, story, "fenna-story-3");
        maps.get(FENNA_ID).set(mock(MapleMap.class)); // died and revived in town
        tickAfter(StoryScenes.LOST_MS);
        assertFalse(scenes.running(ANN), "the companion is gone");

        maps.get(FENNA_ID).set(field);
        maps.get(ANN).set(field);
        scenes.start(ANN, story, "fenna-story-3");
        config.PERSONAL_ARCS_ENABLED = false;
        tickAfter(1000);
        assertFalse(scenes.running(ANN), "switched off");
        verify(quests, never()).sceneDone(any(), anyString());
    }

    @Test
    void itWillNotStartWithoutTheCompanionOrForARaceWithoutTheRival() {
        Story story = capstone(List.of("fenna"), line("fenna", "a"));
        when(fenna.getMap()).thenReturn(mock(MapleMap.class));
        assertEquals("stay close. i'll show you when we're together.", scenes.start(ANN, story, "fenna-story-3"));

        when(fenna.getMap()).thenReturn(field);
        Story race = race(new SceneFoe(200, 3220000));
        when(dorran.getMap()).thenReturn(mock(MapleMap.class));
        assertEquals("go get Dorran.", scenes.start(ANN, race, "rivals-fenna-dorran"));
        assertFalse(scenes.running(ANN));
    }

    private Story race(SceneFoe... foes) {
        Map<String, List<SceneBeat>> outcome = Map.of(
                "won.fenna", List.of(line("fenna", "{mine} to {theirs}. pay up.")),
                "won.dorran", List.of(line("dorran", "{mine} to {theirs}.")),
                "even", List.of(line("fenna", "{mine} and {theirs}? a tie.")),
                "forfeit.fenna", List.of(line("fenna", "he walked off.")));
        return staged("rivals-fenna-dorran", new Scene("bet", 101040003, 0, 0, List.of("fenna", "dorran"),
                List.of(new SceneBeat(null, null, null, null, 0, null, 0, List.of(), true)), 15, List.of(foes), outcome,
                "test"));
    }

    private void raceTo(long fennaDamage, long dorranDamage) {
        hands.damageFenna = fennaDamage;
        hands.damageDorran = dorranDamage;
        scenes.start(ANN, race(new SceneFoe(32, 3220000), new SceneFoe(200, 5220002)), "rivals-fenna-dorran");
        tickAfter(StoryScenes.SETTLE_MS);
    }

    @Test
    void theRaceFoeFitsTheRacersAndTheLargerShareWinsWithBothBondsFlagged() {
        raceTo(6_000, 3_000);
        assertEquals(List.of(5220002), hands.spawned.stream().map(Monster::getId).toList(), "average Lv 33: the second band");
        hands.listeners.get(0).monsterKilled(0);
        tickAfter(1000);
        tickAfter(StoryScenes.LINE_MAX_MS);

        assertEquals(List.of("Fenna: 60 to 30. pay up."), hands.said);
        for (String personaId : List.of("fenna", "dorran")) {
            verify(bonds).setFlag(ANN, personaId, "story.rivalry.dorran-fenna");
            verify(bonds).setFlag(ANN, personaId, "story.rivalry.dorran-fenna.won.fenna");
        }
        verify(store).event(eq(ANN), eq("fenna"), eq("story.race"), contains("fenna=60% dorran=30%"));
    }

    @Test
    void sharesWithinTheMarginAreEvenAndTheRivalCanWin() {
        raceTo(4_800, 4_200);
        hands.listeners.get(0).monsterKilled(0);
        tickAfter(1000);
        tickAfter(StoryScenes.LINE_MAX_MS);
        assertEquals(List.of("Fenna: 48 and 42? a tie."), hands.said);

        hands.said.clear();
        raceTo(1_000, 7_000);
        hands.listeners.get(1).monsterKilled(0);
        tickAfter(1000);
        tickAfter(StoryScenes.LINE_MAX_MS);
        assertEquals(List.of("Dorran: 70 to 10."), hands.said);
    }

    @Test
    void aRivalWhoWalksOffForfeitsAndTheFoeStillHasToFall() {
        raceTo(0, 0);
        maps.get(DORRAN_ID).set(mock(MapleMap.class));
        hands.exits.left(dorran);
        tickAfter(1000);
        verify(store).event(eq(ANN), eq("fenna"), eq("story.race"), contains("forfeit"));
        tickAfter(StoryScenes.LINE_MAX_MS);
        assertEquals(List.of(), hands.said, "the foe is still up");
        hands.listeners.get(0).monsterKilled(0);
        tickAfter(1000);
        assertEquals(List.of("Fenna: he walked off."), hands.said);
        verify(bonds).setFlag(ANN, "dorran", "story.rivalry.dorran-fenna.won.fenna");
    }

    @Test
    void leavingARaceAlreadySettledStillCompletesItsStep() {
        raceTo(6_000, 3_000);
        hands.listeners.get(0).monsterKilled(0);
        tickAfter(1000); // settled: both bonds flagged
        maps.get(ANN).set(exitMap);
        hands.exits.left(ann); // gone before the outcome lines
        tickAfter(1000);
        assertFalse(scenes.running(ANN));
        verify(store).event(eq(ANN), eq("fenna"), eq("story.scene.abort"), contains("settled: done"));

        tickAfter(StoryScenes.CLOSE_DELAY_MS);
        verify(quests).sceneDone(ann, "rivals-fenna-dorran");
        verify(roster, never()).sayInParty(fenna, "another day, then.");
    }

    @Test
    void aSceneMapIsAStageAndAnOrdinaryOneIsNot() {
        assertFalse(StoryScenes.onStage(field));
        assertFalse(StoryScenes.onStage(null));
        verify(roster, never()).onlineCharacter(anyInt()); // nothing ran
    }
}
