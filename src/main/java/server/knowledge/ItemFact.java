package server.knowledge;

/**
 * One item. Requirements are the edited WZ values the server enforces (secondary stats are zeroed in
 * Character.wz). {@code reqJob} is a loadout hint only: players may wear anything, the job gate is bot-only.
 *
 * @param category equip slot ("cap", "weapon", "shoulder", ...) or "use", "setup", "etc", "cash", "pet"
 * @param price    NPC sell price ({@code info/price})
 * @param cash     a cash item (Item.wz/Cash, or an equip with {@code info/cash})
 * @param unnamed  no String.wz name (custom equips); {@code name} is empty
 * @param success  scrolls: the success % players see (String.wz desc), 0 for everything else
 */
public record ItemFact(int id, String name, String category, int reqLevel, int reqJob, int reqStr, int reqDex,
                       int reqInt, int reqLuk, int reqPop, int price, boolean cash, boolean unnamed,
                       int success) {
    public boolean equip() {
        return id / 1_000_000 == 1;
    }
}
