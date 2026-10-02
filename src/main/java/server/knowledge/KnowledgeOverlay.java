package server.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * The live rows the knowledge file leaves out: {@code drop_data}, {@code drop_data_global},
 * {@code shops}+{@code shopitems} and DB-placed life ({@code plife}). Immutable; a rebuild makes a new one.
 * Built by {@link KnowledgeOverlayLoader} from the DB, or directly through {@link #builder()} in tests.
 */
public final class KnowledgeOverlay {
    static final KnowledgeOverlay EMPTY = builder().build();

    /** {mobId, itemId, chance, min, max, questId} per {@code drop_data} row. */
    final List<int[]> drops;
    /** {continent, itemId, chance, min, max, questId} per {@code drop_data_global} row. */
    final List<int[]> globalDrops;
    /** {shopId, npcId, itemId, price, pitch} per {@code shopitems} row. */
    final List<int[]> shopItems;
    /** {type ('m' or 'n'), lifeId, mapId} per {@code plife} row. */
    final List<int[]> life;

    private KnowledgeOverlay(Builder builder) {
        drops = List.copyOf(builder.drops);
        globalDrops = List.copyOf(builder.globalDrops);
        shopItems = List.copyOf(builder.shopItems);
        life = List.copyOf(builder.life);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int dropRows() {
        return drops.size();
    }

    public int globalDropRows() {
        return globalDrops.size();
    }

    public int shopRows() {
        return shopItems.size();
    }

    public int lifeRows() {
        return life.size();
    }

    long approxBytes() {
        return (drops.size() * 6L + globalDrops.size() * 6L + shopItems.size() * 5L + life.size() * 3L) * Integer.BYTES
                + (drops.size() + globalDrops.size() + shopItems.size() + life.size()) * 16L;
    }

    public static final class Builder {
        private final List<int[]> drops = new ArrayList<>();
        private final List<int[]> globalDrops = new ArrayList<>();
        private final List<int[]> shopItems = new ArrayList<>();
        private final List<int[]> life = new ArrayList<>();

        private Builder() {
        }

        public Builder drop(int mobId, int itemId, int chance, int min, int max, int questId) {
            drops.add(new int[]{mobId, itemId, chance, min, max, questId});
            return this;
        }

        public Builder globalDrop(int continent, int itemId, int chance, int min, int max, int questId) {
            globalDrops.add(new int[]{continent, itemId, chance, min, max, questId});
            return this;
        }

        public Builder shopItem(int shopId, int npcId, int itemId, int price, int pitch) {
            shopItems.add(new int[]{shopId, npcId, itemId, price, pitch});
            return this;
        }

        /** A {@code plife} row; {@code type} is "m" (mob) or "n" (NPC), anything else is ignored. */
        public Builder life(String type, int lifeId, int mapId) {
            if ("m".equals(type) || "n".equals(type)) {
                life.add(new int[]{type.charAt(0), lifeId, mapId});
            }
            return this;
        }

        public KnowledgeOverlay build() {
            return new KnowledgeOverlay(this);
        }
    }
}
