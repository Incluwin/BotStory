package server.living.population.casino;

import client.Character;
import server.living.population.CitizenSession;
import server.living.population.Population;

import java.util.Map;
import java.util.Random;

/** What a venue asks of the casino (CitizenCasino): the population, lines, time, and the end of a citizen's shift. */
public interface CasinoHost {
    Population population();

    /**
     * A line a citizen says for the game it runs or plays (a dealer calling a total, a player's "hit"): a line of the
     * trigger's intents in its voice, typed with its delay, past the map's line budget and its cooldowns, because the
     * table is waiting on it. False when no line of the trigger fills.
     */
    boolean say(CitizenSession session, String trigger, Map<String, ?> slots);

    /** The citizen's time at the venue is over: it plans its next activity and no CASINO plan comes for a while. */
    void done(CitizenSession session, String why);

    /** Runs {@code step} once after {@code delayMs}: a timer live, a queue in tests. */
    void after(long delayMs, Runnable step);

    /** Runs a step as the citizen: on its own Agent tick when external writes are routed there. */
    void runAs(CitizenSession session, Runnable step);

    Random random();

    /** A shift's length, drawn in CASINO_SHIFT_MIN_MS..MAX_MS. */
    long shiftMs();

    /** True while a human (not an Agent) stands on the map in that channel. */
    boolean humanOn(int channel, int mapId);

    /**
     * The citizen's character while it is logged in, else null. A post, table or seat keeps the session, and the
     * session keeps its character on its last map after it logs off: live 2026-11-08 Neshao held the bar 21 min after
     * logging off, and a dealer kept dealing to SilverStar's empty seat.
     */
    static Character online(CitizenSession session) {
        Character agent = session == null ? null : session.agent();
        return agent == null || !agent.isLoggedin() ? null : agent;
    }
}
