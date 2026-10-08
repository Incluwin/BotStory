package server.living.roster;

import client.Character;
import config.AgentYamlConfig;
import server.agents.capabilities.supplies.AgentAmmoService;
import server.agents.capabilities.supplies.AgentProjectileKind;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a companion says about its own store trips (CompanionRestock, the party quest desks), in its own lower-case
 * player voice: setting off for the store, back stocked up, too broke to buy, and no shop out here at a boss's gate;
 * for potions, and in other words for a trip short of projectiles alone ("{ammo}": stars, arrows, bolts or bullets).
 * A persona's dialogue may have its own lines for each intent (restock.go, restock.back, restock.broke,
 * restock.noShop, restock.goAmmo, restock.backAmmo, restock.brokeAmmo, restock.noShopAmmo); a hire and everyone else
 * use the lines below. Said on companion-ops.
 */
final class CompanionRestockLines {
    static final String GO = "restock.go";
    static final String BACK = "restock.back";
    static final String BROKE = "restock.broke";
    static final String NO_SHOP = "restock.noShop";
    static final String GO_AMMO = "restock.goAmmo";
    static final String BACK_AMMO = "restock.backAmmo";
    static final String BROKE_AMMO = "restock.brokeAmmo";
    static final String NO_SHOP_AMMO = "restock.noShopAmmo";
    /** What a companion calls projectiles when its weapon is not known. */
    private static final String ANY_AMMO = "ammo";

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
                    "i'm low on potions and out of mesos... could you spare some coin?",
                    "can't afford potions right now. anyone spare a few mesos?",
                    "out of potions and out of mesos. little help?"),
            NO_SHOP, List.of(
                    "heads up, i'm low on potions and there's no shop out here.",
                    "wish i'd stocked up before this. go easy on me in there.",
                    "short on potions, and nowhere to buy any here. careful."),
            GO_AMMO, List.of(
                    "need more {ammo}, heading to the shop.",
                    "almost out of {ammo}, gonna restock real quick."),
            BACK_AMMO, List.of(
                    "{ammo} restocked. let's do this.",
                    "back with a full load of {ammo}."),
            BROKE_AMMO, List.of(
                    "no mesos left for {ammo}. could someone chip in?",
                    "broke and nearly out of {ammo}. mind lending me some mesos?"),
            NO_SHOP_AMMO, List.of(
                    "running thin on {ammo} and there's nowhere to restock here.",
                    "not many {ammo} left and no shop nearby. i'll make them count."));

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
     * On companion-ops: {@code bot} says one of its {@code intent} lines in its party's chat, else on its map (when
     * bots' map chat is on), else in a whisper to its owner. False when it could say it nowhere.
     */
    static boolean say(LivingWorld world, Character bot, String intent) {
        return say(world, bot, intent, null);
    }

    /** As {@link #say(LivingWorld, Character, String)}, "{ammo}" read as {@code ammoWord} (an ammo trip's lines). */
    static boolean say(LivingWorld world, Character bot, String intent, String ammoWord) {
        if (bot == null) {
            return false;
        }
        Persona persona = world.residents().personaOfCharacterId(bot.getId());
        List<String> lines = lines(world, persona, intent);
        String line = withAmmo(lines.get(Math.floorMod(counter.getAndIncrement(), lines.size())), ammoWord);
        if (world.roster().sayInParty(bot, line)) {
            return true;
        }
        if (AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED) {
            AgentDialogueTransportRuntime.sayMapNow(bot, line);
            return true;
        }
        int ownerId = persona == null ? 0 : world.roster().cachedOwnerOf(persona.id());
        Character owner = ownerId == 0 ? null : world.roster().onlineCharacter(ownerId);
        if (owner == null) {
            return false;
        }
        world.residents().whisper(persona, owner, line);
        return true;
    }
}
