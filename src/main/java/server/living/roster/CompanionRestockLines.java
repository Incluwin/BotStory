package server.living.roster;

import client.Character;
import config.AgentYamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.supplies.AgentAmmoService;
import server.agents.capabilities.supplies.AgentProjectileKind;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a companion says about its own store trips (CompanionRestock, the party quest desks), in its own lower-case
 * player voice: setting off for the store, back stocked up, too broke to buy (asking its owner for "{need}" or mesos:
 * potions, or mana potions when only those are short; user decision 2026-10-09), still short after what it was given,
 * and no shop out here at a boss's gate; for potions, and in other words for a trip short of projectiles alone
 * ("{ammo}": stars, arrows, bolts or bullets). No line carries a count. A persona's dialogue may have its own lines
 * for each intent (restock.go, restock.back, restock.broke, restock.noShop, restock.goAmmo, restock.backAmmo,
 * restock.brokeAmmo, restock.noShopAmmo, restock.stillShort); a hire and everyone else use the lines below. Said on
 * companion-ops, and logged.
 */
final class CompanionRestockLines {
    private static final Logger log = LoggerFactory.getLogger(CompanionRestockLines.class);
    static final String GO = "restock.go";
    static final String BACK = "restock.back";
    static final String BROKE = "restock.broke";
    static final String NO_SHOP = "restock.noShop";
    static final String GO_AMMO = "restock.goAmmo";
    static final String BACK_AMMO = "restock.backAmmo";
    static final String BROKE_AMMO = "restock.brokeAmmo";
    static final String NO_SHOP_AMMO = "restock.noShopAmmo";
    /**
     * After its broke line, someone handed it mesos or supplies that still do not pay for its trip
     * (AgentSupplyReadiness.Rule.stillShort): it says it is still short of "{need}". The gift's own path thanks
     * already (the trade window's "ty!", the card's coin line), so this one does not.
     */
    static final String STILL_SHORT = "restock.stillShort";
    /** What a companion calls projectiles when its weapon is not known. */
    private static final String ANY_AMMO = "ammo";
    /** What it lacks when only mana potions are short (the desk's "low on mana potions"). */
    static final String MANA_POTIONS = "mana potions";
    /** What it lacks otherwise (the desk's "low on potions"), and what "{need}" reads as when nothing is named. */
    static final String POTIONS = "potions";

    /**
     * Every companion's lines for an intent its persona has none of its own for. The go lines fit any town it restocks
     * in, a party quest's entrance or not.
     */
    static final Map<String, List<String>> GENERIC = Map.of(
            GO, List.of(
                    "brb, grabbing potions.",
                    "quick store run, back in a sec.",
                    "running low on potions, back in a sec."),
            BACK, List.of(
                    "back, all stocked up.",
                    "got my potions. ready when you are.",
                    "stocked up, let's go."),
            BROKE, List.of(
                    "i'm low on {need} and out of mesos... could you spare a few, or some coin?",
                    "can't afford {need} right now. got any to spare? mesos work too.",
                    "out of {need} and out of mesos. toss me some, or a little coin?"),
            NO_SHOP, List.of(
                    "heads up, i'm low on potions and there's no shop out here.",
                    "wish i'd stocked up before this. go easy on me in there.",
                    "short on potions, and nowhere to buy any here. careful."),
            GO_AMMO, List.of(
                    "need more {ammo}, heading to the shop.",
                    "gonna grab more {ammo} real quick."),
            BACK_AMMO, List.of(
                    "{ammo} restocked. let's do this.",
                    "picked up more {ammo}. lead the way."),
            BROKE_AMMO, List.of(
                    "no mesos left for {ammo}. could someone chip in?",
                    "can't pay for the {ammo} i need. mind lending me some mesos?"),
            NO_SHOP_AMMO, List.of(
                    "running thin on {ammo} and there's nowhere to restock here.",
                    "low on {ammo} and no shop nearby. i'll make them count."),
            STILL_SHORT, List.of(
                    "that helps, but i'm still short for my {need}.",
                    "closer! still not quite enough for the {need} though.",
                    "still a bit short on mesos for {need}."));

    private static final AtomicInteger counter = new AtomicInteger();

    private CompanionRestockLines() {
    }

    /** The persona's own lines for {@code intent} (its arc's or its dialogue's), else the generic ones. */
    static List<String> lines(LivingWorld world, Persona persona, String intent) {
        List<String> own = persona == null ? null : world.arcs().lines(persona, intent);
        return own == null || own.isEmpty() ? GENERIC.get(intent) : own;
    }

    /** The word {@code bot} uses for what its weapon shoots ("stars", ...); "ammo" when it is not known. */
    static String ammoWord(Character bot) {
        AgentProjectileKind kind = bot == null ? null : AgentAmmoService.projectileKind(bot);
        return kind == null ? ANY_AMMO : kind.word();
    }

    /** {@code line} with "{ammo}" read as {@code word} ("ammo" when null). */
    static String withAmmo(String line, String word) {
        return line.replace("{ammo}", word == null ? ANY_AMMO : word);
    }

    /**
     * {@code line} with "{ammo}" and "{need}" read as {@code word}; when null, "ammo" and "potions". A line holds one
     * of them at most: an ammo line what it shoots, a broke or still-short line what it lacks ({@link #need}).
     */
    static String filled(String line, String word) {
        return withAmmo(line, word).replace("{need}", word == null ? POTIONS : word);
    }

    /**
     * What {@code bot} says it lacks for {@code shortfall}, as the desk words it: "mana potions" when only mana
     * potions are short, what it shoots ({@link #ammoWord}) when only projectiles are, else "potions".
     */
    static String need(Character bot, AgentSupplyReadiness.Shortfall shortfall) {
        if (shortfall == null || shortfall.hp() > 0) {
            return POTIONS;
        }
        if (shortfall.mp() > 0) {
            return MANA_POTIONS;
        }
        return shortfall.ammo() > 0 ? ammoWord(bot) : POTIONS;
    }

    /**
     * On companion-ops: {@code bot} says one of its {@code intent} lines in its party's chat, else on its map (when
     * bots' map chat is on), else in a whisper to its owner. False when it could say it nowhere.
     */
    static boolean say(LivingWorld world, Character bot, String intent) {
        return say(world, bot, intent, null);
    }

    /**
     * As {@link #say(LivingWorld, Character, String)}, "{ammo}" or "{need}" read as {@code word} ({@link #filled}: an
     * ammo trip's lines, a broke or still-short line). Every line said is logged with where it went.
     */
    static boolean say(LivingWorld world, Character bot, String intent, String word) {
        if (bot == null) {
            return false;
        }
        Persona persona = world.residents().personaOfCharacterId(bot.getId());
        List<String> lines = lines(world, persona, intent);
        String line = filled(lines.get(Math.floorMod(counter.getAndIncrement(), lines.size())), word);
        String heard = heard(world, bot, persona, line);
        log.info("companion {} says {} ({}): {}", bot.getName(), intent, heard == null ? "nowhere" : heard, line);
        return heard != null;
    }

    /** Says {@code line} in its party's chat, else on its map, else to its owner; where it went, null for nowhere. */
    private static String heard(LivingWorld world, Character bot, Persona persona, String line) {
        if (world.roster().sayInParty(bot, line)) {
            return "party";
        }
        if (AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED) {
            AgentDialogueTransportRuntime.sayMapNow(bot, line);
            return "map";
        }
        int ownerId = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        Character owner = ownerId == 0 ? null : world.roster().onlineCharacter(ownerId);
        if (owner == null) {
            return null;
        }
        world.residents().whisper(persona, owner, line);
        return "whisper";
    }
}
