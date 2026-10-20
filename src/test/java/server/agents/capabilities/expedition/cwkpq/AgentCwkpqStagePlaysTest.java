package server.agents.capabilities.expedition.cwkpq;

import client.BuffStat;
import client.Character;
import client.Job;
import constants.skills.Assassin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B5, the stage plays of phase C4. Stage 1: the first member takes the hidden portal under the lion's head
 * (saying where the way is, once) while the others wait by the exit, then everyone takes the exit. Stage 2: the first
 * member of each archetype who can turn a waiting sigil walks up beside it and casts one of its skills on it; a sigil a
 * player turned is left alone, and everyone else waits by the exit until all five are turned. Stage 4: a bowman or
 * pirate picture is cast on at once; a warrior, mage or thief picture is tried with one plain hit first: a fake turns
 * and is spent, a spot that does not move is a real sigil and takes the class skill.
 */
class AgentCwkpqStagePlaysTest {
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    private final AgentCwkpqSigilCast.Casting casting = mock(AgentCwkpqSigilCast.Casting.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<Integer, Point> positions = new HashMap<>();
    private final List<String> party = new ArrayList<>();
    private final List<Reactor> reactors = new ArrayList<>();
    private final AgentCwkpqRun run = new AgentCwkpqRun(Map.of());
    private AgentCwkpqPorts ports;

    @BeforeEach
    void aMapWithItsPortals() {
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
                return partyQuest;
            }

            @Override
            public AgentCwkpqSigilCast.Casting casting() {
                return casting;
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
        portal("next00", 1, new Point(824, 328));
        portal("next01", 2, new Point(1547, 336));
        when(gateway.grounded(any())).thenReturn(true);
        when(gateway.groundPoint(any(), any())).thenAnswer(inv -> new Point((Point) inv.getArgument(1)));
        when(gateway.reactors(any())).thenAnswer(inv -> List.copyOf(reactors));
        when(gateway.hitReactor(any(), anyInt())).thenReturn(true);
        when(casting.planAt(any(), any(), anyInt())).thenAnswer(inv -> new AgentAttackPlan(inv.getArgument(2), 10, 1,
                new Rectangle(-5000, -5000, 10000, 10000), List.of(), AgentAttackRoute.CLOSE, 0, 0, 0, 0, 4, 0, 600,
                null));
        when(casting.attackAtNothing(any(), any(), any())).thenAnswer(inv -> new AgentAttackTransactionResult(
                AgentAttackTransactionResult.Status.COMMITTED, AgentAttackTransactionResult.Reason.NONE, 0,
                ((AgentAttackPlan) inv.getArgument(2)).skillId, List.of(), 0, 0, 0L));
    }

    // ---- stage 1 ----

    @Test
    void theFirstMemberTakesTheHiddenPortalAndSaysWhereTheWayIsOnce() {
        Character orrin = member(10, "Orrin", Job.HERO, new Point(1547, 336));
        Character magna = member(11, "Magna", Job.BISHOP, new Point(100, 328));

        AgentCwkpqStagePlays.hallway(ports, List.of(magna, orrin), event, run, 1_000L);
        AgentCwkpqStagePlays.hallway(ports, List.of(magna, orrin), event, run, 1_500L);

        verify(partyQuest).enterPortal(orrin, 2);
        verify(partyQuest, never()).enterPortal(eq(magna), anyInt());
        verify(gateway, atLeastOnce()).navigate(entries.get(11), new Point(824, 328), false);
        assertEquals(List.of("Orrin: " + AgentCwkpqStagePlays.HALLWAY_HINT), party);
    }

    @Test
    void onceTheWayIsOpenEveryoneTakesTheExit() {
        when(event.getIntProperty("glpq1")).thenReturn(1);
        Character orrin = member(10, "Orrin", Job.HERO, new Point(824, 328));
        Character magna = member(11, "Magna", Job.BISHOP, new Point(824, 330));

        AgentCwkpqStagePlays.hallway(ports, List.of(orrin, magna), event, run, 1_000L);

        verify(partyQuest).enterPortal(orrin, 1);
        verify(partyQuest).enterPortal(magna, 1);
    }

    // ---- stage 2 ----

    @Test
    void eachWaitingSigilGetsTheFirstMemberWhoCanTurnItAndTheRestWaitByTheExit() {
        Reactor thiefSigil = reactor(31, 6_109_003, new Point(770, -90), 0, true);
        reactor(32, 6_109_000, new Point(-751, 2), 1, false); // a player turned the warrior sigil
        Character fenna = member(10, "Fenna", Job.HERMIT, new Point(725, -90), 4_111_005);
        Character orrin = member(11, "Orrin", Job.HERO, new Point(0, 300), 1_111_008);
        Character vex = member(12, "Vex", Job.NIGHTLORD, new Point(0, 300), 4_111_005);

        AgentCwkpqStagePlays.storage(ports, List.of(fenna, orrin, vex), event, run, 1_000L);

        verify(casting).hitWithSkill(fenna, 31, 4_111_005);
        verify(gateway).facePosition(fenna, thiefSigil.getPosition());
        verify(casting, never()).hitWithSkill(eq(orrin), anyInt(), anyInt());
        verify(casting, never()).hitWithSkill(eq(vex), anyInt(), anyInt());
        verify(gateway).navigate(entries.get(11), new Point(824, 328), false);
        verify(gateway).navigate(entries.get(12), new Point(824, 328), false);
    }

    @Test
    void aTurnerFarFromItsSigilWalksUpBesideItFirst() {
        reactor(31, 6_109_003, new Point(770, -90), 0, true);
        Character fenna = member(10, "Fenna", Job.HERMIT, new Point(0, 300), 4_111_005);

        AgentCwkpqStagePlays.storage(ports, List.of(fenna), event, run, 1_000L);

        verify(gateway).navigate(entries.get(10), new Point(725, -90), true);
        verify(casting, never()).hitWithSkill(any(), anyInt(), anyInt());
    }

    @Test
    void withAllFiveTurnedEveryoneTakesTheExit() {
        when(event.getIntProperty("glpq2")).thenReturn(5);
        Character fenna = member(10, "Fenna", Job.HERMIT, new Point(824, 328), 4_111_005);

        AgentCwkpqStagePlays.storage(ports, List.of(fenna), event, run, 1_000L);

        verify(partyQuest).enterPortal(fenna, 1);
    }

    // ---- stage 3 ----

    @Test
    void aThiefPutsItsHasteUpFirst() {
        agilityPortals();
        Character fenna = member(10, "Fenna", Job.HERMIT, new Point(-483, 1536), 4_111_005, Assassin.HASTE);
        when(casting.buff(any(), any(), anyInt())).thenReturn(true);
        when(fenna.getBuffedValue(BuffStat.SPEED)).thenReturn(null); // no Haste on yet

        AgentCwkpqStagePlays.agility(ports, List.of(fenna), event, run, 1_000L);
        AgentCwkpqStagePlays.agility(ports, List.of(fenna), event, run, 1_200L);

        verify(casting).buff(entries.get(10), fenna, Assassin.HASTE);
        when(fenna.getBuffedValue(BuffStat.SPEED)).thenReturn(45);
        AgentCwkpqStagePlays.agility(ports, List.of(fenna), event, run, 3_000L);
        verify(casting).buff(any(), any(), anyInt()); // once: its Haste is up
    }

    @Test
    void aLowSigilIsClimbedToAtAnyStatsAndAHighOneOnlyHasted() {
        agilityPortals();
        reactor(51, 6_109_000, new Point(857, 1431), 0, true); // the warrior's, on the floor
        reactor(52, 6_109_002, new Point(322, -789), 0, true); // the mage's, past the gap
        Character orrin = member(10, "Orrin", Job.HERO, new Point(812, 1431), 1_111_008);
        Character magna = member(11, "Magna", Job.FP_ARCHMAGE, new Point(100, 1536), 2_111_002);

        AgentCwkpqStagePlays.agility(ports, List.of(orrin, magna), event, run, 1_000L);

        verify(casting).hitWithSkill(orrin, 51, 1_111_008);
        verify(gateway, never()).navigate(eq(entries.get(11)), eq(new Point(277, -789)), anyBoolean());
        verify(gateway).navigate(entries.get(11), new Point(-441, 1535), false);
        assertEquals(List.of("Magna: " + AgentCwkpqStagePlays.NO_HIGH_CLIMBER), party);

        hasted(magna);
        AgentCwkpqStagePlays.agility(ports, List.of(orrin, magna), event, run, 2_000L);
        verify(gateway).navigate(entries.get(11), new Point(277, -789), true);
        assertEquals(1, party.size(), "said once");
    }

    @Test
    void aHastedMemberWithNoSigilLeftTakesTheTopPortalFromItsLongFloorUntilFivePasses() {
        agilityPortals();
        Character orrin = member(10, "Orrin", Job.HERO, new Point(310, -1104), 1_111_008);
        hasted(orrin);
        Character dorran = member(11, "Dorran", Job.PALADIN, new Point(100, 1536), 1_211_002);

        AgentCwkpqStagePlays.agility(ports, List.of(orrin, dorran), event, run, 1_000L);

        verify(partyQuest).enterPortal(orrin, 1);
        verify(gateway).navigate(entries.get(11), new Point(-441, 1535), false);

        when(event.getIntProperty("glpq3_p")).thenReturn(5);
        AgentCwkpqStagePlays.agility(ports, List.of(orrin, dorran), event, run, 3_000L);
        verify(partyQuest).enterPortal(orrin, 1); // no pass wanted: it waits below
    }

    @Test
    void withFiveSigilsAndFivePassesEveryoneTakesTheBottomPortal() {
        agilityPortals();
        when(event.getIntProperty("glpq3")).thenReturn(5);
        when(event.getIntProperty("glpq3_p")).thenReturn(5);
        Character orrin = member(10, "Orrin", Job.HERO, new Point(-441, 1535), 1_111_008);

        AgentCwkpqStagePlays.agility(ports, List.of(orrin), event, run, 1_000L);

        verify(partyQuest).enterPortal(orrin, 2);
    }

    @Test
    void theHighClimbIsHasteOrBetter() {
        Character climber = member(10, "Orrin", Job.HERO, new Point(0, 0));
        assertFalse(AgentCwkpqStagePlays.climbsHigh(climber), "base 100/100");
        when(climber.getTotalMoveSpeedStat()).thenReturn(145);
        when(climber.getTotalJumpStat()).thenReturn(100);
        assertFalse(AgentCwkpqStagePlays.climbsHigh(climber), "Haste's speed without its jump");
        when(climber.getTotalJumpStat()).thenReturn(110);
        assertTrue(AgentCwkpqStagePlays.climbsHigh(climber));
        when(climber.getTotalMoveSpeedStat()).thenReturn(120);
        when(climber.getTotalJumpStat()).thenReturn(120);
        assertTrue(AgentCwkpqStagePlays.climbsHigh(climber));
        assertTrue(AgentCwkpqStagePlays.reaches(member(11, "Dorran", Job.PALADIN, new Point(0, 0)),
                new Point(240, 531)), "a low sigil");
    }

    private void agilityPortals() {
        portal("next00", 1, new Point(215, -1107));
        portal("next01", 2, new Point(-441, 1535));
    }

    private static void hasted(Character member) {
        when(member.getTotalMoveSpeedStat()).thenReturn(145);
        when(member.getTotalJumpStat()).thenReturn(120);
    }

    // ---- stage 4 ----

    @Test
    void aBowmanPictureIsAlwaysRealAndIsCastOnAtOnce() {
        reactor(41, 6_109_017, new Point(1181, -189), 1, true);
        Character fenna = member(10, "Fenna", Job.BOWMASTER, new Point(1136, -189), 3_111_006);

        AgentCwkpqStagePlays.wit(ports, List.of(fenna), event, run, 1_000L);

        verify(casting).hitWithSkill(fenna, 41, 3_111_006);
        verify(gateway, never()).hitReactor(any(), anyInt());
    }

    @Test
    void aWarriorPictureIsTriedWithOnePlainHitAndASpotThatDoesNotMoveTakesTheClassSkill() {
        reactor(42, 6_109_016, new Point(7, -969), 1, true);
        Character orrin = member(10, "Orrin", Job.HERO, new Point(-38, -969), 1_111_008);

        AgentCwkpqStagePlays.wit(ports, List.of(orrin), event, run, 1_000L);
        verify(gateway).hitReactor(orrin, 42);
        verify(casting, never()).hitWithSkill(any(), anyInt(), anyInt());

        AgentCwkpqStagePlays.wit(ports, List.of(orrin), event, run, 1_500L);
        verify(casting, never()).hitWithSkill(any(), anyInt(), anyInt());

        AgentCwkpqStagePlays.wit(ports, List.of(orrin), event, run, 2_000L);
        assertTrue(run.knownReal(42), "it did not move: a real sigil");
        verify(casting).hitWithSkill(orrin, 42, 1_111_008);
    }

    @Test
    void aFakeThatTurnsAtTheTestHitIsSpentAndItsTesterWaitsByTheExit() {
        Reactor fake = reactor(43, 6_108_001, new Point(374, -969), 1, true);
        Character orrin = member(10, "Orrin", Job.HERO, new Point(329, -969), 1_111_008);
        portal("next00", 1, new Point(-433, 635));

        AgentCwkpqStagePlays.wit(ports, List.of(orrin), event, run, 1_000L);
        verify(gateway).hitReactor(orrin, 43);
        when(fake.getState()).thenReturn((byte) 2);

        AgentCwkpqStagePlays.wit(ports, List.of(orrin), event, run, 2_000L);
        verify(casting, never()).hitWithSkill(any(), anyInt(), anyInt());
        verify(gateway).navigate(entries.get(10), new Point(-433, 635), false);
        AgentCwkpqStagePlays.wit(ports, List.of(orrin), event, run, 3_000L);
        assertEquals(List.of("Orrin: the middle one up top's a fake"), party, "said once, where it is");
    }

    @Test
    void theSpotsAreNamedAsAPlayerWouldSayThem() {
        assertEquals("right one up top", AgentCwkpqStagePlays.where(new Point(998, -847)));
        assertEquals("right one halfway up", AgentCwkpqStagePlays.where(new Point(1175, -547)));
        assertEquals("left one low", AgentCwkpqStagePlays.where(new Point(-804, 52)));
        assertEquals("left one halfway up", AgentCwkpqStagePlays.where(new Point(-799, -668)));
        assertEquals("middle one up top", AgentCwkpqStagePlays.where(new Point(7, -969)));
    }

    @Test
    void theFakesShowAWarriorMageOrThiefPictureAndNoneShowsABowmanOrPirate() {
        assertEquals(AgentCwkpqSigils.Archetype.WARRIOR, AgentCwkpqStagePlays.picture(6_108_001));
        assertEquals(AgentCwkpqSigils.Archetype.MAGICIAN, AgentCwkpqStagePlays.picture(6_108_000));
        assertEquals(AgentCwkpqSigils.Archetype.THIEF, AgentCwkpqStagePlays.picture(6_108_005));
        assertEquals(AgentCwkpqSigils.Archetype.PIRATE, AgentCwkpqStagePlays.picture(6_109_020));
        assertTrue(AgentCwkpqStagePlays.alwaysReal(AgentCwkpqSigils.Archetype.BOWMAN));
        assertTrue(AgentCwkpqStagePlays.alwaysReal(AgentCwkpqSigils.Archetype.PIRATE));
        assertEquals(null, AgentCwkpqStagePlays.picture(6_109_000), "a stage-2 sigil is no stage-4 spot");
    }

    private Character member(int id, String name, Job job, Point at, int... skills) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getJob()).thenReturn(job);
        when(member.getMap()).thenReturn(map);
        when(member.getSkillLevel(anyInt())).thenReturn(0);
        for (int skill : skills) {
            when(member.getSkillLevel(skill)).thenReturn(10);
        }
        positions.put(id, at);
        when(member.getPosition()).thenAnswer(inv -> positions.get(id));
        entries.put(id, mock(AgentRuntimeEntry.class));
        run.adopt(id, entries.size() - 1);
        return member;
    }

    private Reactor reactor(int objectId, int reactorId, Point at, int state, boolean active) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getObjectId()).thenReturn(objectId);
        when(reactor.getId()).thenReturn(reactorId);
        when(reactor.getPosition()).thenReturn(at);
        when(reactor.getState()).thenReturn((byte) state);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.isActive()).thenReturn(active);
        reactors.add(reactor);
        return reactor;
    }

    private void portal(String name, int id, Point at) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(id);
        when(portal.getPosition()).thenReturn(at);
        when(map.getPortal(name)).thenReturn(portal);
    }
}
