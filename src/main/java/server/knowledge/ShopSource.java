package server.knowledge;

/**
 * One NPC shop row selling an item. {@code pitch > 0} means the item trades for that many Perfect Pitch
 * (its meso price is then usually 0).
 *
 * @param npcMaps where the shop's NPC stands
 */
public record ShopSource(int itemId, int shopId, int npcId, int price, int pitch, IntList npcMaps) {
}
