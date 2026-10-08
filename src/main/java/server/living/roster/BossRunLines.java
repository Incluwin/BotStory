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
    /**
     * Off to the shop from the boss's entrance for potions, saying about when it will be back ("{n}" minutes). With
     * {@link #GO_AMMO}, the one approved exception to the no-digits rule for companion speech (P7 decision 7: the
     * owner may be signing it up meanwhile, so it says when it will be back).
     */
    static final String GO = "boss.go";
    /**
     * Off to the shop from the boss's entrance for projectiles alone ("{ammo}": stars, arrows, bolts, bullets), with
     * about when it will be back, as {@link #GO} says it.
     */
    static final String GO_AMMO = "boss.goAmmo";

    /** Every companion's lines for an intent its persona has none of its own for. */
    static final Map<String, List<String>> GENERIC = Map.ofEntries(
            Map.entry(SIGNED_UP, List.of(
                    "we're all signed up. ready when you are.",
                    "names are down. let's do this.")),
            Map.entry(REFUSED_LEVEL, List.of(
                    "mu young won't take me yet. i'll wait out here.",
                    "too low for this one still. i'll wait out here.",
                    "she says i'm not strong enough yet lol. waiting outside.")),
            Map.entry(REFUSED_CLOSED, List.of(
                    "sign-ups are closed already. i'll wait out here.",
                    "list's closed, missed it. i'll hang back.",
                    "too late to sign up now, i'll wait here.")),
            Map.entry(REFUSED_FULL, List.of(
                    "the list's full, i'll sit this one out.",
                    "no room left on the list. i'll hang out here.",
                    "list's full already. good luck in there!")),
            Map.entry(REFUSED_BANNED, List.of(
                    "guess i'm off the list. i'll wait out here.",
                    "mu young won't let me sign. i'll sit this one out.",
                    "she won't take my name... i'll wait here.")),
            Map.entry(REFUSED_LATE, List.of(
                    "not gonna make it back in time, go on without me.",
                    "won't be back in time, go ahead without me.",
                    "still too far, don't wait for me.")),
            Map.entry(REFUSED_NOT_HERE, List.of(
                    "couldn't get to mu young in time. i'll catch up after.",
                    "couldn't reach mu young, sorry. catch you after.",
                    "got stuck on the way to mu young. i'll catch up later.")),
            Map.entry(REFUSED_OTHER, List.of(
                    "couldn't get my name down, sorry. i'll wait out here.",
                    "no luck signing up. i'll hang back here.",
                    "something came up, couldn't sign up. i'll wait here.")),
            Map.entry(MISSED, List.of(
                    "couldn't make it in time, i'll wait for you here.",
                    "missed the start, sorry. i'll wait for you here.",
                    "too slow, you went in without me. i'll wait here.")),
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
                    "gonna grab potions first. back in ~{n} min.")),
            Map.entry(GO_AMMO, List.of(
                    "low on {ammo} for balrog, running to the shop. back in ~{n} min.",
                    "gonna grab {ammo} first. back in ~{n} min.")));

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
        return say(world, bot, intent, name, minutes, null);
    }

    /**
     * As {@link #say(LivingWorld, Character, String, String, int)}, and when {@code run} is known, never a wording a
     * companion of that owner's run already said for {@code intent} until it said every one (several refused or late
     * for one reason each say their own line, in their own words).
     */
    static boolean say(LivingWorld world, Character bot, String intent, String name, int minutes,
                       CompanionBossRun run) {
        return say(world, bot, intent, name, minutes, run, null);
    }

    /** As {@link #say(LivingWorld, Character, String, String, int, CompanionBossRun)}, "{ammo}" read as {@code ammo}. */
    static boolean say(LivingWorld world, Character bot, String intent, String name, int minutes,
                       CompanionBossRun run, String ammo) {
        if (bot == null) {
            return false;
        }
        List<String> lines = lines(world, world.residents().personaOfCharacterId(bot.getId()), intent);
        if (lines.isEmpty()) {
            return false;
        }
        int turn = counter.getAndIncrement();
        String wording = run == null ? lines.get(Math.floorMod(turn, lines.size())) : run.wording(intent, lines, turn);
        String line = wording
                .replace("{name}", name == null ? "" : name)
                .replace("{n}", Integer.toString(minutes));
        return world.roster().sayInParty(bot, CompanionRestockLines.withAmmo(line, ammo));
    }
}
