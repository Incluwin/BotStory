package server.living.population.coliseum;

import client.Character;
import config.PvpConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenSession;
import server.living.story.DuelGround;
import server.pvp.DuelField;
import server.pvp.DuelRegistry;
import server.pvp.Glicko2;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Watching from the gallery (docs/PVP_PLAN.md V4.4). Cesar puts two free citizens of a bracket, the best rated one and
 * its nearest-rated foe, in a real best of three on a closed arena floor, and the watcher ringside at the floor's
 * right end. That is the watched-citizen rule: a citizen ladder match is fought for real only while a human
 * watches it; the rest stay simulated ({@link Coliseum#simulate}). A watched result counts on the ladder like any
 * other. A watcher who leaves the floor or logs out calls the bout off, unrated, and everyone goes back where they
 * stood.
 */
final class Gallery {
    private static final Logger log = LoggerFactory.getLogger(Gallery.class);
    /**
     * Ringside: the arena floor's right end (y 275 runs x -990 to 360 on all three), level with the fighters. The client
     * draws its own character about three quarters down the screen, so a seat on a platform above the fight put the
     * fighters under the chat bar (live check, 2026-11-16); the floor fits the screen's width, so the whole fight shows.
     */
    static final Point GALLERY_SPOT = new Point(250, 260);

    enum Stage { ENTERING, INTRO, FIGHTING, BETWEEN, AFTER }

    /** One watched bout: side 0 is {@code a}, side 1 is {@code b}. */
    static final class Bout {
        final int watcherId;
        final String watcherName;
        final int aId;
        final int bId;
        final String aName;
        final String bName;
        final LadderRules.Bracket bracket;
        final double odds;
        final int arenaId;
        Stage stage = Stage.ENTERING;
        DuelGround ground;
        DuelField field;
        volatile DuelField.Result result;
        LadderRules.Score score = LadderRules.Score.START;
        long nextAtMs;
        long deadlineMs;

        Bout(Character watcher, Character a, Character b, LadderRules.Bracket bracket, double odds, int arenaId) {
            this.watcherId = watcher.getId();
            this.watcherName = watcher.getName();
            this.aId = a.getId();
            this.bId = b.getId();
            this.aName = a.getName();
            this.bName = b.getName();
            this.bracket = bracket;
            this.odds = odds;
            this.arenaId = arenaId;
        }

        boolean has(int characterId) {
            return characterId == aId || characterId == bId;
        }
    }

    /** Someone asking to watch; a null bracket means "theirs, else the busiest". */
    private record Request(int watcherId, LadderRules.Bracket bracket, boolean forced) { }

    private final Coliseum coliseum;
    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Bout> byWatcher = new ConcurrentHashMap<>();

    Gallery(Coliseum coliseum) {
        this.coliseum = coliseum;
    }

    void request(Character watcher, LadderRules.Bracket bracket, boolean forced) {
        if (watcher != null) {
            requests.add(new Request(watcher.getId(), bracket, forced));
        }
    }

    boolean idle() {
        return requests.isEmpty() && byWatcher.isEmpty();
    }

    boolean watching(int characterId) {
        return byWatcher.containsKey(characterId);
    }

    /** A citizen fighting in a watched bout. */
    boolean fighting(int citizenId) {
        for (Bout bout : byWatcher.values()) {
            if (bout.has(citizenId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pair to watch among {@code candidates} of one bracket: the best rated who has a foe within the level window,
     * with that foe ({@link LadderRules#pick}). Returns {a, b} ids, a the better rated.
     */
    static Optional<int[]> pair(List<LadderRules.Candidate> candidates) {
        List<LadderRules.Candidate> best = new ArrayList<>(candidates);
        best.sort(Comparator.<LadderRules.Candidate>comparingDouble(c -> -c.rating().rating())
                .thenComparingInt(LadderRules.Candidate::characterId));
        for (LadderRules.Candidate a : best) {
            List<LadderRules.Candidate> others = new ArrayList<>(candidates);
            others.remove(a);
            Optional<LadderRules.Candidate> b = LadderRules.pick(a.level(), a.rating(), others);
            if (b.isPresent()) {
                return Optional.of(new int[] {a.characterId(), b.get().characterId()});
            }
        }
        return Optional.empty();
    }

    // ---- the step -------------------------------------------------------------------------------------------------

    void step(long now) {
        for (Request request; (request = requests.poll()) != null; ) {
            try {
                begin(request, now);
            } catch (RuntimeException failure) {
                log.warn("Gallery: starting a bout for {} failed", request.watcherId(), failure);
            }
        }
        for (Bout bout : new ArrayList<>(byWatcher.values())) {
            try {
                stepOne(bout, now);
            } catch (RuntimeException failure) {
                log.warn("Gallery: {} against {} failed; closing it", bout.aName, bout.bName, failure);
                callOff(bout, "something went wrong");
            }
        }
    }

    private void begin(Request request, long now) {
        Character watcher = Coliseum.online(request.watcherId());
        if (watcher == null || !Coliseum.on() && !request.forced()) {
            return;
        }
        if (byWatcher.containsKey(watcher.getId()) || coliseum.inMatch(watcher.getId())
                || DuelRegistry.inDuel(watcher.getId())) {
            watcher.dropMessage(5, "Cesar: one thing at a time. You are in a match or watching one already.");
            return;
        }
        if (!watcher.isAlive() || watcher.getEventInstance() != null || watcher.getClient() == null) {
            watcher.dropMessage(5, "Cesar: not from inside an event.");
            return;
        }
        int channel = watcher.getClient().getChannel();
        Map<LadderRules.Bracket, List<CitizenSession>> free = new EnumMap<>(LadderRules.Bracket.class);
        for (CitizenSession session : coliseum.population().presence().sessions()) {
            if (coliseum.free(session, channel, watcher.getId())) {
                LadderRules.Bracket.of(session.agent().getLevel())
                        .ifPresent(b -> free.computeIfAbsent(b, k -> new ArrayList<>()).add(session));
            }
        }
        LadderRules.Bracket bracket = request.bracket() != null ? request.bracket()
                : LadderRules.Bracket.of(watcher.getLevel()).filter(b -> free.getOrDefault(b, List.of()).size() >= 2)
                .orElseGet(() -> free.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().size()))
                        .map(Map.Entry::getKey).orElse(LadderRules.Bracket.VETERAN));
        List<CitizenSession> pool = free.getOrDefault(bracket, List.of());
        Map<Integer, CitizenSession> byId = new ConcurrentHashMap<>();
        List<LadderRules.Candidate> candidates = new ArrayList<>();
        LadderBook book = coliseum.book();
        for (CitizenSession session : pool) {
            byId.put(session.characterId, session);
            candidates.add(new LadderRules.Candidate(session.characterId, session.agent().getLevel(),
                    book.rating(session.characterId, bracket, now)));
        }
        Optional<int[]> pair = pair(candidates);
        if (pair.isEmpty()) {
            watcher.dropMessage(5, "Cesar: no two " + bracket.label + " fighters near each other are free on this"
                    + " channel right now. Try again in a minute.");
            return;
        }
        Character a = byId.get(pair.get()[0]).agent();
        Character b = byId.get(pair.get()[1]).agent();
        Glicko2.Rating ratingA = book.rating(a.getId(), bracket, now);
        Glicko2.Rating ratingB = book.rating(b.getId(), bracket, now);
        Bout bout = new Bout(watcher, a, b, bracket, Glicko2.winChance(ratingA, ratingB), coliseum.nextArenaId());
        coliseum.population().presence().hold(a.getId(), now + Coliseum.HOLD_MS);
        coliseum.population().presence().hold(b.getId(), now + Coliseum.HOLD_MS);
        byWatcher.put(watcher.getId(), bout);
        watcher.dropMessage(5, "Cesar: from the gallery, " + a.getName() + " (Lv " + a.getLevel() + " "
                + Coliseum.jobName(a) + ", " + Math.round(ratingA.rating()) + ") against " + b.getName() + " (Lv "
                + b.getLevel() + " " + Coliseum.jobName(b) + ", " + Math.round(ratingB.rating()) + "). To your seat!");
        log.info("Gallery: {} watches {} ({}) against {} ({}) in {}, odds {}{}", watcher.getName(), a.getName(),
                Math.round(ratingA.rating()), b.getName(), Math.round(ratingB.rating()), bracket,
                String.format(Locale.ROOT, "%.2f", bout.odds), request.forced() ? " (GM)" : "");
    }

    private void stepOne(Bout bout, long now) {
        if (bout.stage == Stage.AFTER) {
            if (now >= bout.nextAtMs) {
                close(bout);
            }
            return;
        }
        Character watcher = Coliseum.online(bout.watcherId);
        Character a = agent(bout.aId);
        Character b = agent(bout.bId);
        if (a == null || b == null) {
            callOff(bout, (a == null ? bout.aName : bout.bName) + " is gone");
            return;
        }
        if (watcher == null) {
            callOff(bout, bout.watcherName + " logged out");
            return;
        }
        if (bout.stage != Stage.ENTERING && (bout.ground.left(watcher.getId())
                || watcher.getMap() != bout.ground.map())) {
            callOff(bout, "nobody is watching");
            return;
        }
        switch (bout.stage) {
            case ENTERING -> enter(bout, watcher, a, b, now);
            case INTRO, BETWEEN -> {
                if (out(bout, a) || out(bout, b)) {
                    callOff(bout, (out(bout, a) ? bout.aName : bout.bName) + " left the arena");
                } else if (now >= bout.nextAtMs) {
                    round(bout, watcher, a, b, now);
                }
            }
            case FIGHTING -> fighting(bout, watcher, now);
            case AFTER -> { } // handled above
        }
    }

    private static boolean out(Bout bout, Character fighter) {
        return bout.ground.left(fighter.getId()) || fighter.getMap() != bout.ground.map();
    }

    private void enter(Bout bout, Character watcher, Character a, Character b, long now) {
        if (!watcher.isAlive() || watcher.getEventInstance() != null || watcher.getClient() == null) {
            callOff(bout, watcher.getName() + " cannot come in right now");
            return;
        }
        DuelGround ground = DuelGround.open(watcher, bout.arenaId, Coliseum.ARIANT);
        if (ground == null) {
            callOff(bout, "no arena floor is free");
            return;
        }
        bout.ground = ground;
        ground.enter(watcher, GALLERY_SPOT);
        ground.enter(a, Coliseum.HUMAN_SPOT);
        ground.enter(b, Coliseum.CITIZEN_SPOT);
        LadderBook book = coliseum.book();
        LadderBook.Row row = book.row(a.getId(), bout.bracket);
        CitizenSession session = coliseum.population().presence().session(a.getId());
        if (session != null) {
            coliseum.say(session, row.played() == 0 ? "ladder.intro.new" : "ladder.intro", bout.bName, Map.of(
                    "record", row.wins() + "-" + row.losses(),
                    "rating", Long.toString(Math.round(book.rating(a.getId(), bout.bracket, now).rating())),
                    "bracket", bout.bracket.label.toLowerCase(Locale.ROOT)));
        }
        bout.stage = Stage.INTRO;
        bout.nextAtMs = now + Coliseum.INTRO_MS;
    }

    private void round(Bout bout, Character watcher, Character a, Character b, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        DuelField.Rules rules = new DuelField.Rules(pvp.COLISEUM_ROUND_SECONDS, pvp.COLISEUM_TONICS,
                pvp.COLISEUM_TONIC_PERCENT, pvp.COLISEUM_TONIC_GAP_MS);
        try {
            DuelField field = DuelRegistry.start(bout.ground.map(), List.of(a), List.of(b), rules);
            bout.field = field;
            bout.result = null;
            field.onEnd(result -> bout.result = result);
        } catch (IllegalStateException refused) {
            log.warn("Gallery: round {} of {} against {} would not start: {}", bout.score.played() + 1, bout.aName,
                    bout.bName, refused.getMessage());
            callOff(bout, "the round would not start");
            return;
        }
        bout.stage = Stage.FIGHTING;
        bout.deadlineMs = now + (pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS) * 1000L
                + Coliseum.ROUND_SLACK_MS;
        // the duel shows its round clock to its fighters only; the watcher kept the stock 10-minute arena clock
        // (live check 2026-11-16). This one runs out when the round would: the 3-2-1 count plus the round.
        watcher.sendPacket(PacketCreator.getClock(pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS));
        watcher.dropMessage(5, "Round " + (bout.score.played() + 1) + " of " + LadderRules.ROUNDS + ": " + bout.aName
                + " against " + bout.bName + ". Score " + bout.score + ".");
    }

    private void fighting(Bout bout, Character watcher, long now) {
        DuelField.Result result = bout.result;
        if (result == null) {
            if (now >= bout.deadlineMs && bout.field != null) {
                bout.field.stop(); // the result comes back through onEnd
            }
            return;
        }
        bout.score = bout.score.after(result.winnerSide());
        int played = bout.score.played();
        watcher.dropMessage(5, (result.winnerSide() == 0 ? bout.aName + " takes round " + played
                : result.winnerSide() == 1 ? bout.bName + " takes round " + played
                : "Round " + played + " is a draw") + ". Score " + bout.score + ".");
        if (bout.score.over()) {
            finish(bout, watcher, bout.score.winner(), now);
            return;
        }
        if (result.winnerSide() >= 0) {
            boolean aWon = result.winnerSide() == 0;
            CitizenSession winner = coliseum.population().presence().session(aWon ? bout.aId : bout.bId);
            if (winner != null) {
                coliseum.say(winner, "ladder.round.won", aWon ? bout.bName : bout.aName, Map.of());
            }
        }
        bout.stage = Stage.BETWEEN;
        bout.nextAtMs = now + Coliseum.BETWEEN_ROUNDS_MS;
    }

    /** Decided: both ratings move, the watcher hears the result, the two have their say; back {@code AFTER_MS} later. */
    private void finish(Bout bout, Character watcher, int winnerSide, long now) {
        double scoreA = winnerSide == 0 ? 1d : winnerSide == 1 ? 0d : 0.5d;
        LadderBook.Change[] changes = coliseum.book().record(bout.aId, bout.bId, bout.bracket, scoreA, now);
        coliseum.matched(bout.aId, bout.bId, winnerSide);
        String verdict = winnerSide == 0 ? bout.aName + " beat " + bout.bName
                : winnerSide == 1 ? bout.bName + " beat " + bout.aName : bout.aName + " and " + bout.bName + " drew";
        watcher.dropMessage(5, "Ladder: " + verdict + " (" + bout.score + "). " + bout.aName + " "
                + Math.round(changes[0].before().rating().rating()) + " -> "
                + Math.round(changes[0].after().rating().rating()) + ", " + bout.bName + " "
                + Math.round(changes[1].before().rating().rating()) + " -> "
                + Math.round(changes[1].after().rating().rating()) + ".");
        if (winnerSide >= 0) {
            boolean aWon = winnerSide == 0;
            CitizenSession winner = coliseum.population().presence().session(aWon ? bout.aId : bout.bId);
            CitizenSession loser = coliseum.population().presence().session(aWon ? bout.bId : bout.aId);
            LadderBook.Row won = changes[aWon ? 0 : 1].after();
            LadderBook.Row lost = changes[aWon ? 1 : 0].after();
            if (winner != null) {
                coliseum.say(winner, "ladder.win", aWon ? bout.bName : bout.aName,
                        Map.of("record", won.wins() + "-" + won.losses()));
            }
            if (loser != null) {
                coliseum.say(loser, "ladder.lose", aWon ? bout.aName : bout.bName,
                        Map.of("record", lost.wins() + "-" + lost.losses()));
            }
        }
        log.info("Gallery: {} against {} ended {} ({}), watched by {}: {} {} -> {}, {} {} -> {}", bout.aName,
                bout.bName, winnerSide, bout.score, bout.watcherName, bout.aName,
                Math.round(changes[0].before().rating().rating()), Math.round(changes[0].after().rating().rating()),
                bout.bName, Math.round(changes[1].before().rating().rating()),
                Math.round(changes[1].after().rating().rating()));
        bout.stage = Stage.AFTER;
        bout.nextAtMs = now + Coliseum.AFTER_MS;
    }

    /** Called off, unrated: the round stops and everyone goes back. */
    void callOff(Bout bout, String why) {
        if (bout.field != null && bout.result == null) {
            bout.field.stop();
        }
        Character watcher = Coliseum.online(bout.watcherId);
        if (watcher != null) {
            watcher.dropMessage(5, "Cesar: the bout is off (" + why + "). Nobody's rating moves.");
        }
        log.info("Gallery: {} against {} called off: {}", bout.aName, bout.bName, why);
        close(bout);
    }

    private void close(Bout bout) {
        byWatcher.remove(bout.watcherId, bout);
        if (bout.ground != null) {
            bout.ground.close(null);
        }
        coliseum.population().presence().release(bout.aId);
        coliseum.population().presence().release(bout.bId);
    }

    void stopAll(String why) {
        for (Bout bout : new ArrayList<>(byWatcher.values())) {
            callOff(bout, why);
        }
        requests.clear();
    }

    List<String> status() {
        List<String> lines = new ArrayList<>();
        for (Bout bout : byWatcher.values()) {
            lines.add("watched by " + bout.watcherName + ": " + bout.aName + " v " + bout.bName + " ("
                    + bout.bracket.label + ", " + bout.stage + ", " + bout.score + ")");
        }
        return lines;
    }

    private Character agent(int characterId) {
        CitizenSession session = coliseum.population().presence().session(characterId);
        return session == null ? null : session.agent();
    }
}
