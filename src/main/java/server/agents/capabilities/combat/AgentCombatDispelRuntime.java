package server.agents.capabilities.combat;

import client.Character;
import client.Disease;
import client.Skill;
import constants.skills.Priest;
import server.StatEffect;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Rectangle;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-10-14 (P18): a Priest or Bishop casts Dispel while it or a party member inside Dispel's box carries a disease
 * Dispel cures, at most every DISPEL_INTERVAL_MS, as a player in a boss party does. Horntail's Right Hand seals the whole
 * altar every 25 s, and a sealed companion only basic-attacks. A sealed caster cannot cast: it drinks its own cure first
 * (AgentPotionService.tickDiseaseCure), then dispels the rest. The cast goes through the support special move, so the
 * server cures the party (StatEffect.applyBuff) exactly as for a player's Dispel.
 */
public final class AgentCombatDispelRuntime {
    /** What Character.dispelDebuffs removes. */
    static final Set<Disease> DISPELLABLE = Set.of(
            Disease.CURSE, Disease.DARKNESS, Disease.POISON, Disease.SEAL, Disease.WEAKEN, Disease.SLOW);
    private static final Map<Integer, Long> LAST_CAST = new ConcurrentHashMap<>();

    private AgentCombatDispelRuntime() {
    }

    public static boolean tickDispel(AgentRuntimeEntry entry, Character bot, AgentCombatConfig.Config config) {
        return tickDispel(entry, bot, config, AgentSkillGatewayRuntime.skills(), System.currentTimeMillis(),
                AgentCombatBuffRuntime::castSupportSkill);
    }

    static boolean tickDispel(AgentRuntimeEntry entry, Character bot, AgentCombatConfig.Config config,
                              SkillGateway skills, long nowMs, Caster caster) {
        if (entry == null || bot == null || !bot.isAlive() || bot.hasDisease(Disease.SEAL)
                || bot.getSkillLevel(Priest.DISPEL) <= 0
                || !(AgentModeStateRuntime.following(entry) || AgentModeStateRuntime.grinding(entry))
                || !AgentCombatBuffRuntime.groundedAndFree(entry)) {
            return false;
        }
        Long last = LAST_CAST.get(bot.getId());
        if (last != null && nowMs - last < config.DISPEL_INTERVAL_MS) {
            return false;
        }
        Skill skill = skills.getSkill(Priest.DISPEL);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        StatEffect effect = level <= 0 ? null : skill.getEffect(level);
        if (effect == null || !anyoneNeedsIt(bot, AgentCombatSupportPolicy.partyBuffBox(bot, effect))) {
            return false;
        }
        if (!caster.cast(entry, bot, skill, effect, nowMs)) {
            return false;
        }
        LAST_CAST.put(bot.getId(), nowMs);
        return true;
    }

    /** The caster, or a living party member on its map inside {@code box}, carries a disease Dispel cures. */
    static boolean anyoneNeedsIt(Character bot, Rectangle box) {
        if (afflicted(bot)) {
            return true;
        }
        if (box == null) {
            return false;
        }
        for (Character member : bot.getPartyMembersOnSameMap()) {
            if (member != null && member.getId() != bot.getId() && member.isAlive()
                    && member.getPosition() != null && box.contains(member.getPosition()) && afflicted(member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean afflicted(Character character) {
        for (Disease disease : DISPELLABLE) {
            if (character.hasDisease(disease)) {
                return true;
            }
        }
        return false;
    }

    /** Casts a support skill as AgentCombatBuffRuntime does; a seam for tests. */
    @FunctionalInterface
    interface Caster {
        boolean cast(AgentRuntimeEntry entry, Character bot, Skill skill, StatEffect effect, long nowMs);
    }
}
