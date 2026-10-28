package server.living.population;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Party megaphones (docs/BOT_CONCEPT_PLAN.md F5c, {@code LF_SMEGA_ENABLED}). A citizen party at a PQ lobby still short
 * {@code LF_SMEGA_AFTER_MS} after it formed has its leader megaphone the server once ("LF1M KPQ 21-30 ch2, whisper
 * Ann"): a real Super Megaphone out of its cash tab, one slot of the chatter's megaphone cap (ChatterEngine.megaphone).
 * Megaphones are cash items no NPC sells, so citizens old enough for a citizen-run party quest carry a few, bought
 * now and then (at most {@link #STOCK_CAP}); the count is real and runs out. It says "whisper me", so it goes out only
 * while players' whispers reach citizens ({@code CITIZEN_WHISPERS_ENABLED}).
 */
final class PartyMegaphones {
    private static final Logger log = LoggerFactory.getLogger(PartyMegaphones.class);
    /** A citizen never carries more megaphones than this: restocks never pile up. */
    static final int STOCK_CAP = 2;

    private final Population population;

    PartyMegaphones(Population population) {
        this.population = population;
    }

    boolean on() {
        PopulationConfig config = population.config();
        return config.LF_SMEGA_ENABLED && config.CITIZEN_WHISPERS_ENABLED;
    }

    /**
     * The forming party is short {@code need} (CitizenPartyQuests.callOver): past LF_SMEGA_AFTER_MS and below
     * LF_SMEGA_PER_PARTY megaphones sent, a leader carrying a megaphone sends one if the cap lets it; it tries again
     * next tick while the cap is full. True when one went out.
     */
    boolean shout(CitizenPartyQuests.Forming party, CitizenSession leader, int need, long nowMs) {
        PopulationConfig config = population.config();
        Character agent = leader == null ? null : leader.agent();
        if (!on() || party == null || agent == null || need <= 0 || party.megaphones >= config.LF_SMEGA_PER_PARTY
                || nowMs - party.formedAtMs < config.LF_SMEGA_AFTER_MS
                || agent.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false) <= 0) {
            return false;
        }
        ChatterEngine chatter = population.chatter();
        String line = chatter.compose(ChatterEngine.Event.of("pq.smega", leader.mapId, leader.channel).self(leader)
                .need(need).slot("ch", String.valueOf(leader.channel)));
        if (line == null || !chatter.megaphone(leader, line, true)) {
            return false;
        }
        party.megaphones++;
        leader.journal.add("social", "megaphoned for its " + party.questKey + " party");
        log.info("citizen {} megaphoned for {} more at map {} (ch{})", leader.name(), need, party.mapId, leader.channel);
        return true;
    }

    /**
     * At load (ProgressionLedger.catchUp, beside the pocket money): a citizen a citizen-run lobby of its region fits,
     * carrying no megaphone, buys LF_SMEGA_STOCK of them (never above {@link #STOCK_CAP}) with chance
     * LF_SMEGA_RESTOCK_CHANCE. Returns how many it got.
     */
    static int stock(Population population, Character agent, Citizen citizen) {
        PopulationConfig config = population.config();
        CitizenPartyQuests partyQuests = population.partyQuests();
        if (!config.LF_SMEGA_ENABLED || agent == null || agent.getClient() == null || citizen == null
                || partyQuests == null || !partyQuests.runsLobbyFor(citizen, agent.getLevel())
                || agent.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false) > 0
                || ThreadLocalRandom.current().nextDouble() >= config.LF_SMEGA_RESTOCK_CHANCE) {
            return 0;
        }
        int count = Math.min(STOCK_CAP, config.LF_SMEGA_STOCK);
        if (count <= 0 || !InventoryManipulator.addById(agent.getClient(), ChatterEngine.SUPER_MEGAPHONE, (short) count)) {
            return 0;
        }
        log.debug("citizen {} carries {} megaphones", citizen.name(), count);
        return count;
    }
}
