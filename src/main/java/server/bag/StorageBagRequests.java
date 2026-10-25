package server.bag;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/** One decoded bag window request (net.server.channel.handlers.StorageBagHandler), carried out and answered. */
public final class StorageBagRequests {
    private static final Logger log = LoggerFactory.getLogger(StorageBagRequests.class);

    public static final int OPEN = 0, WITHDRAW = 1, DEPOSIT = 2, ORGANIZE = 3, SET_AUTO = 4, MOVE = 5, CLOSE = 6;

    private StorageBagRequests() {
    }

    /**
     * Under the character's monitor, so a save copies the inventory and the bags either before or after a transfer,
     * never between its two halves. Every request but CLOSE is answered with the bag's snapshot.
     */
    public static void handle(Character chr, int action, int bag, int a, int b, int d) {
        StorageBagKind kind = StorageBagKind.of(bag);
        if (kind == null || action < OPEN || action > CLOSE || !StorageBags.usable(chr.getClient())) {
            return;
        }
        StorageBags bags = chr.getStorageBags();
        if (bags == null) {
            chr.dropMessage(1, "Your Storage Bags are not available right now.");
            return;
        }
        synchronized (chr) {
            if (action == CLOSE) {
                bags.closed();
                return;
            }
            try {
                bags.ensureLoaded();
            } catch (SQLException e) {
                log.error("Storage Bags of {} did not load", chr.getName(), e);
                chr.dropMessage(1, "Your Storage Bags are not available right now.");
                return;
            }
            String line = switch (action) {
                case OPEN -> {
                    bags.opened(kind);
                    yield null;
                }
                case WITHDRAW -> bags.withdraw(chr, kind, a);
                case DEPOSIT -> bags.deposit(chr, kind, InventoryType.getByType((byte) a), (short) b, d);
                case ORGANIZE -> {
                    bags.organize(chr, kind);
                    yield null;
                }
                case SET_AUTO -> {
                    bags.auto(chr, kind, a != 0);
                    yield null;
                }
                case MOVE -> {
                    bags.move(chr, kind, a, b);
                    yield null;
                }
                default -> null;
            };
            if (line != null) {
                chr.dropMessage(5, line);
            }
            chr.sendPacket(bags.snapshot(kind));
        }
    }
}
