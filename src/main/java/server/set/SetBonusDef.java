package server.set;

/**
 * One threshold row from a {@link SetItemDef} — "at N pieces, grant these stats."
 * Fields map 1:1 to the PLAN.md Tier tables. Populated by yamlbeans from set-items.yml;
 * needs a public no-arg constructor and public fields for binding.
 *
 * Only the stats the server actually aggregates (STR/DEX/INT/LUK/WATK/MATK/HP/MP) are
 * applied to combat math. wdef/mdef/acc/avoid/speed/jump/bossDamagePct/finalDamagePct are
 * recorded for {@code !checksets} display and future wiring, but currently not applied —
 * v83 handles those stats client-side and Cosmic has no multiplier hook.
 */
public class SetBonusDef {
    public int pieces;
    public int allStats;
    public int watk;
    public int matk;
    public int hp;
    public int mp;
    public int wdef;
    public int mdef;
    public int acc;
    public int avoid;
    public int speed;
    public int jump;
    public int bossDamagePct;
    public int finalDamagePct;
}
