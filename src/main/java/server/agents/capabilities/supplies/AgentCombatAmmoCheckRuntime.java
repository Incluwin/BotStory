package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import server.StatEffect;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.capabilities.combat.AgentCombatAmmoPolicy;
import server.agents.capabilities.combat.AgentCombatRuntime;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * The Agent's own look at its projectiles (and a mage's MP potions) on its tick: it warns once when low, and with
 * none it stops shooting; grinding, it heads back to whom it follows. Inside a companion run (a party quest a host
 * adopted, or a boss run) a member that runs dry keeps its post and says once that it ran out, in its own words,
 * unless the run says it itself (AgentPartyQuestRuntime.ownsDryLine); any projectile it gets back lets it shoot again
 * at once. That news is said again only after a run ends (the next run hears it too) or once its bag holds
 * AMMO_LOW_WARN again; Soul Arrow's endless count is no refill.
 */
public final class AgentCombatAmmoCheckRuntime {
    private AgentCombatAmmoCheckRuntime() {
    }

    public static void tickAmmoCheck(AgentRuntimeEntry entry, Character bot, int ammoLowWarn, int potLowWarn) {
        WeaponType weaponType = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean mage = weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF;
        boolean rangedAmmoWeapon = AgentCombatAmmoCounter.isRangedAmmoWeapon(weaponType);
        int mpPotionCount = 0;
        int ammo = Integer.MAX_VALUE;
        if (mage) {
            for (Item item : bot.getInventory(InventoryType.USE).list()) {
                if (item.getQuantity() <= 0) {
                    continue;
                }
                StatEffect effect = AgentUseItemClassificationPolicy.itemEffect(item.getItemId());
                if (effect == null || !effect.getStatups().isEmpty()) {
                    continue;
                }
                if (effect.getMp() > 0 || effect.getMpRate() > 0) {
                    mpPotionCount += item.getQuantity();
                    if (mpPotionCount >= potLowWarn) {
                        break;
                    }
                }
            }
        } else if (rangedAmmoWeapon) {
            ammo = AgentCombatAmmoCounter.countAmmo(bot, weaponType);
        }
        // boss runs are companion-only (CompanionBossRuns enlists them), so being inside one is a companion run too
        boolean inRun = rangedAmmoWeapon && (AgentPartyQuestRuntime.companionRun(bot.getId())
                || AgentBossRunRegistry.inside(bot.getId()));
        if (inRun && ammo > 0 && AgentAmmoStateRuntime.noAmmo(entry)) {
            // in a companion run a member that gets any projectiles back (loot, an arrow share, a trade) can shoot
            // again at once; ammoWarnSent and dryLineSaid keep their AMMO_LOW_WARN hysteresis
            AgentAmmoStateRuntime.setNoAmmo(entry, false);
        }

        AgentCombatAmmoPolicy.AmmoCheckDecision decision = AgentCombatAmmoPolicy.ammoCheckDecision(
                mage,
                rangedAmmoWeapon,
                mpPotionCount,
                ammo,
                ammoLowWarn,
                AgentAmmoStateRuntime.ammoWarnSent(entry),
                AgentAmmoStateRuntime.noAmmo(entry));
        switch (decision) {
            case CLEAR_WARNING_STATE -> AgentAmmoStateRuntime.clearAmmoWarningState(entry);
            case MAGE_NO_MP_POTS -> {
                AgentAmmoStateRuntime.setNoAmmo(entry, true);
                if (AgentModeStateRuntime.grinding(entry)) {
                    AgentMovementCommandRuntime.followConfiguredTarget(entry);
                    AgentCombatRuntime.sayMapNow(bot, AgentDialogueSelector.randomReply(AgentDialogueCatalog.combatMpPotsOutReplies()));
                }
            }
            case PROJECTILE_LOW_AMMO -> {
                AgentAmmoStateRuntime.setAmmoWarnSent(entry, true);
                AgentCombatRuntime.sayMapNow(bot, AgentDialogueSelector.randomReply(AgentDialogueCatalog.combatAmmoLowReplies()));
            }
            case PROJECTILE_NO_AMMO -> {
                AgentAmmoStateRuntime.setNoAmmo(entry, true);
                if (!inRun && AgentModeStateRuntime.grinding(entry)) { // in a run it keeps its post
                    AgentMovementCommandRuntime.followConfiguredTarget(entry);
                    AgentCombatRuntime.sayMapNow(bot, AgentDialogueSelector.randomReply(AgentDialogueCatalog.combatAmmoOutReplies()));
                }
            }
            case NO_CHANGE -> {
            }
        }
        if (AgentAmmoStateRuntime.dryLineSaid(entry)
                && (!inRun || AgentCombatAmmoCounter.countCarried(bot, weaponType) >= ammoLowWarn)) {
            AgentAmmoStateRuntime.setDryLineSaid(entry, false); // out of a run, or a bag refilled: news again
        }
        if (inRun && ammo <= 0 && !AgentAmmoStateRuntime.dryLineSaid(entry)
                && !AgentPartyQuestRuntime.ownsDryLine(bot.getId(), bot.getMapId())) {
            AgentProjectileKind kind = AgentProjectileKind.of(weaponType);
            AgentCombatRuntime.sayMapNow(bot, AgentDialogueCatalog.inRunAmmoOutLine(
                    kind == null ? "ammo" : kind.word()));
            AgentAmmoStateRuntime.setDryLineSaid(entry, true);
        }
    }
}
