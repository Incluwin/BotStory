package server.knowledge;

/**
 * One monster from Mob.wz (raw WZ numbers: exp before any rate).
 *
 * @param revive mobs this one turns into when it dies ({@code info/revive})
 */
public record MobFact(int id, String name, int level, int maxHp, int exp, boolean boss, boolean undead,
                      IntList revive) {
}
