package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Collection;

/**
 * What an RJPQ companion may pick up inside its run's event (plan 7.7), as a refuse-only clause for the shared loot
 * eligibility: it never makes a drop lootable that the stock rules refuse. A companion takes a liquid only while it
 * holds none and never one lying in a beaker (it is being poured), a card key never from a closed door's area (it is
 * opening that door), never a letter, and the files only as AgentRjpqDarkLabs.mayTakeFiles allows (a Dark Lab runner
 * in its lab, or a stack it dropped for the leader that it picks back up before it expires). Anything else that would
 * take a new slot is left once the bag is down to the room the claims need (two ETC, one USE, one equip), as a player
 * keeps room for the reward. Mesos are always fine.
 */
final class AgentRjpqLootRule {
    /** One drop as one companion in the run's event sees it. */
    record Facts(int itemId, boolean meso, int held, int freeSlotsOfItsTab, boolean inBeakerArea,
                 boolean inClosedDoorArea, boolean filesMayBeTaken) {
        Facts(int itemId, boolean meso, int held, int freeSlotsOfItsTab, boolean inBeakerArea,
              boolean inClosedDoorArea) {
            this(itemId, meso, held, freeSlotsOfItsTab, inBeakerArea, inClosedDoorArea, false);
        }
    }

    private AgentRjpqLootRule() {
    }

    static boolean allows(Facts facts) {
        if (facts.meso()) {
            return true;
        }
        int itemId = facts.itemId();
        if (itemId == AgentRjpqDefinition.LIQUID_ITEM_ID) {
            return facts.held() == 0 && !facts.inBeakerArea();
        }
        if (itemId == AgentRjpqDefinition.CARD_KEY_ITEM_ID) {
            return !facts.inClosedDoorArea();
        }
        if (itemId == AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID || itemId == AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID) {
            return facts.filesMayBeTaken();
        }
        if (AgentRjpqDefinition.EXCLUSIVE_ITEM_IDS.contains(itemId)) {
            return false;
        }
        InventoryType type = ItemConstants.getInventoryType(itemId);
        boolean newSlot = type == InventoryType.EQUIP || facts.held() == 0;
        return !newSlot || facts.freeSlotsOfItsTab() > reserved(type);
    }

    /** The slots of a tab kept for the claims: the marble (ETC) and the reward (equip or USE). */
    static int reserved(InventoryType type) {
        return switch (type) {
            case ETC -> AgentRjpqAdmissionService.REQUIRED_FREE_ETC_SLOTS;
            case USE -> AgentRjpqAdmissionService.REQUIRED_FREE_USE_SLOTS;
            case EQUIP -> AgentRjpqAdmissionService.REQUIRED_FREE_EQUIP_SLOTS;
            default -> 0;
        };
    }

    /** The facts of one drop for a companion standing in its run's event, read from its map and its bag. */
    static Facts facts(AgentRjpqSession session, Character bot, MapItem drop) {
        int itemId = drop.getItemId();
        boolean meso = drop.getMeso() > 0;
        int held = meso ? 0 : AgentRjpqCoordinator.actions().itemCount(bot, itemId);
        int free = 0;
        if (!meso) {
            var inventory = bot.getInventory(ItemConstants.getInventoryType(itemId));
            free = inventory == null ? 0 : inventory.getNumFreeSlot();
        }
        Point at = drop.getPosition();
        Collection<Reactor> reactors = itemId == AgentRjpqDefinition.LIQUID_ITEM_ID
                || itemId == AgentRjpqDefinition.CARD_KEY_ITEM_ID ? AgentRjpqCoordinator.actions().reactors(bot) : null;
        boolean beaker = false;
        boolean door = false;
        if (reactors != null && at != null) {
            AgentRjpqSide side = session.side();
            String labOne = side.reactorName(AgentRjpqDefinition.LAB_ONE_DOOR);
            String labTwo = side.reactorName(AgentRjpqDefinition.LAB_TWO_DOOR);
            for (Reactor reactor : reactors) {
                String name = reactor.getName();
                if (AgentRjpqDefinition.BEAKER_NAMES.contains(name)) {
                    beaker |= AgentRjpqDefinition.BEAKER_AREA.contains(reactor.getPosition(), at);
                } else if (reactor.getState() == 0 && labOne.equals(name)) {
                    door |= AgentRjpqDefinition.LAB_ONE_DOOR_AREA.contains(reactor.getPosition(), at);
                } else if (reactor.getState() == 0 && labTwo.equals(name)) {
                    door |= AgentRjpqDefinition.LAB_TWO_DOOR_AREA.contains(reactor.getPosition(), at);
                }
            }
        }
        boolean files = (itemId == AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID
                || itemId == AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID)
                && AgentRjpqDarkLabs.mayTakeFiles(session, bot, drop, System.currentTimeMillis());
        return new Facts(itemId, meso, held, free, beaker, door, files);
    }
}
