package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.supplies.AgentAmmoStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * LPQ stage 7 in a companion run (2026-10-07): a member that held a ratz lane and ran out of projectiles says who
 * should take its ratz (the loaded shooter now on its lane, who answers), or with nobody left to shoot asks the leader
 * to go up (a loaded ranged leader) or for projectiles; a member that never held a lane leaves it to the stage's start
 * line. The flags that keep it from saying it twice are set only when the line was said.
 */
class AgentLpqStageSevenDryNewsTest {
    private static final int KESTREL = 401;

    @Test
    void aMemberThatNeverHeldALaneSaysNoStageSevenLine() {
        assertNull(AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, false, 0, List.of(402), true, true),
                "it entered dry: s7-start already named the loaded one");
    }

    @Test
    void oneShooterLeftTakesThemAll() {
        AgentLpqCoordinator.StageSevenDryNews news =
                AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, true, 1, List.of(402), false, true);
        assertEquals(AgentLpqCoordinator.StageSevenDryNews.Kind.TAKE_ALL, news.kind());
        assertEquals(402, news.takerId());
        assertEquals("s7-dry-" + KESTREL, news.key());
    }

    @Test
    void twoShootersLeftShareAndTheTakerIsTheOneOnItsLane() {
        AgentLpqCoordinator.StageSevenDryNews news =
                AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, true, 1, List.of(402, 403), false, true);
        assertEquals(AgentLpqCoordinator.StageSevenDryNews.Kind.TAKE_SIDE, news.kind());
        assertEquals(403, news.takerId(), "the shooter that now holds its lane");
        assertEquals(402, AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, true, 0, List.of(402, 403), false, true)
                .takerId());
    }

    @Test
    void twoDryMembersWithNoShooterShareOneNoTakerKey() {
        AgentLpqCoordinator.StageSevenDryNews first =
                AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, true, 0, List.of(), false, true);
        AgentLpqCoordinator.StageSevenDryNews second =
                AgentLpqCoordinator.stageSevenDryNews(404, true, true, 1, List.of(), false, true);
        assertEquals("s7-dry-none", first.key());
        assertEquals(first.key(), second.key(), "said once a run, by whoever says it first");
        assertEquals(0, first.takerId());
    }

    @Test
    void aLoadedRangedLeaderIsAskedToGoUpNotForStars() {
        assertEquals(AgentLpqCoordinator.StageSevenDryNews.Kind.ASK_LEADER_UP,
                AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, true, 0, List.of(), true, true).kind());
        assertEquals(AgentLpqCoordinator.StageSevenDryNews.Kind.ASK_LEADER_AMMO,
                AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, true, 0, List.of(), false, true).kind());
    }

    @Test
    void withNobodyLoadedForTheLiveRatzTheFirstDryMemberAsksTheLeaderEvenWithoutALane() {
        // review: every box-wacker entered stage 7 dry (Kestrel with no loaded Hunter): nobody tried the ratz and
        // nobody said a word until the run timed out
        AgentLpqCoordinator.StageSevenDryNews news =
                AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, false, 0, List.of(), false, true);
        assertEquals(AgentLpqCoordinator.StageSevenDryNews.Kind.ASK_LEADER_AMMO, news.kind());
        assertEquals("s7-dry-none", news.key(), "once a run, whoever says it first");
        assertNull(AgentLpqCoordinator.stageSevenDryNews(KESTREL, true, false, 0, List.of(), false, false),
                "no live ratz: nothing to ask for");
    }

    @Test
    void aLineToANamelessLeaderOrTakerIsNotSaid() {
        // review: an offline leader made ", i'm out of stars and nobody else can reach the ratz up there..."
        for (AgentLpqCoordinator.StageSevenDryNews.Kind kind : AgentLpqCoordinator.StageSevenDryNews.Kind.values()) {
            assertNull(AgentLpqCoordinator.stageSevenDryText(kind, null, null, null), kind.name());
            assertNull(AgentLpqCoordinator.stageSevenDryText(kind, " ", "", null), kind.name());
        }
        assertEquals("Ann, i'm out of stars. can you get the ratz up there?",
                AgentLpqCoordinator.stageSevenDryText(AgentLpqCoordinator.StageSevenDryNews.Kind.ASK_LEADER_UP,
                        null, "Ann", server.agents.capabilities.supplies.AgentProjectileKind.STARS));
        assertEquals("Pell, i'm out of stars. can you get the ratz up there for me?",
                AgentLpqCoordinator.stageSevenDryText(AgentLpqCoordinator.StageSevenDryNews.Kind.TAKE_ALL,
                        "Pell", "", server.agents.capabilities.supplies.AgentProjectileKind.STARS));
    }

    @Test
    void theRunOwnsTheDryLineOnlyWhileItCanSayIt() {
        AgentLpqSession run = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 1L, 400, 6, 0L);
        run.addMember(400, AgentLpqMemberState.MemberType.HUMAN);
        run.addMember(KESTREL, AgentLpqMemberState.MemberType.AGENT);
        run.setCompanionAdopted(true);
        int stageSeven = AgentLpqDefinition.stage(7).mapId();
        AgentLpqMemberState kestrel = run.member(KESTREL);
        kestrel.holdStageSevenLane(0);

        assertFalse(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven),
                "member coordination chat off: S7d cannot be said, so the generic line speaks");
        run.setMemberCoordinationChatEnabled(true);
        assertTrue(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven));
        assertFalse(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven + 100), "another stage");

        kestrel.releaseStageSevenDryLine(); // its S7d was not said (leader offline, key taken...)
        assertFalse(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven));
    }

    @Test
    void withNobodyLoadedTheRunAlsoOwnsTheDryLineOfAMemberThatNeverHeldALane() {
        AgentLpqSession run = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 1L, 400, 6, 0L);
        run.addMember(400, AgentLpqMemberState.MemberType.HUMAN);
        run.addMember(KESTREL, AgentLpqMemberState.MemberType.AGENT);
        run.setCompanionAdopted(true);
        run.setMemberCoordinationChatEnabled(true);
        int stageSeven = AgentLpqDefinition.stage(7).mapId();

        assertFalse(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven));
        run.setStageSevenNoShooter(true);
        assertTrue(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven), "S7d asks the leader");
        run.setCompanionAdopted(false);
        assertFalse(AgentLpqCoordinator.ownsDryLine(run, KESTREL, stageSeven), "a stock run");
    }

    @Test
    void aStockRunHasNoDryNews() {
        assertNull(AgentLpqCoordinator.stageSevenDryNews(KESTREL, false, true, 0, List.of(402), true, true));
    }

    @Test
    void dryLineSaidIsSetOnlyWhenTheLineWasSaid() {
        AgentLpqMemberState member = new AgentLpqMemberState(KESTREL, AgentLpqMemberState.MemberType.AGENT);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), null, null);

        AgentLpqCoordinator.afterStageSevenDryNews(false, member, entry);
        assertFalse(member.saidStageSevenDry(), "its coordination chat is off, say: nothing was said");
        assertFalse(AgentAmmoStateRuntime.dryLineSaid(entry));

        AgentLpqCoordinator.afterStageSevenDryNews(true, member, entry);
        assertTrue(member.saidStageSevenDry());
        assertTrue(AgentAmmoStateRuntime.dryLineSaid(entry), "the generic in-run line stays quiet now");
    }
}
