package server.agents.capabilities.supplies;

import client.inventory.WeaponType;

public final class AgentAmmoSharePolicy {
    private AgentAmmoSharePolicy() {
    }

    public record DonorScore(boolean donorNeedsSameAmmo, int matchingAmmoCount) {
    }

    public static boolean canRequestShare(WeaponType weaponType) {
        return weaponType == WeaponType.BOW || weaponType == WeaponType.CROSSBOW;
    }

    /**
     * Whether the Agent watches its own projectile count, so that running dry plans a shop trip
     * (AgentResourcePlanningRuntime.observe): the bows and crossbows that also ask their party ({@link #canRequestShare}),
     * and claws, which ask nobody (throwing stars are recharged, not handed over) but get their stars back at any shop.
     * Found 2026-11-17: 11 of 90 Assassin-line citizens carried no star, six of them with no shop visit in a day and a
     * half, because a claw never observed its THROWING_STAR need and so never set out for one.
     */
    public static boolean observesOwnSupply(WeaponType weaponType) {
        return canRequestShare(weaponType) || weaponType == WeaponType.CLAW;
    }

    public static int donationQuantity(int matchingAmmoCount, int lowWarn, boolean donorNeedsSameAmmo) {
        return donorNeedsSameAmmo ? (matchingAmmoCount - lowWarn) / 2 : matchingAmmoCount;
    }

    public static boolean isBetterDonor(DonorScore candidate, DonorScore best) {
        if (best == null) {
            return true;
        }
        if (candidate.donorNeedsSameAmmo() != best.donorNeedsSameAmmo()) {
            return !candidate.donorNeedsSameAmmo();
        }
        return candidate.matchingAmmoCount() > best.matchingAmmoCount();
    }
}
