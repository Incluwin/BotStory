package server.living.bond;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.store.LivingStore.Bond;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Friendship: a 1-10 meter over the bond points a player has with one Resident (docs/LIVING_WORLD_GUIDE.md,
 * "Friendship"). Quests award points as before; fame, gifts, and party quests and bosses cleared with the Resident in
 * the party add more, gifts, party quests and bosses up to a daily count kept in living_event_log. BondService
 * announces level changes. Hired citizens are not Residents and keep no friendship.
 */
public final class Friendship {
    public static final int MAX_LEVEL = 10;
    public static final int CLOSE_FRIEND = 7;
    static final String GIFT = "friend.gift";
    static final String PQ = "friend.pq";
    static final String BOSS = "friend.boss";
    private static final int GIFT_MENU_MAX = 20;
    private static final InventoryType[] GIFT_INVENTORIES = {InventoryType.USE, InventoryType.ETC, InventoryType.SETUP,
            InventoryType.EQUIP};

    private final LivingWorld world;
    private final int[] levelPoints;

    public Friendship(LivingWorld world) {
        this.world = world;
        this.levelPoints = world.config().friendshipLevelPoints();
    }

    /** Level 0 (never met) to 10 for these bond points; {@code levelPoints[i]} is where level i+1 begins. */
    public static int levelFor(int points, int[] levelPoints) {
        int level = 0;
        while (level < levelPoints.length && points >= levelPoints[level]) {
            level++;
        }
        return level;
    }

    public static String label(int level) {
        return switch (level) {
            case 0 -> "Stranger";
            case 1 -> "Met";
            case 2, 3 -> "Acquaintance";
            case 4, 5 -> "Friend";
            case 6 -> "Good friend";
            case 7, 8, 9 -> "Close friend";
            default -> "Best friend";
        };
    }

    public int levelFor(int points) {
        return levelFor(points, levelPoints);
    }

    public int level(int characterId, String personaId) {
        Bond bond = world.bonds().peek(characterId, personaId);
        return bond == null ? 0 : levelFor(bond.points());
    }

    /** "Lv 4 Friend (35 points to Lv 5)". */
    public String describe(int points) {
        int level = levelFor(points);
        String out = "Lv " + level + " " + label(level);
        return level >= MAX_LEVEL ? out : out + " (" + (levelPoints[level] - points) + " points to Lv " + (level + 1) + ")";
    }

    /** The Resident this character plays, or null for players, citizens and hired hands. */
    public Persona residentOf(Character chr) {
        if (chr == null || !AgentPresence.isAgent(chr)) {
            return null;
        }
        Persona persona = world.residents().personaOf(chr);
        return persona == null || LivingContent.isHire(persona.id()) ? null : persona;
    }

    // ---- fame -----------------------------------------------------------------------------

    /** A player famed ({@code delta} 1) or defamed (-1) a Resident; the game allows it once a month per target. */
    public void onFame(Character giver, Character target, int delta) {
        Persona persona = residentOf(target);
        if (persona == null || giver == null || AgentPresence.isAgent(giver)) {
            return;
        }
        world.bonds().addPoints(giver, persona, world.config().FRIENDSHIP_FAME_POINTS * Integer.signum(delta),
                delta > 0 ? "fame" : "defame");
        List<String> lines = world.arcs().lines(persona, delta > 0 ? "famed" : "defamed");
        world.residents().reply(persona, giver, lines.isEmpty() ? (delta > 0 ? "oh. thanks." : "...noted.") : pick(lines, giver));
    }

    // ---- gifts ----------------------------------------------------------------------------

    /**
     * What the player could hand the Resident: one stack per item id, in inventory order, from use, etc, setup and
     * equip (not what is worn); never cash, quest or untradeable items.
     */
    public List<Item> giftable(Character chr) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Item> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (InventoryType type : GIFT_INVENTORIES) {
            List<Item> items = new ArrayList<>(chr.getInventory(type).list());
            items.sort(Comparator.comparingInt(Item::getPosition));
            for (Item item : items) {
                int itemId = item.getItemId();
                if ((item.getFlag() & ItemConstants.UNTRADEABLE) != 0 || ii.isCash(itemId) || ii.isDropRestricted(itemId)
                        || !seen.add(itemId)) {
                    continue;
                }
                out.add(item);
                if (out.size() >= GIFT_MENU_MAX) {
                    return out;
                }
            }
        }
        return out;
    }

    /**
     * The player hands over one of {@code item} (picked from {@link #giftable}): favourites ({@code giftItemIds}) count
     * more, and a Resident takes {@code FRIENDSHIP_GIFTS_PER_DAY} gifts a day from one player and turns the rest down.
     */
    public void gift(Character chr, Persona persona, Item item) {
        CompanionWeddings weddings = world.weddings();
        if (weddings != null && weddings.keepsGarment(chr, persona, item)) {
            return; // a wedding garment for the companion engaged to them: it keeps it for the altar, no friendship gift
        }
        int perDay = world.config().FRIENDSHIP_GIFTS_PER_DAY;
        if (perDay > 0 && world.store().countEvents(chr.getId(), persona.id(), GIFT, world.routineDayStartMs()) >= perDay) {
            say(chr, persona, "gift.again", "you already brought me something today. keep it.");
            return;
        }
        InventoryType type = item.getInventoryType();
        Item held = chr.getInventory(type).getItem(item.getPosition());
        if (held == null || held.getItemId() != item.getItemId()) {
            say(chr, persona, "gift.gone", "...you don't have that anymore.");
            return;
        }
        InventoryManipulator.removeFromSlot(chr.getClient(), type, item.getPosition(), (short) 1, false);
        boolean favorite = persona.giftItemIds().contains(item.getItemId());
        world.store().event(chr.getId(), persona.id(), GIFT, String.valueOf(item.getItemId()));
        say(chr, persona, favorite ? "gift.favorite" : "gift", favorite ? "oh! how did you know?" : "for me? ...thanks.");
        world.bonds().addPoints(chr, persona, favorite ? world.config().FRIENDSHIP_GIFT_FAVORITE_POINTS
                : world.config().FRIENDSHIP_GIFT_POINTS, "gift " + item.getItemId());
    }

    // ---- shared activities ----------------------------------------------------------------

    /** A party quest instance cleared: each player in it befriends the Residents cleared alongside in their party. */
    public void onPartyQuestCleared(List<Character> players) {
        for (Character player : players) {
            if (player == null || AgentPresence.isAgent(player)) {
                continue;
            }
            for (Character member : players) {
                Persona persona = residentOf(member);
                if (persona != null && sameParty(player, member)) {
                    together(player, persona, PQ, world.config().FRIENDSHIP_PQ_POINTS, world.config().FRIENDSHIP_PQ_PER_DAY,
                            "party quest");
                }
            }
        }
    }

    /** A boss died: each player who hit it befriends the Residents of their party standing on its map. */
    public void onBossKilled(int mobId, Set<Integer> attackerIds, MapleMap map) {
        List<Character> here = map.getAllPlayers();
        for (int attackerId : attackerIds) {
            Character player = map.getCharacterById(attackerId);
            if (player == null || AgentPresence.isAgent(player) || player.getParty() == null) {
                continue;
            }
            for (Character member : here) {
                Persona persona = residentOf(member);
                if (persona != null && sameParty(player, member)) {
                    together(player, persona, BOSS, world.config().FRIENDSHIP_BOSS_POINTS,
                            world.config().FRIENDSHIP_BOSS_PER_DAY, "boss " + mobId);
                }
            }
        }
    }

    /**
     * A companion boss run cleared (P12 G12, decision 23): a player who took part (an owner, or a friend who hit one of
     * its bosses) befriends each Resident companion that went in with them, once for the run, under the daily boss cap.
     * The run's own boss kills gave none (LivingWorldHooks.mobKilled).
     */
    public void onBossRunCleared(Character player, List<Persona> companions, String bossKey) {
        if (player == null || AgentPresence.isAgent(player)) {
            return;
        }
        for (Persona persona : companions) {
            together(player, persona, BOSS, world.config().FRIENDSHIP_BOSS_POINTS,
                    world.config().FRIENDSHIP_BOSS_PER_DAY, "boss run " + bossKey);
        }
    }

    private void together(Character player, Persona persona, String kind, int points, int perDay, String reason) {
        if (points == 0 || perDay > 0
                && world.store().countEvents(player.getId(), persona.id(), kind, world.routineDayStartMs()) >= perDay) {
            return;
        }
        world.store().event(player.getId(), persona.id(), kind, reason);
        world.bonds().addPoints(player, persona, points, reason);
    }

    private void say(Character chr, Persona persona, String intent, String fallback) {
        List<String> lines = world.arcs().lines(persona, intent);
        world.residents().reply(persona, chr, lines.isEmpty() ? fallback : pick(lines, chr));
    }

    private static boolean sameParty(Character player, Character member) {
        return player.getParty() != null && member.getParty() != null && player.getParty().getId() == member.getParty().getId();
    }

    private static String pick(List<String> lines, Character chr) {
        return lines.get(Math.floorMod(chr.getId() + (int) (System.currentTimeMillis() / 9000L), lines.size()));
    }
}
