package server.agents.capabilities.dialogue.jev;

import java.util.List;
import java.util.Locale;

/** Routing groups, not equivalent commands: the second Choice retains each action's meaning. */
public enum AgentChatIntentFamily {
    MOVEMENT("Follow the speaker, move to their position, gather the whole party around them, or stop moving/fighting and wait (not logout)",
            AgentChatIntent.FOLLOW, AgentChatIntent.MOVE_HERE, AgentChatIntent.ASSEMBLE, AgentChatIntent.STOP),
    COMBAT("Start hunting monsters: roam the map, patrol nearby platforms, or guard one spot",
            AgentChatIntent.GRIND, AgentChatIntent.PATROL, AgentChatIntent.SENTRY),
    INFORMATION("Ask for the bot's stats, inventory, skills or available commands; change nothing",
            AgentChatIntent.INFO_QUERY, AgentChatIntent.HELP),
    ASSISTANCE("Request healing/buffs, potions/ammunition for the player, or fame/reputation",
            AgentChatIntent.SUPPORT_TOGGLE, AgentChatIntent.SUPPLY_REQUEST, AgentChatIntent.FAME),
    ITEMS("Trade, drop, sell, equip or unequip items; ask about equipment upgrades for either person",
            AgentChatIntent.GEAR_FOR_SPEAKER, AgentChatIntent.GEAR_FOR_BOT, AgentChatIntent.TRADE_WINDOW,
            AgentChatIntent.TRADE_ITEMS, AgentChatIntent.DROP_ITEMS, AgentChatIntent.SELL_TRASH,
            AgentChatIntent.AUTOEQUIP, AgentChatIntent.UNEQUIP_SLOT, AgentChatIntent.UNEQUIP_ALL),
    CHARACTER("Reset ability/skill points, change build, reconnect, log out, or perform an idle animation",
            AgentChatIntent.RESPEC, AgentChatIntent.CHANGE_BUILD, AgentChatIntent.RELOG,
            AgentChatIntent.LOGOUT, AgentChatIntent.FIDGET),
    CHAT("Conversation, unsupported requests, or text aimed at someone other than the companion",
            AgentChatIntent.CHAT_NOT_A_COMMAND);

    private final String description;
    private final List<AgentChatIntent> intents;

    AgentChatIntentFamily(String description, AgentChatIntent... intents) {
        this.description = description;
        this.intents = List.of(intents);
    }

    public String optionId() { return name().toLowerCase(Locale.ROOT); }
    public String description() { return description; }
    public List<AgentChatIntent> intents() { return intents; }

    public static AgentChatIntentFamily fromOptionId(String id) {
        for (AgentChatIntentFamily family : values()) {
            if (family.optionId().equals(id)) return family;
        }
        return null;
    }
}
