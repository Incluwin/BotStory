package server.living.roster;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a companion says in party chat about a boss run (P7, Mu Young's Easy Balrog), in its own lower-case player
 * voice: signed up, why it could not sign up, a missed start, going down, everyone down, seeing the run through for a
 * friend, gg, and off to the shop from the boss's entrance. A persona's arc or dialogue may have its own lines for
 * each intent ("boss.down" and so on); a hire and everyone else use the lines below. "{name}" is a human's name and
 * "{n}" whole minutes. Said on companion-ops.
 */
final class BossRunLines {
    static final String SIGNED_UP = "boss.signedup";
    static final String REFUSED_LEVEL = "boss.refused.level";
    static final String REFUSED_CLOSED = "boss.refused.closed";
    static final String REFUSED_FULL = "boss.refused.full";
    static final String REFUSED_BANNED = "boss.refused.banned";
    static final String REFUSED_LATE = "boss.refused.late";
    static final String REFUSED_NOT_HERE = "boss.refused.nothere";
    static final String REFUSED_OTHER = "boss.refused.other";
    static final String MISSED = "boss.missed";
    static final String DOWN = "boss.down";
    static final String REGROUP = "boss.regroup";
    static final String HANDOFF = "boss.handoff";
    static final String GG = "boss.gg";
    static final String GO = "boss.go";

    /** Every companion's lines for an intent its persona has none of its own for. */
    static final Map<String, List<String>> GENERIC = Map.ofEntries(
            Map.entry(SIGNED_UP, List.of(
                    "we're all signed up. ready when you are.",
                    "names are down. let's do this.")),
            Map.entry(REFUSED_LEVEL, List.of("mu young won't take me yet. i'll wait out here.")),
            Map.entry(REFUSED_CLOSED, List.of("sign-ups are closed already. i'll wait out here.")),
            Map.entry(REFUSED_FULL, List.of("the list's full, i'll sit this one out.")),
            Map.entry(REFUSED_BANNED, List.of("guess i'm off the list. i'll wait out here.")),
            Map.entry(REFUSED_LATE, List.of("not gonna make it back in time, go on without me.")),
            Map.entry(REFUSED_NOT_HERE, List.of("couldn't get to mu young in time. i'll catch up after.")),
            Map.entry(REFUSED_OTHER, List.of("couldn't get my name down, sorry. i'll wait out here.")),
            Map.entry(MISSED, List.of("couldn't make it in time, i'll wait for you here.")),
            Map.entry(DOWN, List.of(
                    "ugh, i'm down. i'll wait out here for you.",
                    "sorry, balrog got me. waiting outside.",
                    "that hurt... i'll wait out here.")),
            Map.entry(REGROUP, List.of("everyone's down... let's regroup outside.")),
            Map.entry(HANDOFF, List.of("i'll see this through with you, {name}.")),
            Map.entry(GG, List.of(
                    "gg!",
                    "gg, nice one.",
                    "we did it! gg.")),
            Map.entry(GO, List.of(
                    "low on pots for balrog, running to the shop. back in ~{n} min.",
                    "gonna grab potions first. back in ~{n} min.")));

    private static final AtomicInteger counter = new AtomicInteger();

    private BossRunLines() {
    }

    /** The intent of a companion that did not get onto the entry NPC's list for {@code refusal}. */
    static String refusal(AgentBossRunHost.Refusal refusal) {
        return switch (refusal) {
            case LEVEL -> REFUSED_LEVEL;
            case CLOSED -> REFUSED_CLOSED;
            case FULL -> REFUSED_FULL;
            case BANNED -> REFUSED_BANNED;
            case LATE -> REFUSED_LATE;
            case NOT_HERE -> REFUSED_NOT_HERE;
            case BUSY, UNKNOWN -> REFUSED_OTHER;
        };
    }

    /** The persona's own lines for {@code intent} (its arc's or its dialogue's), else the generic ones. */
    static List<String> lines(LivingWorld world, Persona persona, String intent) {
        List<String> own = persona == null ? null : world.arcs().lines(persona, intent);
        return own == null || own.isEmpty() ? GENERIC.getOrDefault(intent, List.of()) : own;
    }

    /**
     * On companion-ops: {@code bot} says one of its {@code intent} lines in its party's chat, "{name}" read as
     * {@code name} and "{n}" as {@code minutes}. False when it has no line for it or could not say it.
     */
    static boolean say(LivingWorld world, Character bot, String intent, String name, int minutes) {
        if (bot == null) {
            return false;
        }
        List<String> lines = lines(world, world.residents().personaOfCharacterId(bot.getId()), intent);
        if (lines.isEmpty()) {
            return false;
        }
        String line = lines.get(Math.floorMod(counter.getAndIncrement(), lines.size()))
                .replace("{name}", name == null ? "" : name)
                .replace("{n}", Integer.toString(minutes));
        return world.roster().sayInParty(bot, line);
    }
}
