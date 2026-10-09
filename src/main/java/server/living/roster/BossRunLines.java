package server.living.roster;

import client.Character;
import config.AgentYamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.maps.MapleMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a companion says in party chat about a boss run (P7, Mu Young's Easy Balrog), in its own lower-case player
 * voice: signed up, nobody heals, why it could not sign up, a missed start, going down, everyone down, seeing the run
 * through for a friend, gg, and off to the shop from the boss's entrance. A persona's arc or dialogue may have its own
 * lines for each intent ("boss.down" and so on); a hire and everyone else use the lines below. "{name}" is a human's
 * name, "{n}" whole minutes, "{ammo}" a projectile word and "{eta}" a bench ally's recall ETA (RecallTravel.describe).
 * A bench ally (Part B, "boss.ally.*": on its way down, here, back to camp) leads itself in a party of bots, so it
 * tells its owner where the owner hears it ({@link #tell}). P8: a boss whose place the generic lines do not fit (Zakum:
 * Adobis, the Door to Zakum, El Nath) has its own lines for those intents ({@link #BY_BOSS}), read by the boss key a
 * caller passes or its run's. Said on companion-ops.
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
     * Decision 3 (2026-10-08): before Start, once per party, nobody of the owner's party who goes in can cast Heal
     * (CompanionBossRuns.warnIfNoHealer). A warning only: nobody stays out for it.
     */
    static final String NO_HEALER = "boss.noheal";
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
    static final String ALLY_ON_MY_WAY = "boss.ally.onmyway";
    static final String ALLY_NO_ROOM = "boss.ally.noroom";
    static final String ALLY_CANT_COME = "boss.ally.cantcome";
    static final String ALLY_HERE = "boss.ally.here";
    static final String ALLY_LOW_POTS = "boss.ally.lowpots";
    /** Still short of projectiles after buying what it could on arrival ("{ammo}": stars, arrows, bolts, bullets). */
    static final String ALLY_LOW_AMMO = "boss.ally.lowammo";
    static final String ALLY_TURNED_BACK = "boss.ally.turnedback";
    static final String ALLY_NOT_LISTED = "boss.ally.notlisted";
    static final String ALLY_CALLED_OFF = "boss.ally.calledoff";
    /**
     * Back to camp after missing the start. Most often the Start took the ally in from the room while its owner stood
     * elsewhere, and the run moved it back out; else the ally itself did not get in. So no wording says who went in.
     */
    static final String ALLY_MISSED = "boss.ally.missed";
    static final String ALLY_GOOD_FIGHT = "boss.ally.goodfight";
    static final String ALLY_NEXT_TIME = "boss.ally.nexttime";
    static final String ALLY_GOODBYE = "boss.ally.goodbye";
    /**
     * P8: no entry of the boss left today (its daily entry log, ExpeditionBossLog: Zakum), so it was not signed up; a
     * party companion says it in party chat, a bench ally whispers it. Every boss with an entry log.
     */
    static final String QUOTA = "boss.refused.quota";
    /**
     * P8: its owner left the fight before the clear, so it walks out too (AgentBossRunHost.Out.OWNER_LEFT, a boss whose
     * owner takes the party out: Zakum); one line per owner's run. Every boss with that rule.
     */
    static final String OWNER_LEFT = "boss.owner_left";
    /**
     * P8, Zakum only ({@link #BY_BOSS}): the first of the owner's party companions to go in says it once per run while
     * the owner stands on the altar carrying the Eye of Fire (the run's marker item).
     */
    static final String ZAKUM_EYE = "boss.zakum.eye";

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
            Map.entry(NO_HEALER, List.of(
                    "no heals this run? i'll be chugging pots.",
                    "nobody's healing, so watch your hp out there.",
                    "no priest with us. keep your pots handy.",
                    "no healer this time. pot early, ok?")),
            Map.entry(GO, List.of(
                    "low on pots for balrog, running to the shop. back in ~{n} min.",
                    "gonna grab potions first. back in ~{n} min.")),
            Map.entry(GO_AMMO, List.of(
                    "low on {ammo} for balrog, running to the shop. back in ~{n} min.",
                    "gonna grab {ammo} first. back in ~{n} min.")),
            Map.entry(ALLY_ON_MY_WAY, List.of(
                    "heading down to the temple. {eta}.",
                    "omw to mu young, {eta}.",
                    "coming! be there in {eta}.")),
            Map.entry(ALLY_NO_ROOM, List.of("too many of us out right now, can't make it down. sorry!")),
            Map.entry(ALLY_CANT_COME, List.of(
                    "can't get away right now, sorry.",
                    "something came up, can't make it this time.")),
            Map.entry(ALLY_HERE, List.of(
                    "made it down. signing up.",
                    "here! putting my name down.",
                    "phew, long way down. where do i sign?")),
            Map.entry(ALLY_LOW_POTS, List.of(
                    "bit low on pots, i'll make it work.",
                    "running low on pots and no shop down here. i'll manage.")),
            Map.entry(ALLY_LOW_AMMO, List.of(
                    "low on {ammo}, i'll make it last.",
                    "not many {ammo} left and no shop down here. i'll make them count.")),
            Map.entry(ALLY_TURNED_BACK, List.of(
                    "list closed before i got there. heading back to camp.",
                    "won't make it down in time, heading back. good luck!")),
            Map.entry(ALLY_NOT_LISTED, List.of(
                    "couldn't get my name down. heading back to camp.",
                    "missed the list, sorry. back to camp for me.")),
            Map.entry(ALLY_CALLED_OFF, List.of(
                    "guess we're not going. back to camp.",
                    "no fight today huh. back to camp then.")),
            Map.entry(ALLY_MISSED, List.of(
                    "missed the start. back to camp for me.",
                    "didn't make it in with you. heading back to camp.")),
            Map.entry(ALLY_GOOD_FIGHT, List.of(
                    "good fight! back to camp.",
                    "good fight. see ya around.",
                    "gg, good fight. heading back to camp.")),
            Map.entry(ALLY_NEXT_TIME, List.of(
                    "we'll get him next time. back to camp.",
                    "rough one. heading back to camp.")),
            Map.entry(ALLY_GOODBYE, List.of("heading back to camp. see ya.")),
            Map.entry(QUOTA, List.of(
                    "already went in today. i'll wait out here.",
                    "used up my run for today, i'll hang back.",
                    "can't go in again today. good luck in there!")),
            Map.entry(OWNER_LEFT, List.of(
                    "you're out? we're coming too.",
                    "heading out with you.")));

    /**
     * P8: a boss's own lines for an intent (boss key -> intent -> lines), where the generic ones name another boss or
     * its place (Mu Young, Balrog, her temple, "down here"), and the intents only that boss has. A persona's own lines
     * still come first; every intent a boss has none for keeps the generic ones.
     */
    static final Map<String, Map<String, List<String>>> BY_BOSS = Map.of(
            // slot: zakum (Adobis's room behind the Door to Zakum, the altar, El Nath)
            "zakum", Map.ofEntries(
                    Map.entry(DOWN, List.of(
                            "ugh, zakum got me. i'll wait in el nath.",
                            "i'm down... see you back in town.",
                            "that arm hit hard. waiting in el nath.")),
                    Map.entry(REFUSED_LEVEL, List.of("adobis won't take me yet. i'll wait out here.")),
                    Map.entry(REFUSED_BANNED, List.of(
                            "guess i'm off the list. i'll wait out here.",
                            "adobis won't let me sign. i'll sit this one out.")),
                    Map.entry(REFUSED_NOT_HERE, List.of("couldn't get to adobis in time. i'll catch up after.")),
                    Map.entry(GO, List.of("low on pots for zakum, grabbing some here. back in ~{n} min.")),
                    Map.entry(GO_AMMO, List.of("low on {ammo} for zakum, grabbing some here. back in ~{n} min.")),
                    Map.entry(ALLY_ON_MY_WAY, List.of(
                            "heading to the door to zakum. {eta}.",
                            "omw to adobis, {eta}.")),
                    Map.entry(ALLY_HERE, List.of(
                            "made it to the door. signing up.",
                            "here! putting my name down.")),
                    Map.entry(ALLY_LOW_POTS, List.of("bit low on pots and no shop up here. i'll manage.")),
                    Map.entry(ALLY_LOW_AMMO, List.of(
                            "not many {ammo} left and no shop up here. i'll make them count.")),
                    Map.entry(GG, List.of(
                            "gg! zakum's down.",
                            "we did it! gg.")),
                    Map.entry(ZAKUM_EYE, List.of(
                            "ready when you are. drop the eye on the altar.",
                            "we're set, drop the eye whenever."))));

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
            case BUSY, UNKNOWN, WITHDRAWN -> REFUSED_OTHER;
        };
    }

    /**
     * The persona's own lines for {@code intent} (its arc's or its dialogue's), else {@code bossKey}'s own
     * ({@link #BY_BOSS}), else the generic ones; a null boss reads the generic ones.
     */
    static List<String> lines(LivingWorld world, Persona persona, String intent, String bossKey) {
        List<String> own = persona == null ? null : world.arcs().lines(persona, intent);
        if (own != null && !own.isEmpty()) {
            return own;
        }
        Map<String, List<String>> boss = bossKey == null ? null : BY_BOSS.get(bossKey);
        List<String> theirs = boss == null ? null : boss.get(intent);
        return theirs != null ? theirs : GENERIC.getOrDefault(intent, List.of());
    }

    /**
     * On companion-ops: {@code bot} says one of its {@code intent} lines in its party's chat, "{name}" read as
     * {@code name} and "{n}" as {@code minutes}. False when it has no line for it or could not say it. The generic
     * lines: a caller that knows the boss passes its key or its run.
     */
    static boolean say(LivingWorld world, Character bot, String intent, String name, int minutes) {
        return say(world, bot, intent, name, minutes, null);
    }

    /**
     * As {@link #say(LivingWorld, Character, String, String, int)}, in {@code bossKey}'s own words where it has them
     * ({@link #BY_BOSS}), for a caller with no run at hand (a death, a shop trip from the boss's entrance).
     */
    static boolean say(LivingWorld world, String bossKey, Character bot, String intent, String name, int minutes) {
        return say(world, bossKey, bot, intent, name, minutes, null);
    }

    /** As {@link #say(LivingWorld, String, Character, String, String, int)}, "{ammo}" read as {@code ammo}. */
    static boolean say(LivingWorld world, String bossKey, Character bot, String intent, String name, int minutes,
                       String ammo) {
        return speak(world, bossKey, bot, intent, name, minutes, null, ammo);
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

    /**
     * As {@link #say(LivingWorld, Character, String, String, int, CompanionBossRun)}, "{ammo}" read as {@code ammo}; in
     * the words of {@code run}'s boss ({@link #BY_BOSS}).
     */
    static boolean say(LivingWorld world, Character bot, String intent, String name, int minutes,
                       CompanionBossRun run, String ammo) {
        return speak(world, run == null ? null : run.bossKey, bot, intent, name, minutes, run, ammo);
    }

    /** {@code bot} says one of its {@code intent} lines for {@code bossKey} in its party's chat (see {@link #say}). */
    private static boolean speak(LivingWorld world, String bossKey, Character bot, String intent, String name,
                                 int minutes, CompanionBossRun run, String ammo) {
        if (bot == null) {
            return false;
        }
        List<String> lines = lines(world, world.residents().personaOfCharacterId(bot.getId()), intent, bossKey);
        if (lines.isEmpty()) {
            return false;
        }
        return world.roster().sayInParty(bot, line(lines, intent, name, minutes, null, ammo, run));
    }

    /**
     * On companion-ops: {@code bot} says one of its {@code intent} lines where the human it came for, its anchor
     * ({@code anchorId}: its owner, or the human it sees the run through for), hears it. In its party's chat when a
     * human online shares its party (a party companion, as {@link #say}); else on its map when the anchor stands on
     * that map (the same instance) and bots' map chat is on; else in a whisper to the anchor, once per intent per
     * anchor in {@code run} (five allies down in the fight are one whisper; with no run, every time); else nowhere.
     * "{name}" reads as {@code name} and "{eta}" as {@code eta}. False when it said nothing.
     */
    static boolean tell(LivingWorld world, Character bot, String intent, String name, String eta,
                        CompanionBossRun run, int anchorId) {
        return tell(world, bot, intent, name, eta, run, anchorId, null);
    }

    /**
     * As {@link #tell(LivingWorld, Character, String, String, String, CompanionBossRun, int)}, "{ammo}" read as
     * {@code ammo} (CompanionRestockLines.withAmmo: "ammo" when null).
     */
    static boolean tell(LivingWorld world, Character bot, String intent, String name, String eta,
                        CompanionBossRun run, int anchorId, String ammo) {
        if (bot == null) {
            return false;
        }
        Persona persona = world.residents().personaOfCharacterId(bot.getId());
        List<String> lines = lines(world, persona, intent, run == null ? null : run.bossKey);
        if (lines.isEmpty()) {
            return false;
        }
        if (humanInParty(bot)) {
            return world.roster().sayInParty(bot, line(lines, intent, name, 0, eta, ammo, run));
        }
        if (anchorId == 0) {
            return false;
        }
        MapleMap map = bot.getMap();
        if (map != null && map.getCharacterById(anchorId) != null
                && AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED) {
            AgentDialogueTransportRuntime.sayMapNow(bot, line(lines, intent, name, 0, eta, ammo, run));
            return true;
        }
        Character anchor = persona == null ? null : world.roster().onlineCharacter(anchorId);
        if (anchor == null || run != null && !run.firstWhisper(anchorId, intent)) {
            return false;
        }
        world.residents().whisper(persona, anchor, line(lines, intent, name, 0, eta, ammo, run));
        return true;
    }

    /**
     * One of {@code lines} (not empty), never a wording {@code run} said for {@code intent} until it said each, with
     * "{name}", "{n}", "{eta}" and "{ammo}" (CompanionRestockLines.withAmmo) filled in.
     */
    private static String line(List<String> lines, String intent, String name, int minutes, String eta, String ammo,
                               CompanionBossRun run) {
        int turn = counter.getAndIncrement();
        String wording = run == null ? lines.get(Math.floorMod(turn, lines.size())) : run.wording(intent, lines, turn);
        String line = wording
                .replace("{name}", name == null ? "" : name)
                .replace("{n}", Integer.toString(minutes))
                .replace("{eta}", eta == null ? "" : eta);
        return CompanionRestockLines.withAmmo(line, ammo);
    }

    /** True when a human online is in {@code bot}'s party (its owner's, for a party companion; never an ally's). */
    static boolean humanInParty(Character bot) {
        Party party = bot.getParty();
        if (party == null) {
            return false;
        }
        for (PartyCharacter member : party.getMembers()) {
            if (!member.isBot() && member.isOnline()) {
                return true;
            }
        }
        return false;
    }
}
