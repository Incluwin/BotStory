package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.Skill;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.InventoryGateway;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** User decision 2026-10-13: companions learn from mastery books their owner gives them or they loot. */
class AgentMasteryBooksTest {
    private static final int BRANDISH = 1121008;
    /** A mastery book of Brandish for a Hero: needs Brandish 5, masters it to 20. */
    private static final int BRANDISH_20 = 2290010;
    /** A Night Lord's book: a Hero gets no skill from it (ItemInformationProvider.getSkillStats skillid 0). */
    private static final int TRIPLE_THROW_20 = 2290084;
    private static final int POTION = 2000005;

    private final SkillGateway skills = mock(SkillGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final Skill brandish = mock(Skill.class);
    private final Character hero = mock(Character.class);
    private final List<Item> use = new ArrayList<>();
    private final Map<Integer, Map<String, Integer>> books = new HashMap<>();
    private boolean succeeds = true;

    @BeforeEach
    void aHeroWithBrandishFiveAtMasterTen() {
        when(skills.getSkill(BRANDISH)).thenReturn(brandish);
        when(hero.getJob()).thenReturn(Job.HERO);
        when(hero.isAlive()).thenReturn(true);
        when(hero.getSkillLevel(brandish)).thenReturn((byte) 5);
        when(hero.getMasterLevel(brandish)).thenReturn(10);
        when(hero.changeSkillLevel(any(), anyByte(), anyInt(), anyLong())).thenReturn(true);
        Inventory tab = mock(Inventory.class);
        when(tab.list()).thenReturn(use);
        when(hero.getInventory(InventoryType.USE)).thenReturn(tab);
        books.put(BRANDISH_20, Map.of("skillid", BRANDISH, "masterLevel", 20, "reqSkillLevel", 5, "success", 70));
        books.put(TRIPLE_THROW_20, Map.of("skillid", 0, "masterLevel", 20, "reqSkillLevel", 5, "success", 70));
    }

    private void carry(int itemId, int slot) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        when(item.getPosition()).thenReturn((short) slot);
        when(item.getQuantity()).thenReturn((short) 1);
        use.add(item);
    }

    private boolean read() {
        return AgentMasteryBooks.readOne(hero, skills, inventory, (itemId, jobId) -> {
            assertEquals(Job.HERO.getId(), jobId, "the book is read for the Agent's own job");
            return books.get(itemId);
        }, chance -> {
            assertEquals(70, chance, "the book's own success rate");
            return succeeds;
        });
    }

    @Test
    void aHeroReadsItsBrandishBookAndMastersBrandishToTwenty() {
        carry(POTION, 1);
        carry(TRIPLE_THROW_20, 2);
        carry(BRANDISH_20, 3);

        assertTrue(read());
        verify(inventory).removeFromSlot(hero, InventoryType.USE, (short) 3, (short) 1, false);
        verify(hero).changeSkillLevel(brandish, (byte) 5, 20, -1);
    }

    @Test
    void aFailedRollUsesTheBookUpAndLeavesTheMasterLevel() {
        carry(BRANDISH_20, 4);
        succeeds = false;

        assertFalse(read());
        verify(inventory).removeFromSlot(hero, InventoryType.USE, (short) 4, (short) 1, false);
        verify(hero, never()).changeSkillLevel(any(), anyByte(), anyInt(), anyLong());
    }

    @Test
    void anotherJobsBookABookItCannotReadYetAndOneItHasOutgrownStayInItsBag() {
        carry(TRIPLE_THROW_20, 2);
        assertFalse(read(), "a Night Lord's book");

        carry(BRANDISH_20, 3);
        when(hero.getSkillLevel(brandish)).thenReturn((byte) 4);
        assertFalse(read(), "Brandish 4, the book asks for 5");

        when(hero.getSkillLevel(brandish)).thenReturn((byte) 10);
        when(hero.getMasterLevel(brandish)).thenReturn(20);
        assertFalse(read(), "already mastered to 20");

        verify(inventory, never()).removeFromSlot(any(), any(), anyShort(), anyShort(), any(Boolean.class));
        verify(hero, never()).changeSkillLevel(any(), anyByte(), anyInt(), anyLong());
    }

    @Test
    void itLooksThroughItsBagAtMostEveryTenSeconds() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(hero, null, null);
        when(hero.getInventory(InventoryType.USE)).thenReturn(null); // nothing to read; only the timer counts

        AgentMasteryBooks.tick(entry, hero, 1_000L);
        assertEquals(1_000L + AgentMasteryBooks.CHECK_EVERY_MS, entry.buildState().nextBookCheckAt());
        AgentMasteryBooks.tick(entry, hero, 5_000L);
        assertEquals(1_000L + AgentMasteryBooks.CHECK_EVERY_MS, entry.buildState().nextBookCheckAt(), "not yet");
        AgentMasteryBooks.tick(entry, hero, 11_000L);
        assertEquals(11_000L + AgentMasteryBooks.CHECK_EVERY_MS, entry.buildState().nextBookCheckAt());
    }

    @Test
    void onlySkillAndMasteryBooksAreBooks() {
        assertTrue(AgentMasteryBooks.isBook(2280003));
        assertTrue(AgentMasteryBooks.isBook(BRANDISH_20));
        assertFalse(AgentMasteryBooks.isBook(POTION));
        assertFalse(AgentMasteryBooks.isBook(2300000));
    }
}
