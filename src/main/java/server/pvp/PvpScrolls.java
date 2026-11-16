package server.pvp;

/**
 * The stock attack scroll for a weapon, which PvP pays and sells (docs/PVP_PLAN.md section 2): a rival win's 25% scroll
 * and the Arena Quartermaster's two. Each weapon type's scrolls sit at 2040000 + (type - 100) * 100, the 60% one at +1
 * and the 10% one at +2 (Item.wz Consume/0204 "success"); magic weapons' are for magic attack.
 */
public final class PvpScrolls {
    private PvpScrolls() {
    }

    /** The {@code percent} (60 or 10) attack scroll for weapon {@code weaponId}, or 0 for none. */
    public static int attack(int weaponId, int percent) {
        int offset = switch (percent) {
            case 60 -> 1;
            case 10 -> 2;
            default -> 0;
        };
        int type = weaponId / 10000;
        return switch (type) {
            case 130, 131, 132, 133, 137, 138, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149 ->
                    offset == 0 ? 0 : 2040000 + (type - 100) * 100 + offset;
            default -> 0;
        };
    }
}
