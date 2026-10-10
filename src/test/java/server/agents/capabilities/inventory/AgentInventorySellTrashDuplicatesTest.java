package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static server.agents.capabilities.inventory.KeptCopyFixture.overall;
import static server.agents.capabilities.inventory.KeptCopyFixture.scrolledOverall;
import static server.agents.capabilities.inventory.KeptCopyFixture.warriorWithBag;

/**
 * What a shop visit sells when the bag holds several copies of one own-class overall: the kept copy protects only
 * itself, and every other copy is judged by the normal trash rules.
 */
class AgentInventorySellTrashDuplicatesTest {

    @Test
    void threeIdenticalOwnClassOverallsKeepOneAndSellTwo() {
        Equip first = overall(1, 3, 2);
        Equip second = overall(2, 3, 2);
        Equip third = overall(3, 3, 2);

        assertEquals(identitySet(second, third), sold(warriorWithBag(first, second, third)),
                "one identical copy stays, the other two are trash");
    }

    @Test
    void aWeakerCopyOfAKeptPieceIsSold() {
        Equip kept = overall(1, 5, 2);
        Equip weaker = overall(2, 3, 2);

        assertEquals(identitySet(weaker), sold(warriorWithBag(kept, weaker)),
                "the kept copy protects only itself, not every copy of its id");
    }

    @Test
    void aScrolledBestCopyStaysAndThePlainCopyIsJudgedNormally() {
        Equip scrolled = scrolledOverall(1, 5, 2);
        Equip plain = overall(2, 3, 2);

        assertEquals(identitySet(plain), sold(warriorWithBag(scrolled, plain)));
    }

    @Test
    void aWeakerScrolledCopyStaysByItsOwnRuleWhileAWeakerPlainCopyIsSold() {
        Equip keptPlain = overall(1, 4, 2);
        Equip weakerScrolled = scrolledOverall(2, 3, 2);
        Equip weakerPlain = overall(3, 3, 2);

        assertEquals(identitySet(weakerPlain), sold(warriorWithBag(keptPlain, weakerScrolled, weakerPlain)),
                "a scrolled copy is never trash, whichever copy is kept");
    }

    @Test
    void aWeakerCopyWithAGoodRollStaysByItsOwnRule() {
        Equip kept = overall(1, 12, 2);
        Equip goodRoll = overall(2, 10, 2);

        assertEquals(identitySet(), sold(warriorWithBag(kept, goodRoll)),
                "a 10 STR roll is protected by the sell-trash rule, not by the kept copy's id");
    }

    private static Set<Item> sold(Character warrior) {
        InventoryGateway inventory = KeptCopyFixture.inventoryGateway();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(warrior, null, null);
        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
            List<Item> trash = AgentInventorySellTrashService.collectSellTrashEquips(entry, warrior, inventory);
            Set<Item> sold = identitySet();
            sold.addAll(trash);
            assertEquals(trash.size(), sold.size(), "each piece is planned for sale once");
            return sold;
        }
    }

    private static Set<Item> identitySet(Item... items) {
        Set<Item> set = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(set, items);
        return set;
    }
}
