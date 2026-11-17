package server.living.population.coliseum;

import client.Character;
import config.PvpConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenSession;
import server.living.story.DuelGround;
import server.pvp.DuelField;
import server.pvp.DuelLog;
import server.pvp.DuelRegistry;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One tournament bout fought for real (docs/PVP_PLAN.md V5): two fighters, each a player or a citizen, best of three
 * coliseum rounds on a closed arena floor, with anyone watching the tournament ringside. Side 0 is {@code a} (the better
 * seed), side 1 {@code b}. A fighter who leaves the floor mid-bout loses it; everyone goes back where they stood. The
 * tournament reads {@link #done()} and {@link #winnerSide()} on its own step: this class decides nothing about the
 * draw.
 */
final class TournamentBout {
    private static final Logger log = LoggerFactory.getLogger(TournamentBout.class);

    enum Stage { ENTERING, INTRO, FIGHTING, BETWEEN, AFTER, DONE }

    final int pairIndex;
    final int[] ids;
    final String[] names;
    final boolean[] human;
    final LadderRules.Bracket bracket;
    final String roundName;
    final int arenaId;
    final List<Integer> watchers;
    final long startedAtMs;
    Stage stage = Stage.ENTERING;
    DuelGround ground;
    DuelField field;
    volatile DuelField.Result result;
    LadderRules.Score score = LadderRules.Score.START;
    long nextAtMs;
    long deadlineMs;
    final long[] dealt = new long[2];
    final long[] taken = new long[2];
    final boolean[] ko = new boolean[2];
    private int winnerSide = -1;
    private String why = "";

    TournamentBout(int pairIndex, int[] ids, String[] names, boolean[] human, LadderRules.Bracket bracket,
                   String roundName, int arenaId, List<Integer> watchers, long now) {
        this.pairIndex = pairIndex;
        this.ids = ids.clone();
        this.names = names.clone();
        this.human = human.clone();
        this.bracket = bracket;
        this.roundName = roundName;
        this.arenaId = arenaId;
        this.watchers = new ArrayList<>(watchers);
        this.startedAtMs = now;
    }

    boolean done() {
        return stage == Stage.DONE;
    }

    /** 0 or 1 once decided; -1 for a draw (the better seed goes on) or a bout called off. */
    int winnerSide() {
        return winnerSide;
    }

    String why() {
        return why;
    }

    boolean has(int characterId) {
        return ids[0] == characterId || ids[1] == characterId;
    }

    boolean watchedBy(int characterId) {
        return watchers.contains(characterId);
    }

    // ---- the step -------------------------------------------------------------------------------------------------

    void step(Coliseum coliseum, long now) {
        if (stage == Stage.DONE) {
            return;
        }
        if (stage == Stage.AFTER) {
            if (now >= nextAtMs) {
                close(coliseum);
            }
            return;
        }
        Character a = fighter(coliseum, 0);
        Character b = fighter(coliseum, 1);
        if (a == null || b == null) {
            int gone = a == null ? 0 : 1;
            forfeit(coliseum, gone, names[gone] + (human[gone] ? " logged out" : " is gone"), now);
            return;
        }
        switch (stage) {
            case ENTERING -> enter(coliseum, a, b, now);
            case INTRO, BETWEEN -> {
                if (out(a) || out(b)) {
                    int gone = out(a) ? 0 : 1;
                    forfeit(coliseum, gone, names[gone] + " left the arena", now);
                } else if (now >= nextAtMs) {
                    round(coliseum, a, b, now);
                }
            }
            case FIGHTING -> fighting(coliseum, now);
            default -> { }
        }
    }

    private boolean out(Character fighter) {
        return ground == null || ground.left(fighter.getId()) || fighter.getMap() != ground.map();
    }

    private Character fighter(Coliseum coliseum, int side) {
        if (human[side]) {
            return Coliseum.online(ids[side]);
        }
        CitizenSession session = coliseum.population().presence().session(ids[side]);
        return session == null ? null : session.agent();
    }

    /** Whoever opens the floor: a seated player, else a watcher (a closed floor needs someone's channel). */
    private Character owner(Coliseum coliseum, Character a, Character b) {
        if (human[0]) {
            return a;
        }
        if (human[1]) {
            return b;
        }
        for (int watcherId : watchers) {
            Character watcher = Coliseum.online(watcherId);
            if (watcher != null && watcher.getClient() != null && watcher.isAlive()) {
                return watcher;
            }
        }
        return null;
    }

    private void enter(Coliseum coliseum, Character a, Character b, long now) {
        Character owner = owner(coliseum, a, b);
        if (owner == null) {
            callOff(coliseum, "nobody is watching");
            return;
        }
        DuelGround opened = DuelGround.open(owner, arenaId, Coliseum.ARIANT);
        if (opened == null) {
            callOff(coliseum, "no arena floor is free");
            return;
        }
        ground = opened;
        ground.enter(a, Coliseum.HUMAN_SPOT);
        ground.enter(b, Coliseum.CITIZEN_SPOT);
        for (int watcherId : new ArrayList<>(watchers)) {
            Character watcher = Coliseum.online(watcherId);
            if (watcher != null && watcher.isAlive() && watcher.getEventInstance() == null && !has(watcherId)
                    && !DuelRegistry.inDuel(watcherId)) {
                ground.enter(watcher, Gallery.GALLERY_SPOT);
            } else {
                watchers.remove((Integer) watcherId);
            }
        }
        tellAll(coliseum, "Tournament " + roundName + ": " + names[0] + " against " + names[1] + ", best of "
                + LadderRules.ROUNDS + ".");
        for (int side = 0; side < 2; side++) {
            CitizenSession session = citizen(coliseum, side);
            if (session != null) {
                coliseum.say(session, "tournament.intro", names[1 - side], Map.of("round", roundName));
                break; // one line before round one is enough
            }
        }
        stage = Stage.INTRO;
        nextAtMs = now + Coliseum.INTRO_MS;
    }

    private void round(Coliseum coliseum, Character a, Character b, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        DuelField.Rules rules = new DuelField.Rules(pvp.COLISEUM_ROUND_SECONDS, pvp.COLISEUM_TONICS,
                pvp.COLISEUM_TONIC_PERCENT, pvp.COLISEUM_TONIC_GAP_MS);
        try {
            field = DuelRegistry.start(ground.map(), List.of(a), List.of(b), rules);
            result = null;
            field.onEnd(ended -> result = ended);
        } catch (IllegalStateException refused) {
            log.warn("Tournament: round {} of {} against {} would not start: {}", score.played() + 1, names[0],
                    names[1], refused.getMessage());
            callOff(coliseum, "the round would not start");
            return;
        }
        stage = Stage.FIGHTING;
        deadlineMs = now + (pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS) * 1000L + Coliseum.ROUND_SLACK_MS;
        for (int watcherId : watchers) {
            Character watcher = Coliseum.online(watcherId);
            if (watcher != null) {
                watcher.sendPacket(PacketCreator.getClock(pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS));
            }
        }
        tellAll(coliseum, "Round " + (score.played() + 1) + " of " + LadderRules.ROUNDS + ": " + names[0] + " against "
                + names[1] + ". Score " + score + ".");
    }

    private void fighting(Coliseum coliseum, long now) {
        DuelField.Result ended = result;
        if (ended == null) {
            if (now >= deadlineMs && field != null) {
                field.stop(); // the result comes back through onEnd
            }
            return;
        }
        int left = -1;
        for (DuelLog.Row row : ended.rows()) {
            int side = row.charId() == ids[0] ? 0 : row.charId() == ids[1] ? 1 : -1;
            if (side < 0) {
                continue;
            }
            dealt[side] += row.dealt();
            taken[side] += row.taken();
            ko[side] |= row.ko();
            if (row.left()) {
                left = side;
            }
        }
        if (left >= 0) {
            forfeit(coliseum, left, names[left] + " left the arena", now);
            return;
        }
        score = score.after(ended.winnerSide());
        int played = score.played();
        tellAll(coliseum, (ended.winnerSide() == 0 ? names[0] + " takes round " + played
                : ended.winnerSide() == 1 ? names[1] + " takes round " + played
                : "Round " + played + " is a draw") + ". Score " + score + ".");
        if (score.over()) {
            finish(coliseum, score.winner(), "best of three " + score, now);
            return;
        }
        if (ended.winnerSide() >= 0) {
            CitizenSession winner = citizen(coliseum, ended.winnerSide());
            if (winner != null) {
                coliseum.say(winner, "ladder.round.won", names[1 - ended.winnerSide()], Map.of());
            }
        }
        stage = Stage.BETWEEN;
        nextAtMs = now + Coliseum.BETWEEN_ROUNDS_MS;
    }

    /** Side {@code gone} is out of the bout: the other goes through. */
    void forfeit(Coliseum coliseum, int gone, String reason, long now) {
        if (field != null && result == null) {
            field.stop();
        }
        log.info("Tournament: {} against {}: {} (a forfeit)", names[0], names[1], reason);
        finish(coliseum, 1 - gone, "forfeit: " + reason, now);
    }

    private void finish(Coliseum coliseum, int side, String reason, long now) {
        winnerSide = side;
        why = reason;
        if (side >= 0) {
            tellAll(coliseum, names[side] + " beats " + names[1 - side] + " (" + (reason.startsWith("forfeit")
                    ? "a forfeit" : score.toString()) + ") and goes through.");
            CitizenSession winner = citizen(coliseum, side);
            if (winner != null && !reason.startsWith("forfeit")) {
                coliseum.say(winner, "tournament.win", names[1 - side], Map.of("round", roundName));
            }
        } else {
            tellAll(coliseum, names[0] + " and " + names[1] + " draw (" + score + "); the better seed, " + names[0]
                    + ", goes through.");
        }
        stage = Stage.AFTER;
        nextAtMs = reason.startsWith("forfeit") ? now : now + Coliseum.AFTER_MS;
    }

    /** Called off with no result (no floor, a round that would not start, the coliseum closing). */
    void callOff(Coliseum coliseum, String reason) {
        if (field != null && result == null) {
            field.stop();
        }
        winnerSide = -1;
        why = "called off: " + reason;
        log.info("Tournament: {} against {} called off: {}", names[0], names[1], reason);
        close(coliseum);
    }

    private void close(Coliseum coliseum) {
        if (ground != null) {
            ground.close(null);
            ground = null;
        }
        stage = Stage.DONE;
    }

    private CitizenSession citizen(Coliseum coliseum, int side) {
        return human[side] ? null : coliseum.population().presence().session(ids[side]);
    }

    /** The players in the bout and the watchers hear it. */
    private void tellAll(Coliseum coliseum, String line) {
        for (int side = 0; side < 2; side++) {
            if (human[side]) {
                Character chr = Coliseum.online(ids[side]);
                if (chr != null) {
                    chr.dropMessage(5, line);
                }
            }
        }
        for (int watcherId : watchers) {
            Character watcher = Coliseum.online(watcherId);
            if (watcher != null) {
                watcher.dropMessage(5, line);
            }
        }
    }

    String status() {
        return names[0] + " v " + names[1] + " (" + roundName + ", " + stage + ", " + score
                + (watchers.isEmpty() ? "" : ", " + watchers.size() + " watching") + ")";
    }
}
