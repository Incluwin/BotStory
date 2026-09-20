package server.bot;

import config.YamlConfig;

import java.util.List;

/**
 * Filter that decides whether the drop simulator may emit an item.
 *
 * <p>See BOT_ECONOMY.md § Item pool eligibility. The allowlist is intersected
 * with persona eligibility, the hard-exclusion list, and the current SMT
 * rarity cap at roll time.
 *
 * <p>Ranges here match bot-allowlist.yml defaults. If the YAML is missing or
 * malformed, these defaults keep the system sane.
 */
public final class BotItemAllowlist {

    private static final int[][] EQUIP_RANGES = {
            {1000000, 1099999}, {1100000, 1109999},
            {1102000, 1102999}, {1132000, 1132999}, {1050000, 1059999},
            {1060000, 1069999}, {1070000, 1079999}, {1080000, 1089999},
            {1040000, 1049999}, {1002000, 1049999}, {1302000, 1302999},
            {1312000, 1312999}, {1322000, 1322999}, {1332000, 1332999},
            {1372000, 1372099}, {1382000, 1382099}, {1402000, 1402099},
            {1412000, 1412099}, {1422000, 1422099}, {1432000, 1432099},
            {1442000, 1442099}, {1452000, 1452099}, {1462000, 1462099},
            {1472000, 1472099}, {1482000, 1482099}, {1492000, 1492099},
            {1112000, 1113499}, {1122000, 1122999}, {1032000, 1032999},
            {1092000, 1092999}
    };

    private static final int[][] SCROLL_RANGES = {
            {2040000, 2044999}
    };

    private static final int[][] CRAFT_MAT_RANGES = {
            {4000000, 4000999}, {4003000, 4003999},
            {4011000, 4012999}, {4260000, 4260099}
    };

    private static final int[][] RARE_CONSUMABLE_RANGES = {
            {2022000, 2022999}, {2050000, 2050099}
    };

    // Hard-excluded boss drops, premium scrolls, cash items, etc.
    private static final int[][] HARD_EXCLUDED_RANGES = {
            {1002357, 1002359}, {1002972, 1002974},                         // Zakum Helms
            {1122000, 1122001}, {1132026, 1132029},                         // HT accessories
            {1122007, 1122007}, {1122076, 1122076}, {1122226, 1122229},     // PB rewards
            {1372071, 1372076}, {1382069, 1382074}, {1402067, 1402072},     // Timeless/Reverse
            {1412025, 1412030}, {1432036, 1432041}, {1442048, 1442053},
            {1452042, 1452047}, {1462040, 1462045}, {1472052, 1472057},
            {1482017, 1482022}, {1492016, 1492021},
            {2049000, 2049003}, {2049100, 2049107},                         // Clean Slate, Chaos
            {2046000, 2046005}, {2046100, 2046103},                         // GFA
            {2340000, 2340000}, {2530000, 2530000},                         // White, Shielding
            {5000000, 5999999}                                              // Cash items
    };

    public static boolean isAllowed(int itemId) {
        if (inAnyRange(itemId, HARD_EXCLUDED_RANGES)) return false;
        return inAnyRange(itemId, EQUIP_RANGES)
                || inAnyRange(itemId, SCROLL_RANGES)
                || inAnyRange(itemId, CRAFT_MAT_RANGES)
                || inAnyRange(itemId, RARE_CONSUMABLE_RANGES);
    }

    public static boolean isHardExcluded(int itemId) {
        return inAnyRange(itemId, HARD_EXCLUDED_RANGES);
    }

    /**
     * Cash-shop gating defense in depth. Cosmic already enforces
     * {@code USE_ENFORCE_UNMERCHABLE_CASH}; this is a belt-and-suspenders guard
     * that blocks cash ranges at the allowlist layer too.
     */
    public static boolean isCashItem(int itemId) {
        return itemId >= 5000000 && itemId <= 5999999;
    }

    /**
     * Persona-level eligibility — does this persona ever stock this item id?
     * Coarse filter; the fine filter is persona-rarity weights.
     */
    public static boolean isPersonaEligible(BotPersona persona, int itemId) {
        return switch (persona) {
            case WarriorGear     -> isWarriorWeapon(itemId) || isUniversalArmor(itemId);
            case MagicianGear    -> isMagicianWeapon(itemId) || isUniversalArmor(itemId);
            case ArcherGear      -> isArcherWeapon(itemId) || isUniversalArmor(itemId);
            case ThiefGear       -> isThiefWeapon(itemId) || isUniversalArmor(itemId);
            case PirateGear      -> isPirateWeapon(itemId) || isUniversalArmor(itemId);
            case AccessoryVendor -> isAccessory(itemId);
            case ScrollMerchant  -> inAnyRange(itemId, SCROLL_RANGES);
            case RareSpecialist  -> isAllowed(itemId);   // rarity gate handled by weights
            case BulkVendor      -> inAnyRange(itemId, CRAFT_MAT_RANGES)
                                 || inAnyRange(itemId, RARE_CONSUMABLE_RANGES);
            case NPCVendor       -> false;  // NPC-stock path, not drop-sim allowlist
            case Generalist      -> isAllowed(itemId);
        };
    }

    // ──── weapon/armor/accessory range helpers ────
    private static boolean isWarriorWeapon(int id) {
        return (id >= 1302000 && id <= 1322999)   // 1H sword/axe/mace
            || (id >= 1402000 && id <= 1432099)   // 2H sword/axe/mace, spear
            || (id >= 1442000 && id <= 1442099);  // polearm
    }
    private static boolean isMagicianWeapon(int id) {
        return (id >= 1372000 && id <= 1382099);
    }
    private static boolean isArcherWeapon(int id) {
        return (id >= 1452000 && id <= 1462099);
    }
    private static boolean isThiefWeapon(int id) {
        return (id >= 1332000 && id <= 1332999) || (id >= 1472000 && id <= 1472099);
    }
    private static boolean isPirateWeapon(int id) {
        return (id >= 1482000 && id <= 1492099);
    }
    private static boolean isUniversalArmor(int id) {
        // hats / tops / bottoms / overalls / shoes / gloves / capes / shields
        return (id >= 1000000 && id <= 1099999) || (id >= 1102000 && id <= 1102999)
                || (id >= 1092000 && id <= 1092999);
    }
    private static boolean isAccessory(int id) {
        return (id >= 1112000 && id <= 1113499)  // rings
                || (id >= 1122000 && id <= 1122999)  // pendants
                || (id >= 1032000 && id <= 1032999)  // earrings
                || (id >= 1132000 && id <= 1132999); // belts
    }

    private static boolean inAnyRange(int itemId, int[][] ranges) {
        for (int[] r : ranges) {
            if (itemId >= r[0] && itemId <= r[1]) return true;
        }
        return false;
    }

    /** Returns true iff the persona and item pass the global and persona filters. */
    public static boolean isGloballyEligible(BotPersona persona, int itemId) {
        if (!isAllowed(itemId)) return false;
        return isPersonaEligible(persona, itemId);
    }

    @SuppressWarnings("unused")
    private static void suppressUnused() {
        // YamlConfig is imported so future overrides can be loaded here;
        // keeping the reference avoids "unused import" on the skeleton.
        List<?> ignored = YamlConfig.config == null ? List.of() : List.of();
    }
}
