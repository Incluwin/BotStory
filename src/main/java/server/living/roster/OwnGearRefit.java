package server.living.roster;

import client.inventory.Equip;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.knowledge.ItemFact;
import server.living.quest.FriendForge;
import server.living.store.LivingStore.WearStats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * What a companion puts back on once its {@code !bossgear} kit came off (CompanionGearTool: on both release paths,
 * loaded and in its saved inventory while nothing has it loaded, and at {@code !bossgear remove}). Each slot a boss
 * piece left empty takes a piece for that slot it carries in its Equip tab and can wear: first the very piece the kit
 * displaced from that slot (BossGearRecord: Tamsin's own Steel Sharp Helm, not the higher Jousting Helmet she also
 * carries), else the best one. A piece only ever moves out of its bag; nothing is made. A slot a boss piece pushed off
 * counts too: the pants under a boss overall, and the shield beside a two-handed boss weapon. Pure: it only says which
 * piece goes where.
 *
 * <p>The weapon is dressed first and the shield last, the top before the pants. A piece fits a slot by its knowledge
 * category (cap, coat or overall, pants, shoes, glove, cape, shield, weapon; never a cash piece). It is wearable by its
 * level, its job mask (as every Agent is held to), fame, and the main stat the server holds the job to (STR for a
 * warrior, INT a magician, DEX a bowman, LUK a thief: base plus what it wears), as the game's own wear check
 * (ItemInformationProvider.canWearEquipment) is. Like that check, the gender in its id never rules a piece out: every
 * persona is made in the male beginner set (BotCreator), so Tamsin wore a man's Blue Jean Shorts under her coat. A
 * shield goes on only beside a worn one-handed weapon, and beside a worn shield only a one-handed weapon does. An
 * overall goes on only with no pants on, and pants only under a coat. The piece the kit displaced from the slot goes on
 * when it is still carried and wearable; else the best: for the weapon the boss weapon's type first (the kit keeps the
 * worn type), then a piece of its own gender (or for both), then the highest required level, then the first bag slot.
 * A piece with no knowledge fact never moves.</p>
 */
final class OwnGearRefit {
    private static final short CAP = -1;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short SHOES = -7;
    private static final short GLOVE = -8;
    private static final short CAPE = -9;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    /** The order the slots are dressed in: the weapon before the shield, the top before the pants. */
    private static final List<Short> ORDER = List.of(WEAPON, TOP, PANTS, CAP, SHOES, GLOVE, CAPE, SHIELD);
    private static final int WEAPON_TYPE = 10_000;
    /** ItemInformationProvider.isTwoHanded: every weapon type from 140 (two-handed sword) to 149 (gun). */
    private static final int FIRST_TWO_HANDED = 140;
    private static final int LAST_WEAPON = 149;
    /** The gender digit of an equip id ({@code id / 1000 % 10}): 0 male, 1 female, anything else both (GearPicks). */
    private static final int GENDER_DIGIT = 1_000;
    private static final int DIGIT = 10;
    private static final int MALE = 0;
    private static final int FEMALE = 1;
    /** A job's branch ({@code id / 100 % 10}, Job.getJobNiche): which main stat its gear asks for. */
    private static final int BRANCH = 100;
    private static final int WARRIOR = 1;
    private static final int MAGICIAN = 2;
    private static final int BOWMAN = 3;
    private static final int THIEF = 4;

    private OwnGearRefit() {
    }

    /** One carried piece and the worn slot it goes on. */
    record Move(Item piece, short slot) {
    }

    /**
     * The moves, in dressing order. {@code wearer}: the character now (null: unknown, nothing moves); {@code taken}:
     * the worn slot and item id of every boss piece that came off; {@code displaced}: the worn slot and item id of each
     * own piece the kit displaced (BossGearRecord; empty when unknown); {@code worn}: what it still wears, by slot;
     * {@code carried}: its Equip tab; {@code facts}: an item's knowledge fact, or null when unknown. Changes nothing.
     */
    static List<Move> plan(WearStats wearer, Map<Short, Integer> taken, Map<Short, Integer> displaced,
                           Map<Short, Item> worn, Collection<Item> carried, IntFunction<ItemFact> facts) {
        List<Move> moves = new ArrayList<>();
        if (wearer == null || taken.isEmpty()) {
            return moves;
        }
        Set<Short> open = new HashSet<>(taken.keySet());
        if (ItemConstants.isOverall(taken.getOrDefault(TOP, 0))) {
            open.add(PANTS); // the boss overall pushed its pants off
        }
        if (twoHanded(taken.getOrDefault(WEAPON, 0))) {
            open.add(SHIELD); // the two-handed boss weapon pushed its shield off
        }
        Map<Short, Item> wearing = new HashMap<>(worn);
        List<Item> bag = new ArrayList<>(carried);
        int bossType = taken.getOrDefault(WEAPON, 0) / WEAPON_TYPE;
        for (short slot : ORDER) {
            if (!open.contains(slot) || wearing.containsKey(slot) || !takesAPiece(slot, wearing)) {
                continue;
            }
            int own = displaced.getOrDefault(slot, 0);
            Item best = null;
            ItemFact bestFact = null;
            for (Item piece : bag) {
                ItemFact fact = piece instanceof Equip ? facts.apply(piece.getItemId()) : null;
                if (fact == null || slotOf(fact) != slot || !besideTheRest(slot, piece, wearing)
                        || !wearable(wearer, fact, wearing)) {
                    continue;
                }
                if (best == null || better(piece, fact, best, bestFact, own, slot == WEAPON ? bossType : 0,
                        wearer.gender())) {
                    best = piece;
                    bestFact = fact;
                }
            }
            if (best != null) {
                moves.add(new Move(best, slot));
                wearing.put(slot, best);
                bag.remove(best);
            }
        }
        return moves;
    }

    /** Two-handed (ItemInformationProvider.isTwoHanded): no shield beside it. */
    static boolean twoHanded(int itemId) {
        int type = itemId / WEAPON_TYPE;
        return type >= FIRST_TWO_HANDED && type <= LAST_WEAPON;
    }

    /** A shield only beside a worn one-handed weapon; pants only when the top is not an overall. */
    private static boolean takesAPiece(short slot, Map<Short, Item> wearing) {
        if (slot == SHIELD) {
            Item weapon = wearing.get(WEAPON);
            return weapon != null && !twoHanded(weapon.getItemId());
        }
        if (slot == PANTS) {
            Item top = wearing.get(TOP);
            return top == null || !ItemConstants.isOverall(top.getItemId());
        }
        return true;
    }

    /** Beside a worn shield only a one-handed weapon; over worn pants only a coat. */
    private static boolean besideTheRest(short slot, Item piece, Map<Short, Item> wearing) {
        if (slot == WEAPON) {
            return !twoHanded(piece.getItemId()) || !wearing.containsKey(SHIELD);
        }
        if (slot == TOP) {
            return !ItemConstants.isOverall(piece.getItemId()) || !wearing.containsKey(PANTS);
        }
        return true;
    }

    /** The worn slot a piece of this knowledge category goes on; 0 for none (a cash piece, or no slot of the kit). */
    private static short slotOf(ItemFact fact) {
        if (!fact.equip() || fact.cash()) {
            return 0;
        }
        return switch (fact.category()) {
            case "cap" -> CAP;
            case "coat", "longcoat" -> TOP;
            case "pants" -> PANTS;
            case "shoes" -> SHOES;
            case "glove" -> GLOVE;
            case "cape" -> CAPE;
            case "shield" -> SHIELD;
            case "weapon" -> WEAPON;
            default -> 0;
        };
    }

    private static boolean wearable(WearStats wearer, ItemFact fact, Map<Short, Item> wearing) {
        if (fact.reqLevel() > wearer.level()) {
            return false;
        }
        if (fact.reqJob() != 0 && (fact.reqJob() & FriendForge.jobMask(wearer.jobId())) == 0) {
            return false;
        }
        if (fact.reqPop() > 0 && fact.reqPop() > wearer.fame()) {
            return false;
        }
        return switch (wearer.jobId() / BRANCH % DIGIT) {
            case WARRIOR -> fact.reqStr() <= wearer.str() + bonus(wearing, Equip::getStr);
            case MAGICIAN -> fact.reqInt() <= wearer.int_() + bonus(wearing, Equip::getInt);
            case BOWMAN -> fact.reqDex() <= wearer.dex() + bonus(wearing, Equip::getDex);
            case THIEF -> fact.reqLuk() <= wearer.luk() + bonus(wearing, Equip::getLuk);
            default -> true;
        };
    }

    /** The gender digit of the id ({@code id / 1000 % 10}: 0 male, 1 female, else both) fits {@code gender}. */
    private static boolean fitsGender(int itemId, int gender) {
        int digit = itemId / GENDER_DIGIT % DIGIT;
        return digit != MALE && digit != FEMALE || digit == gender;
    }

    /** What the pieces it wears add to one stat. */
    private static int bonus(Map<Short, Item> wearing, ToIntFunction<Equip> stat) {
        int sum = 0;
        for (Item item : wearing.values()) {
            if (item instanceof Equip equip) {
                sum += stat.applyAsInt(equip);
            }
        }
        return sum;
    }

    /**
     * {@code own}: the item id the kit displaced from this slot, which wins first (0: none); {@code prefer}: the weapon
     * type that wins next (0: none); then a piece for the wearer's {@code gender} (or for both), then the higher level,
     * then the earlier bag slot.
     */
    private static boolean better(Item piece, ItemFact fact, Item best, ItemFact bestFact, int own, int prefer,
                                  int gender) {
        boolean was = own != 0 && piece.getItemId() == own;
        if (was != (own != 0 && best.getItemId() == own)) {
            return was;
        }
        boolean preferred = prefer != 0 && piece.getItemId() / WEAPON_TYPE == prefer;
        boolean bestPreferred = prefer != 0 && best.getItemId() / WEAPON_TYPE == prefer;
        if (preferred != bestPreferred) {
            return preferred;
        }
        boolean fits = fitsGender(piece.getItemId(), gender);
        if (fits != fitsGender(best.getItemId(), gender)) {
            return fits;
        }
        if (fact.reqLevel() != bestFact.reqLevel()) {
            return fact.reqLevel() > bestFact.reqLevel();
        }
        return piece.getPosition() < best.getPosition();
    }
}
