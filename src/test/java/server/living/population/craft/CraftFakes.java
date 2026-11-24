package server.living.population.craft;

import client.inventory.Equip;
import client.inventory.Item;
import server.living.population.Population;

import java.util.function.IntFunction;

/** For tests in other packages: trades over a book of their own. */
public final class CraftFakes {
    private CraftFakes() {
    }

    public static Professions professions(Population population, RecipeBook book) {
        return new Professions(population, book);
    }

    /** The real moves, making equips by {@code equips} and anything else plain; everything tradeable, no scrolling. */
    public static CommissionHands hands(IntFunction<Equip> equips) {
        return new CommissionHands(new CommissionStore(), new CommissionHands.Items() {
            @Override
            public boolean tradeable(int itemId) {
                return true;
            }

            @Override
            public Item make(int itemId, int count) {
                return itemId / 1_000_000 == 1 ? equips.apply(itemId) : new Item(itemId, (short) 0, (short) count);
            }

            @Override
            public boolean scrolls(int scrollId, int equipId) {
                return false;
            }

            @Override
            public Equip scrolled(Equip equip, int scrollId) {
                return equip;
            }
        });
    }
}
