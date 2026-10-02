package server.knowledge;

import client.Character;
import config.YamlConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server-rule facts a bot may state (docs/SERVER_KNOWLEDGE_PLAN.md section 10), filled from their live
 * sources at answer time: rates from {@code World} (GM commands and MapleEdit change them), the leech window
 * and apprentice bonus from config.yaml, the rest mirrored from the code that enforces them. Low-risk facts
 * may be offered unprompted; rates, quest EXP and the gear rule only when asked. Never stated: dead or
 * switched-off settings, GM tools, internals, and the words "server" or "config" in a reply.
 */
final class ServerRules {
    /** ExpAction: quest EXP pays six times the listed amount, three times below this level. */
    private static final int QUEST_EXP_LOW_LEVEL = 10;
    private static final int QUEST_EXP = 6;
    private static final int QUEST_EXP_LOW = 3;
    /** Character.getExpRate: monster EXP is held to this multiplier below level 30. */
    private static final int EXP_CAP = 5;
    private static final int EXP_CAP_LEVEL = 30;
    private static final int PERCENT = 100;

    private ServerRules() {
    }

    /** {@code prompted}: the asker asked about rules directly, so the "only when asked" facts are included too. */
    static Map<String, String> facts(ContentAnswers.Rates rates, boolean prompted) {
        Map<String, String> out = new LinkedHashMap<>();
        if (prompted) {
            out.put("content.rules.rates", "monster exp is " + rates.exp() + "x (held to " + Math.min(rates.exp(), EXP_CAP)
                    + "x below level " + EXP_CAP_LEVEL + "), mesos " + rates.meso() + "x, drops " + rates.drop() + "x, boss drops "
                    + rates.bossDrop() + "x");
            out.put("content.rules.quest_exp", "quest exp rewards pay " + QUEST_EXP + "x the listed amount (" + QUEST_EXP_LOW
                    + "x below level " + QUEST_EXP_LOW_LEVEL + ")");
            out.put("content.rules.gear", "any class can wear any gear; only level, your own main stat and fame count "
                    + "(pirates and beginners: level only); the client may still show red text");
        }
        out.put("content.rules.spawns", "maps hold about " + trim(rates.mobRate()) + "x the usual monsters, up to "
                + rates.perSpawnPoint() + " per spawn spot, and refill on their own");
        out.put("content.rules.party", "party exp only reaches members within " + YamlConfig.config.server.EXP_SPLIT_LEVEL_INTERVAL
                + " levels of the monster or of someone who hit it");
        out.put("content.rules.bags", "every bag tab is fully expanded; pets never expire");
        out.put("content.rules.rock", rock());
        out.put("content.rules.pitch", pitch());
        out.put("content.rules.scrolls", "chaos scroll 60% and white scrolls drop from anything, very rarely");
        out.put("content.rules.equip_levels", "gear from level 10 up gains item levels (to 30); the hidden appraiser by the "
                + "free market reads it");
        out.put("content.rules.gacha", "gachapon is 90/8/2 common/uncommon/rare, and every machine shares the rare prizes");
        out.put("content.rules.apprentice", "a recruit 2+ levels below you in your party gets +"
                + Math.round(YamlConfig.config.living_world.APPRENTICE_BONUS_PER_LEVEL * PERCENT) + "% exp per level of gap, up to +"
                + Math.round(YamlConfig.config.living_world.APPRENTICE_BONUS_PER_LEVEL * YamlConfig.config.living_world.APPRENTICE_MAX_LEVELS
                * PERCENT) + "%");
        return out;
    }

    static String rock() {
        return "at level " + Character.HYPER_TELEPORT_ROCK_GRANT_LEVEL + " everyone gets a hyper teleport rock: permanent, "
                + "10 saved maps, crosses continents";
    }

    static String pitch() {
        return "perfect pitch drops from anything; party quests and bosses hand out more; inkwell trades it";
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }
}
