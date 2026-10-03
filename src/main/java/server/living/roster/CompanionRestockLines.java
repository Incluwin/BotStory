package server.living.roster;

import client.Character;
import config.AgentYamlConfig;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a companion says about its own potion trips (CompanionRestock, the Lakelis desk), in its own lower-case player
 * voice: setting off for the store, back stocked up, too broke to buy, and no shop out here at a boss's gate. A
 * persona's dialogue may have its own lines for each intent (restock.go, restock.back, restock.broke, restock.noShop);
 * a hire and everyone else use the lines below. Said on companion-ops.
 */
final class CompanionRestockLines {
    static final String GO = "restock.go";
    static final String BACK = "restock.back";
    static final String BROKE = "restock.broke";
    static final String NO_SHOP = "restock.noShop";

    /** Every companion's lines for an intent its persona has none of its own for. */
    static final Map<String, List<String>> GENERIC = Map.of(
            GO, List.of(
                    "brb, grabbing potions before we go in.",
                    "quick store run for potions, don't start without me.",
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
                    "short on potions, and nowhere to buy any here. careful."));

    private static final AtomicInteger counter = new AtomicInteger();

    private CompanionRestockLines() {
    }

    /** The persona's own lines for {@code intent} (its arc's or its dialogue's), else the generic ones. */
    static List<String> lines(LivingWorld world, Persona persona, String intent) {
        List<String> own = persona == null ? null : world.arcs().lines(persona, intent);
        return own == null || own.isEmpty() ? GENERIC.get(intent) : own;
    }

    /**
     * On companion-ops: {@code bot} says one of its {@code intent} lines in its party's chat, else on its map (when
     * bots' map chat is on), else in a whisper to its owner. False when it could say it nowhere.
     */
    static boolean say(LivingWorld world, Character bot, String intent) {
        if (bot == null) {
            return false;
        }
        Persona persona = world.residents().personaOfCharacterId(bot.getId());
        List<String> lines = lines(world, persona, intent);
        String line = lines.get(Math.floorMod(counter.getAndIncrement(), lines.size()));
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
