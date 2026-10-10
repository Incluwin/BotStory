package server.set;

import java.util.ArrayList;
import java.util.List;

/**
 * A single set definition loaded from set-items.yml: display name, members (item IDs that
 * count toward the set — including variants like Reverse for Timeless), and the piece-count
 * thresholds that grant cumulative bonuses.
 */
public class SetItemDef {
    public String id;
    public String name;
    public List<Integer> members = new ArrayList<>();
    public List<SetBonusDef> bonuses = new ArrayList<>();
}
