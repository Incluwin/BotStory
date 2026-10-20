package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqLootRule.Facts;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.maps.MapItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B8: in the Armory a companion takes what its own class can use, leaves Gelt Chocolate to physical
 * attackers, and never takes the Tao materials or what no class uses (storybooks, the chair). Mesos are fine.
 * Outside the Armory the clause refuses nothing.
 */
class AgentCwkpqLootRuleTest {
    @Test
    void geltChocolateIsLeftToPhysicalAttackers() {
        assertFalse(AgentCwkpqLootRule.allows(new Facts(2_022_121, false, Archetype.MAGICIAN, false)));
        assertTrue(AgentCwkpqLootRule.allows(new Facts(2_022_121, false, Archetype.WARRIOR, false)));
        assertTrue(AgentCwkpqLootRule.allows(new Facts(2_022_121, false, Archetype.PIRATE, false)));
    }

    @Test
    void theTaoMaterialsAndWhatNoClassUsesAreLeftToTheHumans() {
        for (int tao : new int[] {4_032_015, 4_032_016, 4_032_017}) {
            assertFalse(AgentCwkpqLootRule.allows(new Facts(tao, false, Archetype.THIEF, true)));
        }
        assertFalse(AgentCwkpqLootRule.allows(new Facts(4_001_107, false, Archetype.THIEF, false)), "a storybook");
        assertFalse(AgentCwkpqLootRule.allows(new Facts(3_010_010, false, Archetype.THIEF, false)), "the chair");
        assertTrue(AgentCwkpqLootRule.allows(new Facts(2_022_277, false, Archetype.THIEF, false)), "a potion");
        assertTrue(AgentCwkpqLootRule.allows(new Facts(0, true, Archetype.THIEF, false)), "mesos");
    }

    @Test
    void equipsBooksAndWeaponScrollsOnlyForItsOwnClass() {
        assertTrue(AgentCwkpqLootRule.allows(new Facts(1_472_051, false, Archetype.THIEF, true)));
        assertFalse(AgentCwkpqLootRule.allows(new Facts(1_472_051, false, Archetype.WARRIOR, false)));
        assertFalse(AgentCwkpqLootRule.allows(new Facts(2_290_011, false, Archetype.MAGICIAN, false)));
        assertTrue(AgentCwkpqLootRule.allows(new Facts(2_044_903, false, Archetype.PIRATE, true)));
        assertFalse(AgentCwkpqLootRule.allows(new Facts(2_044_903, false, Archetype.BOWMAN, false)));
    }

    @Test
    void reqJobBitsAndWeaponScrollsReadAsTheGameDoes() {
        assertTrue(AgentCwkpqLootRule.wearsReqJob(Archetype.MAGICIAN, 0));
        assertTrue(AgentCwkpqLootRule.wearsReqJob(Archetype.PIRATE, 16));
        assertFalse(AgentCwkpqLootRule.wearsReqJob(Archetype.WARRIOR, 8));
        assertTrue(AgentCwkpqLootRule.wearsReqJob(Archetype.BOWMAN, 4 | 8));
        assertFalse(AgentCwkpqLootRule.wearsReqJob(null, 1));

        assertTrue(AgentCwkpqLootRule.scrollsWeapon(2_044_803, 1_482_011), "knuckle scroll on a knuckle");
        assertTrue(AgentCwkpqLootRule.scrollsWeapon(2_044_903, 1_492_012), "gun scroll on a gun");
        assertFalse(AgentCwkpqLootRule.scrollsWeapon(2_044_903, 1_482_011));
        assertTrue(AgentCwkpqLootRule.isWeaponScroll(2_044_803));
        assertFalse(AgentCwkpqLootRule.isWeaponScroll(2_040_804), "a glove scroll is no weapon scroll");
        assertTrue(AgentCwkpqLootRule.isBook(2_290_011));
    }

    @Test
    void outsideTheArmoryNothingIsRefused() {
        Character bot = mock(Character.class);
        when(bot.getMapId()).thenReturn(610_030_600);
        MapItem tao = mock(MapItem.class);
        when(tao.getItemId()).thenReturn(4_032_015);
        assertFalse(AgentCwkpqLootRule.refuses(bot, tao));
    }

    @Test
    void theRuleCoversEveryKindTheArmoryChestsDrop() throws IOException {
        String drops = Files.readString(Path.of("src/main/resources/db/data/131-reactordrops-data.sql"),
                StandardCharsets.UTF_8);
        Matcher row = Pattern.compile("\\(610200[2-5], *(\\d+),").matcher(drops);
        Set<Integer> items = new TreeSet<>();
        while (row.find()) {
            items.add(Integer.parseInt(row.group(1)));
        }
        assertTrue(items.containsAll(Set.of(2_022_121, 4_032_015, 4_032_016, 4_032_017)), items.toString());
        for (int itemId : items) {
            int kind = itemId / 1_000_000;
            boolean judged = kind == 1 || AgentCwkpqLootRule.isBook(itemId) || AgentCwkpqLootRule.isWeaponScroll(itemId)
                    || itemId == AgentCwkpqLootRule.GELT_CHOCOLATE || kind == 2 || kind == 3 || kind == 4;
            assertEquals(true, judged, itemId + " has a rule");
        }
    }
}
