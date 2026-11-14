package server.living.population.rivals;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RivalChallengesTest {
    private static final long MINUTE = RivalChallenges.MINUTE_MS;

    private static PopulationConfig config() {
        PopulationConfig config = new PopulationConfig();
        config.RIVAL_CHALLENGE_GAP_MINUTES = 120;
        config.RIVAL_CHALLENGE_DAILY_MAX = 3;
        return config;
    }

    /** Five minutes on one field, full HP, no window, three members loaded, last challenge a day ago, none today. */
    private static RivalChallenges.Facts ready() {
        return new RivalChallenges.Facts(5 * MINUTE, 1.0, false, 3, 24 * 60 * MINUTE, 0);
    }

    @Test
    void aMainWhoMeetsEveryRuleMayBeChallenged() {
        assertNull(RivalChallenges.blocked(ready(), config()));
    }

    @Test
    void eachRuleOnItsOwnHoldsAChallengeBack() {
        PopulationConfig config = config();
        RivalChallenges.Facts ok = ready();
        assertNotNull(RivalChallenges.blocked(new RivalChallenges.Facts(5 * MINUTE - 1, 1.0, false, 3, ok.sinceLastMs(), 0),
                config), "under five minutes on the field");
        assertNotNull(RivalChallenges.blocked(new RivalChallenges.Facts(ok.onFieldMs(), 0.59, false, 3, ok.sinceLastMs(), 0),
                config), "under 60% HP");
        assertNotNull(RivalChallenges.blocked(new RivalChallenges.Facts(ok.onFieldMs(), 1.0, true, 3, ok.sinceLastMs(), 0),
                config), "a window open");
        assertNotNull(RivalChallenges.blocked(new RivalChallenges.Facts(ok.onFieldMs(), 1.0, false, 1, ok.sinceLastMs(), 0),
                config), "one member loaded");
        assertNotNull(RivalChallenges.blocked(new RivalChallenges.Facts(ok.onFieldMs(), 1.0, false, 3, 119 * MINUTE, 0),
                config), "challenged 119 minutes ago");
        assertNotNull(RivalChallenges.blocked(new RivalChallenges.Facts(ok.onFieldMs(), 1.0, false, 3, ok.sinceLastMs(), 3),
                config), "three today");
    }

    @Test
    void theGapAndTheDailyMaxFollowTheirSwitches() {
        PopulationConfig config = config();
        config.RIVAL_CHALLENGE_GAP_MINUTES = 0;
        config.RIVAL_CHALLENGE_DAILY_MAX = 5;
        assertNull(RivalChallenges.blocked(new RivalChallenges.Facts(5 * MINUTE, 0.6, false, 2, 0L, 4), config));
    }

    @Test
    void aLonePlayerFacesTheMemberWhoseGearEdgeIsNearestTheirs() {
        record Member(String name, double edge) { }
        List<Member> crew = List.of(new Member("far", 1.6), new Member("near", 1.05), new Member("mid", 0.8));

        List<Member> one = RivalChallenges.nearest(crew, 1, Member::edge, 1.0);
        List<Member> two = RivalChallenges.nearest(crew, 2, Member::edge, 1.0);
        List<Member> more = RivalChallenges.nearest(crew, 5, Member::edge, 1.0);

        assertEquals(List.of("near"), one.stream().map(Member::name).toList());
        assertEquals(List.of("near", "mid"), two.stream().map(Member::name).toList());
        assertEquals(3, more.size());
    }

    @Test
    void theAnswerWordsAreTheCrewsYesAndNo() {
        assertEquals(RivalCrews.Answer.YES, RivalCrews.answer("ya lets go"));
        assertEquals(RivalCrews.Answer.YES, RivalCrews.answer("ok"));
        assertEquals(RivalCrews.Answer.NO, RivalCrews.answer("nah busy"));
        assertEquals(RivalCrews.Answer.OTHER, RivalCrews.answer("who are u"));
    }
}
