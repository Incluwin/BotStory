package server.agents.capabilities.supplies;

import client.inventory.WeaponType;

/**
 * What a ranged weapon shoots, in the word a player uses for it: stars (claw), arrows (bow), bolts (crossbow),
 * bullets (gun). A host's lines name a companion's projectiles by it.
 */
public enum AgentProjectileKind {
    STARS("stars"),
    ARROWS("arrows"),
    BOLTS("bolts"),
    BULLETS("bullets");

    private final String word;

    AgentProjectileKind(String word) {
        this.word = word;
    }

    /** The plural a player says for these projectiles, lower case. */
    public String word() {
        return word;
    }

    /** What {@code weaponType} shoots; null for a weapon that shoots nothing (or none). */
    public static AgentProjectileKind of(WeaponType weaponType) {
        if (weaponType == null) {
            return null;
        }
        return switch (weaponType) {
            case CLAW -> STARS;
            case BOW -> ARROWS;
            case CROSSBOW -> BOLTS;
            case GUN -> BULLETS;
            default -> null;
        };
    }
}
