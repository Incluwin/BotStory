package server.bot;

import java.util.EnumSet;
import java.util.Set;

/**
 * Bot merchant persona. Drives which items a bot stocks, how it prices them,
 * how many listings it carries, which generation path fills its inventory,
 * and the SMT at which it becomes active.
 *
 * <p>All config literals match the tables in
 * {@code Docs/BOT_ECONOMY.md § Merchant personas}; the persona enum is the
 * source of truth for the Java side, the YAML / DB own the tunable values.
 */
public enum BotPersona {
    ScrollMerchant ( 5,  8, 3, 5, 1.00, 0, GenPath.DROP_SIM, Rarity.set("R1","R2","R3","R4")),
    WarriorGear    ( 4,  6, 1, 2, 0.95, 1, GenPath.DROP_SIM, Rarity.set("R2","R3","R4")),
    MagicianGear   ( 4,  6, 1, 2, 0.95, 1, GenPath.DROP_SIM, Rarity.set("R2","R3","R4")),
    ArcherGear     ( 4,  6, 1, 2, 0.95, 1, GenPath.DROP_SIM, Rarity.set("R2","R3","R4")),
    ThiefGear      ( 4,  6, 1, 2, 0.95, 1, GenPath.DROP_SIM, Rarity.set("R2","R3","R4")),
    PirateGear     ( 4,  6, 1, 2, 0.95, 1, GenPath.DROP_SIM, Rarity.set("R2","R3","R4")),
    NPCVendor      ( 6,  8, 20, 50, 1.00, 0, GenPath.NPC_STOCK, EnumSet.noneOf(Rarity.class)),
    BulkVendor     ( 4,  6, 5, 20, 0.85, 0, GenPath.DROP_SIM, Rarity.set("R1","R2","R3","R4")),
    RareSpecialist ( 2,  4, 1, 1, 1.15, 4, GenPath.DROP_SIM, Rarity.set("R3","R4","R5")),
    AccessoryVendor( 3,  5, 1, 1, 1.05, 1, GenPath.DROP_SIM, Rarity.set("R1","R2","R3")),
    Generalist     ( 4,  7, 1, 4, 1.00, 0, GenPath.DROP_SIM, Rarity.set("R1","R2","R3"));

    public enum GenPath { DROP_SIM, NPC_STOCK }

    public enum Rarity {
        R1, R2, R3, R4, R5;
        static Set<Rarity> set(String... codes) {
            Set<Rarity> out = EnumSet.noneOf(Rarity.class);
            for (String c : codes) out.add(Rarity.valueOf(c));
            return out;
        }
    }

    public final int itemsMin;
    public final int itemsMax;
    public final int qtyMin;
    public final int qtyMax;
    public final double priceMult;
    public final int activeFromSmt;
    public final GenPath genPath;
    public final Set<Rarity> eligibleRarities;

    BotPersona(int itemsMin, int itemsMax, int qtyMin, int qtyMax,
               double priceMult, int activeFromSmt, GenPath genPath,
               Set<Rarity> eligibleRarities) {
        this.itemsMin = itemsMin;
        this.itemsMax = itemsMax;
        this.qtyMin = qtyMin;
        this.qtyMax = qtyMax;
        this.priceMult = priceMult;
        this.activeFromSmt = activeFromSmt;
        this.genPath = genPath;
        this.eligibleRarities = eligibleRarities;
    }

    public boolean activeAt(int currentSmt) {
        if (currentSmt < activeFromSmt) return false;
        if (genPath == GenPath.NPC_STOCK) return true; // NPC path isn't gated by rarity
        // Otherwise require at least one of the persona's eligible rarities to be unlocked.
        Rarity cap = smtRarityCap(currentSmt);
        for (Rarity r : eligibleRarities) {
            if (r.ordinal() <= cap.ordinal()) return true;
        }
        return false;
    }

    /** SMT → rarity cap. Matches bot-persona-rarity.yml:smt_rarity_cap. */
    public static Rarity smtRarityCap(int smt) {
        return switch (Math.max(0, Math.min(6, smt))) {
            case 0                    -> Rarity.R1;
            case 1, 2                 -> Rarity.R2;
            case 3                    -> Rarity.R3;
            case 4, 5                 -> Rarity.R4;
            default                   -> Rarity.R5;
        };
    }

    /** SMT → total merchant target count. Matches bot-persona-rarity.yml:smt_merchant_target. */
    public static int smtMerchantTarget(int smt) {
        return switch (Math.max(0, Math.min(6, smt))) {
            case 0 -> 80;
            case 1 -> 110;
            case 2 -> 135;
            case 3 -> 155;
            case 4 -> 165;
            case 5 -> 172;
            default -> 175;
        };
    }
}
