package server.set;

import java.util.Collections;
import java.util.List;

/**
 * Cumulative set bonus for one character. Computed fresh on each equip change; stored on
 * Character so {@code !checksets} and stat-application code can read it without recomputing.
 *
 * str/dex/int_/luk/watk/matk/hp/mp are applied to server combat math by Character.recalcEquipStats.
 * The others are computed and recorded but are not wired to combat yet — see SetBonusDef.
 */
public final class SetBonusTotal {
    public static final SetBonusTotal EMPTY = new SetBonusTotal(
            0, 0, 0, 0,
            0, 0,
            0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            Collections.emptyList());

    public final int str;
    public final int dex;
    public final int int_;
    public final int luk;
    public final int watk;
    public final int matk;
    public final int hp;
    public final int mp;
    public final int wdef;
    public final int mdef;
    public final int acc;
    public final int avoid;
    public final int speed;
    public final int jump;
    public final int bossDamagePct;
    public final int finalDamagePct;
    public final List<ActiveSet> activeSets;

    public SetBonusTotal(int str, int dex, int int_, int luk,
                         int watk, int matk,
                         int hp, int mp,
                         int wdef, int mdef,
                         int acc, int avoid,
                         int speed, int jump,
                         int bossDamagePct, int finalDamagePct,
                         List<ActiveSet> activeSets) {
        this.str = str;
        this.dex = dex;
        this.int_ = int_;
        this.luk = luk;
        this.watk = watk;
        this.matk = matk;
        this.hp = hp;
        this.mp = mp;
        this.wdef = wdef;
        this.mdef = mdef;
        this.acc = acc;
        this.avoid = avoid;
        this.speed = speed;
        this.jump = jump;
        this.bossDamagePct = bossDamagePct;
        this.finalDamagePct = finalDamagePct;
        this.activeSets = activeSets;
    }
}
