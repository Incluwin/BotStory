package server.set;

import java.util.ArrayList;
import java.util.List;

/**
 * A single set definition loaded from set-items.yml: display name, members (item IDs that
 * count toward the set — including variants like Reverse for Timeless), and the piece-count
 * thresholds that grant cumulative bonuses.
 *
 * {@code jobs} restricts the set to characters of the listed job niches ("WARRIOR", "MAGICIAN",
 * "BOWMAN", "THIEF", "PIRATE"). Null/empty means any class — kept that way for tests and for
 * any future cross-class set. Niche matching uses {@link client.Job#getJobNiche()}, so Cygnus
 * and Aran/Evan resolve to their parent niche too (Thunder Breaker → PIRATE, Aran → WARRIOR…).
 */
public class SetItemDef {
    public String id;
    public String name;
    public List<String> jobs = new ArrayList<>();
    public List<Integer> members = new ArrayList<>();
    public List<SetBonusDef> bonuses = new ArrayList<>();
}
