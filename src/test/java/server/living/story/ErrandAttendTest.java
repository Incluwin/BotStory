package server.living.story;

import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.mentor.MentorLead;
import server.living.resident.LivingTalk;
import server.living.roster.JobErrands;
import server.living.roster.JobInstructors;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.Packets;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An attended job errand (F6.3): the yes/no and what each answer does, the follow to the instructor's town with its
 * hints, the lead through El Nath (a two-way door it goes through first) and Leafre's gate (its owner first), the
 * advancement in front of its owner and the capstone after it; and every way it falls back to today's errand alone.
 */
class ErrandAttendTest {
    private static final byte YES_NO = 1;
    private static final int ANN = 10;
    private static final int FENNA_ID = 901;
    private static final int HENESYS = 100000000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final LivingStore store = mock(LivingStore.class);
    private final RosterService roster = mock(RosterService.class);
    private final JobErrands jobErrands = mock(JobErrands.class);
    private final StoryArcs stories = mock(StoryArcs.class);
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final FakeHands hands = new FakeHands();
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final AtomicReference<MapleMap> annAt = new AtomicReference<>();
    private final AtomicReference<MapleMap> fennaAt = new AtomicReference<>();
    private final AtomicReference<Point> fennaPos = new AtomicReference<>(new Point(0, 0));
    private final Character ann = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Character fenna = mock(Character.class);
    private final Persona persona = mock(Persona.class);
    private final JobInstructors.Instructor rene = JobInstructors.of(Job.RANGER);
    private final JobInstructors.Instructor legor = JobInstructors.of(Job.BOWMASTER);
    private ErrandAttend attend;

    /** The world's side, recorded. */
    final class FakeHands implements ErrandHands {
        final List<String> log = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        boolean walking;
        final Point door = new Point(500, 100);
        final Point npc = new Point(800, 100);

        @Override
        public int nextHop(Character bot, int fromMapId, int goalMapId) {
            log.add("route " + fromMapId + " -> " + goalMapId);
            return fromMapId == ErrandAttend.EL_NATH && goalMapId == ErrandAttend.CHIEFS_RESIDENCE
                    ? ErrandAttend.CHIEFS_RESIDENCE : 0;
        }

        @Override
        public ErrandAttend.Leg portalLeg(Character owner, MapleMap here, int toMapId) {
            return new ErrandAttend.Leg(here.getId(), 3, toMapId, 0, door, new MentorLead.Leg(true, false, "lead.oneway"));
        }

        @Override
        public ErrandAttend.Leg gateLeg(MapleMap here, String portalName, int toMapId) {
            log.add("gate " + portalName);
            return new ErrandAttend.Leg(here.getId(), 7, toMapId, -1, door, new MentorLead.Leg(false, false, "lead.gate"));
        }

        @Override
        public Point npcAt(MapleMap map, int npcId) {
            return npc;
        }

        @Override
        public void walk(Character bot, AgentMapWalk.Target target) {
            log.add("walk " + target.finish() + " " + target.mapId() + "/" + target.id());
        }

        @Override
        public boolean walking(int botId) {
            return walking;
        }

        @Override
        public void follow(Character bot) {
            log.add("follow");
        }

        @Override
        public void say(Character bot, String line) {
            said.add(line);
        }
    }

    private MapleMap map(int id) {
        return maps.computeIfAbsent(id, key -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(key);
            return map;
        });
    }

    private void both(int mapId) {
        annAt.set(map(mapId));
        fennaAt.set(map(mapId));
    }

    @BeforeEach
    void wire() {
        config.JOB_ERRAND_ATTEND_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.roster()).thenReturn(roster);
        when(world.jobErrands()).thenReturn(jobErrands);
        when(world.stories()).thenReturn(stories);
        when(persona.id()).thenReturn("fenna");
        when(persona.name()).thenReturn("Fenna");
        when(persona.homeTown()).thenReturn("henesys");
        when(content.persona("fenna")).thenReturn(persona);

        both(HENESYS);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getMap()).thenAnswer(inv -> annAt.get());
        when(ann.getMapId()).thenAnswer(inv -> annAt.get().getId());
        when(ann.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(ann);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        when(fenna.getId()).thenReturn(FENNA_ID);
        when(fenna.getName()).thenReturn("Fenna");
        when(fenna.getJob()).thenReturn(Job.HUNTER);
        when(fenna.getMap()).thenAnswer(inv -> fennaAt.get());
        when(fenna.getMapId()).thenAnswer(inv -> fennaAt.get().getId());
        when(fenna.getPosition()).thenAnswer(inv -> fennaPos.get());

        RosterEntry entry = new RosterEntry(ANN, "fenna", RosterState.ACTIVE, LivingStore.Assignment.TRAIN, 0, 0, 0, 0L,
                0L, 0L, 0L, 0L, 0L, 0, null);
        when(roster.entry(ANN, "fenna")).thenReturn(entry);
        when(roster.companion(entry)).thenReturn(fenna);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(roster.sayInParty(any(), anyString())).thenReturn(true);
        when(jobErrands.advanceAttended(any(), anyString(), anyInt(), any(), any())).thenAnswer(inv -> {
            when(fenna.getJob()).thenReturn(inv.<Job>getArgument(3));
            return true;
        });
        attend = new ErrandAttend(world, hands, clock::get);
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(ann);
    }

    private void answer(int action) {
        assertTrue(LivingTalk.respond(client, YES_NO, (byte) action, Packets.buildInPacket(out -> { })));
    }

    private void tickAfter(long ms) {
        clock.addAndGet(ms);
        attend.tick();
    }

    /** Asked for {@code job} from {@code instructor}, and Ann said yes. */
    private void accepted(Job job, JobInstructors.Instructor instructor) {
        assertTrue(attend.offer(ann, persona, fenna, job, instructor));
        answer(1);
        tickAfter(1000);
        assertEquals(ErrandAttend.Phase.FOLLOWING, attend.attend("fenna").phase);
    }

    @Test
    void onlyAThirdOrFourthJobIsAttendedAndOnlyWithTheSwitchOn() {
        assertFalse(attend.offer(ann, persona, fenna, Job.HUNTER, JobInstructors.of(Job.HUNTER)), "2nd job: alone");
        config.JOB_ERRAND_ATTEND_ENABLED = false;
        assertFalse(attend.offer(ann, persona, fenna, Job.RANGER, rene), "switched off: alone");
        verify(store, never()).event(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void itAsksOnceAndYesKeepsItInThePartyFollowing() {
        assertTrue(attend.offer(ann, persona, fenna, Job.RANGER, rene));
        assertTrue(LivingTalk.active(client), "the yes/no is up");
        assertTrue(attend.offer(ann, persona, fenna, Job.RANGER, rene), "asked already: still nothing for JobErrands");
        verify(store).event(eq(ANN), eq("fenna"), eq("job.attend.ask"), anyString());

        answer(1);
        tickAfter(1000);
        assertEquals(ErrandAttend.Phase.FOLLOWING, attend.attend("fenna").phase);
        verify(roster).sayInParty(fenna, "great. get us to el nath and i'll show you the last bit.");
        verify(store).event(eq(ANN), eq("fenna"), eq("job.attend"), anyString());
        verify(jobErrands, never()).due(anyInt(), any());
    }

    @Test
    void noSendsItAloneAsBeforeAndTheNextErrandAsksAgain() {
        attend.offer(ann, persona, fenna, Job.RANGER, rene);
        answer(0);
        tickAfter(1000);

        assertNull(attend.attend("fenna"));
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
        verify(roster, never()).sayInParty(any(), anyString());
        assertFalse(attend.offer(ann, persona, fenna, Job.RANGER, rene), "JobErrands' due: it goes alone, unasked");
        assertTrue(attend.offer(ann, persona, fenna, Job.RANGER, rene), "a later errand asks again");
    }

    @Test
    void aWindowAlreadyOpenIsWaitedForThenItGoesAlone() {
        LivingTalk.open(ann, 9040011).menu("a window", List.of(new LivingTalk.Option("x", () -> { }))).show();
        attend.offer(ann, persona, fenna, Job.RANGER, rene);
        for (int i = 1; i < config.JOB_ERRAND_ATTEND_ASK_TRIES - 1; i++) {
            tickAfter(config.JOB_ERRAND_ATTEND_ASK_RETRY_MS);
            assertTrue(attend.any(), "try " + i);
        }
        tickAfter(config.JOB_ERRAND_ATTEND_ASK_RETRY_MS);
        assertFalse(attend.any());
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
    }

    @Test
    void aWindowClosedUnansweredIsANo() {
        attend.offer(ann, persona, fenna, Job.RANGER, rene);
        LivingTalk.forget(ann); // escape
        tickAfter(1000);
        assertTrue(attend.any(), "a click may still be on its way");
        tickAfter(ErrandAttend.CLOSED_GRACE_MS);
        assertFalse(attend.any());
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
    }

    @Test
    void onTheWayItSaysWhereNextOncePerTownAndGoesAheadWhenItTakesTooLong() {
        accepted(Job.RANGER, rene);
        tickAfter(1000);
        tickAfter(1000);
        verify(roster).sayInParty(fenna, AttendLines.VICTORIA);
        both(ErrandAttend.ORBIS);
        tickAfter(1000);
        verify(roster).sayInParty(fenna, AttendLines.ORBIS_TO_EL_NATH);

        tickAfter(config.JOB_ERRAND_ATTEND_MAX_MINUTES * 60_000L);
        assertFalse(attend.any());
        verify(roster).sayInParty(fenna, "i'll go on ahead. see you after.");
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
    }

    @Test
    void inElNathItLeadsThroughTheDoorAdvancesInFrontOfItsOwnerThenTheCapstone() {
        accepted(Job.RANGER, rene);
        both(ErrandAttend.EL_NATH);
        tickAfter(1000);
        assertTrue(attend.leading(FENNA_ID), "walking ahead: off follow");
        assertTrue(hands.log.contains("walk REACH_PORTAL " + ErrandAttend.EL_NATH + "/3"), hands.log.toString());
        verify(roster).sayInParty(fenna, "this way. stay close.");

        fennaPos.set(hands.door);
        tickAfter(1000);
        assertTrue(hands.log.contains("walk ENTER_PORTAL " + ErrandAttend.EL_NATH + "/3"), "a two-way door: it goes first");
        fennaAt.set(map(ErrandAttend.CHIEFS_RESIDENCE));
        tickAfter(1000);
        annAt.set(map(ErrandAttend.CHIEFS_RESIDENCE));
        tickAfter(1000);
        assertFalse(attend.leading(FENNA_ID), "the leg is done");
        assertTrue(hands.log.contains("follow"));

        tickAfter(1000);
        assertEquals(ErrandAttend.Phase.AT_INSTRUCTOR, attend.attend("fenna").phase);
        assertTrue(hands.log.contains("walk NPC " + ErrandAttend.CHIEFS_RESIDENCE + "/" + rene.npcId()));
        fennaPos.set(hands.npc);
        tickAfter(1000);
        assertEquals(List.of("here we are. stay close, this won't take long."), hands.said);
        verify(jobErrands, never()).advanceAttended(any(), anyString(), anyInt(), any(), any());

        tickAfter(config.COMPANION_JOB_ERRAND_TALK_MS);
        verify(jobErrands).advanceAttended(fenna, "fenna", ANN, Job.RANGER, rene);
        tickAfter(ErrandAttend.AFTER_MS);
        assertEquals(2, hands.said.size());
        assertTrue(hands.said.get(1).endsWith("! thanks for coming with me."), hands.said.get(1));
        assertFalse(attend.any());
        assertFalse(attend.leading(FENNA_ID));
        verify(stories).capstoneAfterErrand(ann, persona);
        verify(jobErrands, never()).due(anyInt(), any());
        verify(store).event(ANN, "fenna", "job.attend.done", "RANGER");
    }

    @Test
    void itsStoryLinesForTheTierComeFirst() {
        server.living.config.Content.Story story = new server.living.config.Content.Story("fenna-long-road", "fenna",
                List.of(), null, Map.of("errand.attend.arrive.3", List.of("here. the chief's house.")), "test");
        when(content.storyOf("fenna")).thenReturn(story);
        accepted(Job.RANGER, rene);
        both(ErrandAttend.CHIEFS_RESIDENCE);
        tickAfter(1000);
        fennaPos.set(hands.npc);
        tickAfter(1000);
        assertEquals(List.of("here. the chief's house."), hands.said);
    }

    @Test
    void atLeafresGateItsOwnerGoesFirstAndItFollows() {
        accepted(Job.BOWMASTER, legor);
        both(ErrandAttend.FOREST_GATE);
        tickAfter(1000);
        assertTrue(hands.log.contains("gate " + ErrandAttend.FOREST_GATE_PORTAL), hands.log.toString());
        fennaPos.set(hands.door);
        tickAfter(1000);
        verify(roster).sayInParty(fenna, "the gate's here. you first, i'll follow.");
        assertFalse(hands.log.stream().anyMatch(entry -> entry.startsWith("walk ENTER_PORTAL")), "it never takes the gate first");

        annAt.set(map(ErrandAttend.FOREST_OF_THE_PRIEST));
        tickAfter(1000);
        assertFalse(attend.leading(FENNA_ID));
        fennaAt.set(map(ErrandAttend.FOREST_OF_THE_PRIEST));
        tickAfter(1000);
        assertEquals(ErrandAttend.Phase.AT_INSTRUCTOR, attend.attend("fenna").phase);
    }

    @Test
    void anOwnerWhoWalksOffMidLegIsFollowedAndLedAgainInTown() {
        accepted(Job.RANGER, rene);
        both(ErrandAttend.EL_NATH);
        tickAfter(1000);
        annAt.set(map(211040000)); // out to the snowfield
        tickAfter(1000);
        assertEquals(ErrandAttend.Phase.FOLLOWING, attend.attend("fenna").phase);
        assertFalse(attend.leading(FENNA_ID));
        both(ErrandAttend.EL_NATH);
        tickAfter(1000);
        assertEquals(ErrandAttend.Phase.LEADING, attend.attend("fenna").phase);
    }

    @Test
    void aLogoutSendsItAloneUnaskedOnceItsOwnerIsBack() {
        accepted(Job.RANGER, rene);
        when(roster.onlineCharacter(ANN)).thenReturn(null);
        tickAfter(1000);
        assertFalse(attend.any());
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
        verify(roster, never()).sayInParty(any(), eq("i'll go on ahead. see you after."));
        assertFalse(attend.offer(ann, persona, fenna, Job.RANGER, rene), "back online: today's errand");
    }

    @Test
    void theSwitchGoingOffSendsItAlone() {
        accepted(Job.RANGER, rene);
        both(ErrandAttend.EL_NATH);
        tickAfter(1000);
        assertTrue(attend.leading(FENNA_ID));
        config.JOB_ERRAND_ATTEND_ENABLED = false;
        tickAfter(1000);
        assertFalse(attend.any());
        assertFalse(attend.leading(FENNA_ID), "off follow no more");
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
    }

    @Test
    void aLeadItsOwnerNeverFollowsGivesUpAndItGoesAlone() {
        accepted(Job.RANGER, rene);
        both(ErrandAttend.EL_NATH);
        tickAfter(1000);
        fennaPos.set(hands.door);
        tickAfter(1000);
        fennaAt.set(map(ErrandAttend.CHIEFS_RESIDENCE));
        tickAfter(1000);
        for (int i = 0; i <= config.MENTOR_LEAD_TRIES; i++) { // it waits beyond, comes back, walks ahead again
            tickAfter(config.MENTOR_LEAD_WAIT_MS);
            fennaAt.set(map(ErrandAttend.EL_NATH));
            tickAfter(1000);
            tickAfter(1000);
            fennaAt.set(map(ErrandAttend.CHIEFS_RESIDENCE));
            tickAfter(1000);
        }
        assertFalse(attend.any(), "Ann never came through");
        assertFalse(attend.leading(FENNA_ID));
        verify(roster, atLeastOnce()).sayInParty(fenna, "you coming?");
        verify(roster).sayInParty(fenna, "i'll go on ahead. see you after.");
        verify(jobErrands).due(FENNA_ID, Job.RANGER);
    }

    @Test
    void aRefusedJobChangeEndsItWithNoThanks() {
        when(jobErrands.advanceAttended(any(), anyString(), anyInt(), any(), any())).thenReturn(false);
        accepted(Job.RANGER, rene);
        both(ErrandAttend.CHIEFS_RESIDENCE);
        tickAfter(1000);
        fennaPos.set(hands.npc);
        tickAfter(1000);
        tickAfter(config.COMPANION_JOB_ERRAND_TALK_MS);
        assertFalse(attend.any());
        assertEquals(1, hands.said.size(), "the arrive line only");
        verify(stories, never()).capstoneAfterErrand(any(), any());
        verify(jobErrands, never()).due(anyInt(), any());
    }

    @Test
    void theInstructorsTownIsWhereItLeads() {
        assertTrue(ErrandAttend.inRegion(ErrandAttend.CHIEFS_RESIDENCE, ErrandAttend.EL_NATH));
        assertFalse(ErrandAttend.inRegion(ErrandAttend.CHIEFS_RESIDENCE, ErrandAttend.ORBIS));
        assertTrue(ErrandAttend.inRegion(ErrandAttend.FOREST_OF_THE_PRIEST, ErrandAttend.LEAFRE));
        assertTrue(ErrandAttend.inRegion(ErrandAttend.FOREST_OF_THE_PRIEST, ErrandAttend.FOREST_GATE));
        assertFalse(ErrandAttend.inRegion(ErrandAttend.FOREST_OF_THE_PRIEST, ErrandAttend.EL_NATH));
        assertTrue(ErrandAttend.inRegion(130000000, 130000000), "Ereve: the instructor's own map");
    }
}
