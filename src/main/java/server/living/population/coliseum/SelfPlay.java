package server.living.population.coliseum;

import client.Character;
import config.PvpConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.story.DuelGround;
import server.pvp.DuelPolicies;
import server.pvp.DuelSelfPlay;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Citizens' training bouts (docs/WORLD_SYSTEMS_PLAN.md I1.2, decision D17, pvp.DUEL_LEARNING_ENABLED): off-peak, up to
 * DUEL_SELFPLAY_BOUTS pairs of free citizens nobody is looking at (no human on either one's map) fight on a private copy
 * of an arena floor ({@link DuelSelfPlay}) and go back to the spot they stood on. No one is told: no announcement, no
 * ladder row, no line, no memory. It rides the coliseum's minute and one-second step.
 */
final class SelfPlay {
    private static final Logger log = LoggerFactory.getLogger(SelfPlay.class);
    static final long SETTLE_MS = 3_000L;
    /** A partner this many levels away at most. */
    static final int LEVEL_SPAN = 5;

    /** One bout on its floor. */
    private static final class Bout {
        final DuelSelfPlay play;
        final DuelGround ground;
        long nextAtMs;
        long deadlineMs;

        Bout(DuelSelfPlay play, DuelGround ground, long nextAtMs) {
            this.play = play;
            this.ground = ground;
            this.nextAtMs = nextAtMs;
        }
    }

    private final Coliseum coliseum;
    private final List<Bout> bouts = new CopyOnWriteArrayList<>();
    private int turn;

    SelfPlay(Coliseum coliseum) {
        this.coliseum = coliseum;
    }

    boolean idle() {
        return bouts.isEmpty();
    }

    boolean fighting(int citizenId) {
        for (Bout bout : bouts) {
            if (bout.play.a().getId() == citizenId || bout.play.b().getId() == citizenId) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code now} is inside [from, to) hours UTC; a window over midnight wraps, an empty one never holds. */
    static boolean offPeak(long now, int from, int to) {
        int hour = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).getHour();
        return from < to ? hour >= from && hour < to : from > to && (hour >= from || hour < to);
    }

    void minute(long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        if (pvp.DUEL_LEARNING_ENABLED && offPeak(now, pvp.DUEL_SELFPLAY_FROM_HOUR, pvp.DUEL_SELFPLAY_TO_HOUR)) {
            begin(pvp.DUEL_SELFPLAY_BOUTS - bouts.size(), now);
        }
    }

    /** Begins up to {@code wanted} bouts; how many. */
    synchronized int begin(int wanted, long now) {
        List<CitizenSession> free = new ArrayList<>();
        for (CitizenSession session : coliseum.population().presence().sessions()) {
            if (coliseum.free(session, 0, 0) && session.agent().getLevel() >= 30
                    && Population.humansIn(session.agent().getMap()) == 0) {
                free.add(session);
            }
        }
        Collections.shuffle(free);
        int begun = 0;
        while (begun < wanted && free.size() >= 2) {
            CitizenSession first = free.removeFirst();
            int job = first.agent().getJob().getId();
            boolean measuring = DuelPolicies.get().verdict(job) == DuelPolicies.Verdict.MEASURING;
            CitizenSession second = partner(first, free, measuring);
            if (second == null) {
                continue;
            }
            free.remove(second);
            if (open(first, second, measuring, now)) {
                begun++;
            }
        }
        if (begun > 0) {
            coliseum.startTicker();
        }
        return begun;
    }

    /** The free citizen on {@code first}'s channel within LEVEL_SPAN; a measuring job's, the matchup it has least of. */
    private CitizenSession partner(CitizenSession first, List<CitizenSession> free, boolean measuring) {
        Character a = first.agent();
        CitizenSession best = null;
        int fewest = Integer.MAX_VALUE;
        for (CitizenSession session : free) {
            Character b = session.agent();
            if (session.channel != first.channel || Math.abs(b.getLevel() - a.getLevel()) > LEVEL_SPAN) {
                continue;
            }
            int pairs = measuring ? DuelPolicies.get().pairsAgainst(a.getJob().getId(), DuelSelfPlay.classOf(b),
                    DuelSelfPlay.band(b)) : 0;
            if (pairs < fewest) {
                best = session;
                fewest = pairs;
            }
        }
        return best;
    }

    private boolean open(CitizenSession first, CitizenSession second, boolean measuring, long now) {
        Character a = first.agent();
        Character b = second.agent();
        DuelGround ground = DuelGround.open(a, coliseum.nextArenaId(), a.getMapId());
        if (ground == null) {
            return false;
        }
        coliseum.population().presence().hold(a.getId(), now + Coliseum.HOLD_MS);
        coliseum.population().presence().hold(b.getId(), now + Coliseum.HOLD_MS);
        DuelSelfPlay play = new DuelSelfPlay(a, b, measuring, turn++ % 2 == 0);
        ground.enter(a, Coliseum.HUMAN_SPOT);
        ground.enter(b, Coliseum.CITIZEN_SPOT);
        bouts.add(new Bout(play, ground, now + SETTLE_MS));
        log.debug("self-play: {} against {}{}", a.getName(), b.getName(), measuring ? " (measuring)" : "");
        return true;
    }

    void step(long now) {
        for (Bout bout : bouts) {
            try {
                stepOne(bout, now);
            } catch (RuntimeException failure) {
                log.warn("self-play: {} against {} failed; closing it", bout.play.a().getName(), bout.play.b().getName(), failure);
                close(bout);
            }
        }
    }

    private void stepOne(Bout bout, long now) {
        DuelSelfPlay play = bout.play;
        if (play.fighting()) {
            if (play.roundOver()) {
                bout.nextAtMs = now + SETTLE_MS;
            } else if (now >= bout.deadlineMs) {
                close(bout);
            }
            return;
        }
        if (!play.more() || out(bout, play.a()) || out(bout, play.b())) {
            close(bout);
        } else if (now >= bout.nextAtMs) {
            PvpConfig pvp = YamlConfig.config.pvp;
            bout.deadlineMs = now + (pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS) * 1000L + Coliseum.ROUND_SLACK_MS;
            if (!play.start(bout.ground.map())) {
                close(bout);
            }
        }
    }

    private static boolean out(Bout bout, Character fighter) {
        return bout.ground.left(fighter.getId()) || fighter.getMap() != bout.ground.map();
    }

    private void close(Bout bout) {
        if (!bouts.remove(bout)) {
            return;
        }
        bout.play.finish();
        bout.ground.close(null);
        coliseum.population().presence().release(bout.play.a().getId());
        coliseum.population().presence().release(bout.play.b().getId());
    }

    void stop() {
        bouts.forEach(this::close);
    }

    List<String> status() {
        List<String> lines = new ArrayList<>(List.of("Self-play: " + bouts.size() + " bout(s) under way."));
        lines.addAll(DuelPolicies.get().status());
        return lines;
    }
}
