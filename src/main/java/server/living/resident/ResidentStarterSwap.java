package server.living.resident;

import client.Character;
import client.creator.BotCreator;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Swaps a Resident's starting top or bottom of the other gender for its own gender's twin (BotCreator.starterTwin), worn
 * or in the bag, each in its own key (docs/PERSONA_STARTER_OUTFIT_GENDER.md, option 2). Residents made before
 * 2026-10-10 started in the men's set whatever their gender, and a later look.gender edit changes the gender of the
 * character but not its clothes; the gender of the loaded character decides. Only a plain piece is swapped: every
 * persisted field the swap does not carry is the fresh twin's (no owner tag or gift note, no flag, no expiry, never
 * scrolled, failed or hammered, no ring or anvil look). That holds because each twin pair has the same Character.wz
 * data (ResidentStarterSwapTest pins it); its growth and stats go to the twin. Every twin is made before anything moves,
 * and a piece that moved since the scan stays (the next load tries again). Idempotent: it runs on every load. A hired
 * citizen's gear is its own.
 */
final class ResidentStarterSwap {
    private static final Logger log = LoggerFactory.getLogger(ResidentStarterSwap.class);
    private static final IntFunction<Equip> FRESH =
            id -> ItemInformationProvider.getInstance().getEquipById(id) instanceof Equip equip ? equip : null;

    private ResidentStarterSwap() {
    }

    /** One piece to swap: where it is, and its twin made ready for that key. */
    private record Planned(Inventory inventory, short position, Equip piece, Equip twin, boolean worn) { }

    /** {@link #swap(Character, Persona, IntFunction)} with the game's own fresh pieces. */
    static boolean swap(Character agent, Persona persona) {
        return swap(agent, persona, FRESH);
    }

    /**
     * Swaps the plain starter pieces of the other gender, twins made by {@code fresh} (null when it cannot make one: the
     * piece stays); true when it changed something, then saved.
     */
    static boolean swap(Character agent, Persona persona, IntFunction<Equip> fresh) {
        if (agent == null || persona == null || LivingContent.isHire(persona.id())) {
            return false;
        }
        int gender = agent.getGender();
        List<Planned> plan = new ArrayList<>();
        plan(agent.getInventory(InventoryType.EQUIPPED), true, gender, fresh, plan);
        plan(agent.getInventory(InventoryType.EQUIP), false, gender, fresh, plan);
        if (plan.isEmpty()) {
            return false;
        }
        boolean changed = false;
        boolean wornChanged = false;
        for (Planned planned : plan) {
            if (replace(planned)) {
                log.info("Resident {} swapped its starter piece {} for {} ({} {})", persona.id(), planned.piece().getItemId(),
                        planned.twin().getItemId(), planned.worn() ? "worn slot" : "bag slot", planned.position());
                changed = true;
                wornChanged |= planned.worn();
            }
        }
        if (!changed) {
            return false;
        }
        try {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByCharacterInstance(agent);
            if (entry != null) { // as ResidentKit.wearCarried does after a change
                AgentInventoryReservationRuntime.refreshEquipmentReservations(entry, agent, System.currentTimeMillis());
            }
            if (wornChanged && agent.getMap() != null) {
                agent.equipChanged(); // one look update for onlookers, stats recalculated
            }
        } finally {
            agent.saveCharToDB();
        }
        return true;
    }

    /** Adds each plain starter piece of the other gender in {@code inventory} with its twin; moves nothing. */
    private static void plan(Inventory inventory, boolean worn, int gender, IntFunction<Equip> fresh, List<Planned> plan) {
        if (inventory == null) {
            return;
        }
        for (Item item : inventory.list()) {
            short position = item.getPosition();
            // a worn key is negative, a bag key positive: addItemFromDB drops a negative key outside EQUIPPED
            if (worn ? position >= 0 : position <= 0) {
                continue;
            }
            int twinId = BotCreator.starterTwin(item.getItemId(), gender);
            if (twinId == 0 || !(item instanceof Equip piece)) {
                continue;
            }
            Equip twin = fresh.apply(twinId);
            if (twin == null || !plain(piece, twin)) {
                continue;
            }
            carry(piece, twin);
            twin.setPosition(position);
            plan.add(new Planned(inventory, position, piece, twin, worn));
        }
    }

    /** Every persisted field the swap does not carry is the fresh twin's: nothing on the piece is a player's doing. */
    private static boolean plain(Equip piece, Equip twin) {
        return empty(piece.getOwner()) && empty(piece.getGiftFrom())
                && piece.getFlag() == twin.getFlag() // raw flag: isUntradeable() reads WZ data
                && piece.getExpiration() == twin.getExpiration()
                && piece.getLevel() == twin.getLevel() // successful scrolls
                && piece.getUpgradeSlots() == twin.getUpgradeSlots() // failed scrolls, hammers
                && piece.getVicious() == twin.getVicious()
                && piece.getRingId() == twin.getRingId()
                && piece.getAnvilItemId() == twin.getAnvilItemId();
    }

    private static boolean empty(String text) {
        return text == null || text.isEmpty();
    }

    /** The piece's stats and growth (equip leveling) onto its twin. */
    private static void carry(Equip piece, Equip twin) {
        twin.setStr(piece.getStr());
        twin.setDex(piece.getDex());
        twin.setInt(piece.getInt());
        twin.setLuk(piece.getLuk());
        twin.setHp(piece.getHp());
        twin.setMp(piece.getMp());
        twin.setWatk(piece.getWatk());
        twin.setMatk(piece.getMatk());
        twin.setWdef(piece.getWdef());
        twin.setMdef(piece.getMdef());
        twin.setAcc(piece.getAcc());
        twin.setAvoid(piece.getAvoid());
        twin.setHands(piece.getHands());
        twin.setSpeed(piece.getSpeed());
        twin.setJump(piece.getJump());
        twin.setItemLevel(piece.getItemLevel());
        twin.setItemExp(piece.getItemExp());
    }

    /** Puts {@code twin} in {@code piece}'s key; false when the piece moved since the scan (the next load tries again). */
    private static boolean replace(Planned planned) {
        Inventory inventory = planned.inventory();
        inventory.lockInventory();
        try {
            if (inventory.getItem(planned.position()) != planned.piece()) {
                return false;
            }
            inventory.removeSlot(planned.position());
            inventory.addItemFromDB(planned.twin()); // its position is that key, its sign matches the inventory
            return true;
        } finally {
            inventory.unlockInventory();
        }
    }
}
