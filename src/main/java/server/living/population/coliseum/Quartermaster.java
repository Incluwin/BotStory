package server.living.population.coliseum;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.living.resident.LivingTalk;
import server.pvp.PvpScrolls;
import tools.PacketCreator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Arena Quartermaster (docs/PVP_PLAN.md V4.3): Jano, beside Tigun in Ariant, trades Event Trophies for the stock in
 * {@link #FILE}. Trophies are bound (trade, shops, drops and Duey refuse them), so this is the only place they spend.
 * A trade takes the Trophies only once the item fits, and gives them back if the item does not arrive.
 */
final class Quartermaster {
    private static final Logger log = LoggerFactory.getLogger(Quartermaster.class);

    static final Path FILE = Path.of("scripts", "config", "pvp", "quartermaster.yaml");
    static final int JANO = 2101010;
    static final String WEAPON_60 = "weapon60";
    static final String WEAPON_10 = "weapon10";

    /** One line of stock: an item id (or {@link #WEAPON_60} / {@link #WEAPON_10}), its bundle and its Trophy price. */
    record Ware(String key, int bundle, int price) {
        /** What this ware gives someone holding weapon {@code weaponId} (0: none): the item, or that weapon's scroll. */
        int itemFor(int weaponId) {
            return switch (key) {
                case WEAPON_60 -> PvpScrolls.attack(weaponId, 60);
                case WEAPON_10 -> PvpScrolls.attack(weaponId, 10);
                default -> Integer.parseInt(key);
            };
        }

        boolean forWeapon() {
            return key.equals(WEAPON_60) || key.equals(WEAPON_10);
        }
    }

    private static volatile List<Ware> stock;

    static List<Ware> stock() {
        List<Ware> loaded = stock;
        if (loaded == null) {
            loaded = load(FILE);
            stock = loaded;
        }
        return loaded;
    }

    static List<Ware> load(Path file) {
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException missing) {
            log.warn("Quartermaster: no stock file at {}", file);
            return List.of();
        }
    }

    /** "stock:", then indented "item: bundle, price" lines in window order; a line that does not read is skipped. */
    static List<Ware> parse(List<String> lines) {
        List<Ware> wares = new ArrayList<>();
        boolean inStock = false;
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            if (line.isBlank()) {
                continue;
            }
            if (!java.lang.Character.isWhitespace(line.charAt(0))) {
                inStock = line.trim().equals("stock:");
                continue;
            }
            int colon = line.indexOf(':');
            if (!inStock || colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String[] values = line.substring(colon + 1).split(",");
            try {
                if (!key.equals(WEAPON_60) && !key.equals(WEAPON_10)) {
                    Integer.parseInt(key);
                }
                int bundle = Integer.parseInt(values[0].trim());
                int price = Integer.parseInt(values[1].trim());
                if (bundle > 0 && bundle <= Short.MAX_VALUE && price > 0) {
                    wares.add(new Ware(key, bundle, price));
                }
            } catch (RuntimeException unreadable) {
                log.warn("Quartermaster: skipped stock line '{}'", raw.trim());
            }
        }
        return List.copyOf(wares);
    }

    /**
     * Why {@code ware} cannot be traded, or null when it can: no scroll for the weapon held, too few Trophies, or (when
     * {@code fits} is false) no room, or already holding a one-per-character item.
     */
    static String refusal(Ware ware, int itemId, int trophies, boolean fits, boolean holdsOneOfAKind) {
        if (itemId <= 0) {
            return ware.forWeapon() ? "Hold the weapon you want it for, and I'll find you its scroll." : "I'm out of that.";
        }
        if (trophies < ware.price()) {
            return "That's " + ware.price() + " Event Trophies, and you have " + trophies + ".";
        }
        if (holdsOneOfAKind) {
            return "You already have one of those, and one is all anybody gets.";
        }
        if (!fits) {
            return "You've no room for it. Make some and come back.";
        }
        return null;
    }

    // ---- the window -----------------------------------------------------------------------------------------------

    void talk(Character player) {
        List<Ware> wares = stock();
        int trophies = trophies(player);
        if (wares.isEmpty()) {
            LivingTalk.open(player, JANO).ok("Nothing on my shelves today. Come back later.").show();
            return;
        }
        int weaponId = weaponId(player);
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Ware ware : wares) {
            options.add(new LivingTalk.Option(label(ware, ware.itemFor(weaponId)), () -> confirm(player, ware)));
        }
        LivingTalk.open(player, JANO).menu("I'm Jano. What you win in the arena, you spend with me, and nowhere else."
                + "\r\n\r\nYou have #b" + trophies + " Event " + (trophies == 1 ? "Trophy" : "Trophies") + "#k.",
                options).show();
    }

    private static String label(Ware ware, int itemId) {
        String what;
        if (itemId > 0) {
            what = "#v" + itemId + "# #t" + itemId + "#" + (ware.bundle() > 1 ? " x" + ware.bundle() : "");
        } else {
            what = (ware.key().equals(WEAPON_60) ? "60%" : "10%") + " attack scroll for your weapon";
        }
        return what + "  #b" + ware.price() + " Trophies#k";
    }

    private void confirm(Character player, Ware ware) {
        int itemId = ware.itemFor(weaponId(player));
        String why = refusal(ware, itemId, trophies(player), true, false);
        if (why != null) {
            LivingTalk.open(player, JANO).ok(why).back(() -> talk(player)).show();
            return;
        }
        LivingTalk.open(player, JANO).yesNo(ware.price() + " Event Trophies for #b#t" + itemId + "#"
                        + (ware.bundle() > 1 ? " x" + ware.bundle() : "") + "#k?",
                () -> trade(player, ware), () -> talk(player)).show();
    }

    private void trade(Character player, Ware ware) {
        int itemId = ware.itemFor(weaponId(player));
        int trophies = trophies(player);
        boolean oneOfAKind = itemId > 0 && ItemInformationProvider.getInstance().isPickupRestricted(itemId, player)
                && player.getItemQuantity(itemId, true) > 0;
        boolean fits = itemId > 0 && InventoryManipulator.checkSpace(player.getClient(), itemId, ware.bundle(), "");
        String why = refusal(ware, itemId, trophies, fits, oneOfAKind);
        if (why != null) {
            LivingTalk.open(player, JANO).ok(why).back(() -> talk(player)).show();
            return;
        }
        InventoryManipulator.removeById(player.getClient(), InventoryType.ETC, ItemId.EVENT_TROPHY, ware.price(), false,
                false);
        if (!InventoryManipulator.addById(player.getClient(), itemId, (short) ware.bundle())) {
            InventoryManipulator.addById(player.getClient(), ItemId.EVENT_TROPHY, (short) ware.price());
            log.warn("Quartermaster: {} x{} did not reach {}; {} Trophies given back", itemId, ware.bundle(),
                    player.getName(), ware.price());
            LivingTalk.open(player, JANO).ok("Hm, that didn't go through. Your Trophies are back with you.").show();
            return;
        }
        player.sendPacket(PacketCreator.getShowItemGain(itemId, (short) ware.bundle(), true));
        log.info("Quartermaster: {} traded {} Trophies for {} x{} ({} left)", player.getName(), ware.price(), itemId,
                ware.bundle(), trophies - ware.price());
        LivingTalk.open(player, JANO).ok("Pleasure doing business. Win me some more of those.")
                .back(() -> talk(player)).show();
    }

    private static int trophies(Character player) {
        return player.getItemQuantity(ItemId.EVENT_TROPHY, false);
    }

    private static int weaponId(Character player) {
        Item weapon = player.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        return weapon == null ? 0 : weapon.getItemId();
    }
}
