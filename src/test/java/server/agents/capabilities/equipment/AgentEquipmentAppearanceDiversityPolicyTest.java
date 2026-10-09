package server.agents.capabilities.equipment;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEquipmentAppearanceDiversityPolicyTest {
    private static final short CAP = AgentEquipmentAppearanceDiversityPolicy.HAT_SLOT;
    private static final short TOP = AgentEquipmentAppearanceDiversityPolicy.TOP_OR_OVERALL_SLOT;
    private static final short PANTS = AgentEquipmentAppearanceDiversityPolicy.BOTTOM_SLOT;
    /** One !bossgear kit's cap, coat and pants: GearPicks gives two Lv160 warriors these same ids. */
    private static final Map<Short, Integer> KIT = Map.of(CAP, 1_002_377, TOP, 1_040_087, PANTS, 1_060_076);

    @AfterEach
    void clearAgents() {
        AgentRuntimeRegistry.clear();
    }

    @Test
    void selectsAnUnusedAppearanceCandidateWhenOneExists() {
        Equip usedBest = equip(1_002_001);
        Equip unusedNextBest = equip(1_002_002);

        List<Equip> selected = AgentEquipmentAppearanceDiversityPolicy.preferUnusedCandidates(
                List.of(usedBest, unusedNextBest), Set.of(usedBest.getItemId()));

        assertEquals(List.of(unusedNextBest), selected);
    }

    @Test
    void retainsTheOnlyCandidateRatherThanLeavingTheAgentUnequipped() {
        Equip shared = equip(1_002_001);
        List<Equip> candidates = List.of(shared);

        List<Equip> selected = AgentEquipmentAppearanceDiversityPolicy.preferUnusedCandidates(
                candidates, Set.of(shared.getItemId()));

        assertSame(candidates, selected);
    }

    @Test
    void keepsAPieceMadeForThisCharacterEvenWhenSomeoneElseWearsItsLook() {
        Equip ownQuestHelm = equip(1_002_004);
        when(ownQuestHelm.getOwner()).thenReturn("Garrick");
        Equip plainHelm = equip(1_002_025);

        List<Equip> selected = AgentEquipmentAppearanceDiversityPolicy.preferUnusedCandidates(
                List.of(ownQuestHelm, plainHelm), Set.of(ownQuestHelm.getItemId()), "Garrick");

        assertEquals(List.of(ownQuestHelm, plainHelm), selected);
    }

    @Test
    void twoWarriorsInTheSameBossKitBothKeepEveryPieceOfIt() {
        Character thane = agent(1_001, "Thane", Job.HERO);
        Character ingrid = agent(1_002, "Ingrid", Job.DARKKNIGHT);
        Map<Short, Equip> thaneKit = wearKit(thane);
        Map<Short, Equip> ingridKit = wearKit(ingrid);
        // what each wore before !bossgear swapped into its Equip bag, a look no other warrior wears
        Map<Short, List<Equip>> thanePool = pool(thaneKit, Map.of(CAP, 1_002_004, TOP, 1_040_000, PANTS, 1_060_000));
        Map<Short, List<Equip>> ingridPool = pool(ingridKit, Map.of(CAP, 1_002_025, TOP, 1_040_001, PANTS, 1_060_001));

        AgentEquipmentAppearanceDiversityPolicy.preferUnusedSameClassItems(thane, thanePool);
        AgentEquipmentAppearanceDiversityPolicy.preferUnusedSameClassItems(ingrid, ingridPool);

        for (short slot : KIT.keySet()) {
            assertTrue(thanePool.get(slot).contains(thaneKit.get(slot)),
                    "Thane keeps his boss piece in slot " + slot + ": " + thanePool.get(slot));
            assertTrue(ingridPool.get(slot).contains(ingridKit.get(slot)),
                    "Ingrid keeps her boss piece in slot " + slot + ": " + ingridPool.get(slot));
        }
    }

    @Test
    void anUntaggedCopyOfAnotherWarriorsLookIsStillSwappedForAnUnusedOne() {
        Character thane = agent(1_001, "Thane", Job.HERO);
        Character ingrid = agent(1_002, "Ingrid", Job.DARKKNIGHT);
        wearKit(ingrid);
        Equip bossCapCopy = Equip.restored(KIT.get(CAP), (short) 1); // bought or looted: same look, no tag
        Equip madeForIngrid = Equip.restored(KIT.get(TOP), (short) 2);
        madeForIngrid.setOwner("Ingrid");
        Equip unusedCap = Equip.restored(1_002_004, (short) 3);
        Equip unusedTop = Equip.restored(1_040_000, (short) 4);
        Map<Short, List<Equip>> pool = new HashMap<>();
        pool.put(CAP, new ArrayList<>(List.of(bossCapCopy, unusedCap)));
        pool.put(TOP, new ArrayList<>(List.of(madeForIngrid, unusedTop)));

        AgentEquipmentAppearanceDiversityPolicy.preferUnusedSameClassItems(thane, pool);

        assertEquals(List.of(unusedCap), pool.get(CAP), "the tag on the candidate decides, not the one Ingrid wears");
        assertEquals(List.of(unusedTop), pool.get(TOP), "a piece made for someone else is no boss piece");
    }

    private static Equip equip(int itemId) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(itemId);
        return equip;
    }

    private static Character agent(int id, String name, Job job) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getJob()).thenReturn(job);
        when(agent.getInventory(InventoryType.EQUIPPED))
                .thenReturn(new Inventory(agent, InventoryType.EQUIPPED, (byte) 100));
        AgentRuntimeRegistry.registerEntry(new AgentRuntimeEntry(agent, null, null));
        return agent;
    }

    /** Puts the boss kit's cap, coat and pants on, each tagged as !bossgear forges it. */
    private static Map<Short, Equip> wearKit(Character agent) {
        Map<Short, Equip> worn = new HashMap<>();
        KIT.forEach((slot, itemId) -> {
            Equip piece = Equip.restored(itemId, slot);
            piece.setOwner(AgentEquipmentOwnerTags.BOSS_GEAR);
            agent.getInventory(InventoryType.EQUIPPED).addItemFromDB(piece);
            worn.put(slot, piece);
        });
        return worn;
    }

    /** The auto-equip pool per slot: the worn boss piece and the old piece in the bag. */
    private static Map<Short, List<Equip>> pool(Map<Short, Equip> worn, Map<Short, Integer> old) {
        Map<Short, List<Equip>> pool = new HashMap<>();
        short bagSlot = 1;
        for (short slot : KIT.keySet()) {
            pool.put(slot, new ArrayList<>(List.of(worn.get(slot), Equip.restored(old.get(slot), bagSlot++))));
        }
        return pool;
    }
}
