package server.living.resident;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.roster.ui.CompanionMenus;
import tools.DatabaseConnection;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A hired citizen ({@code hire:<characterId>}, docs/BOT_MANAGEMENT_PLAN.md P4) keeps its own face, gear and level, and it is
 * no Resident: no look is written for it, the wardrobe and the shaper leave it alone, it speaks under the Bulletin
 * Board (never a borrowed townsperson), and it has no arc and offers no quests.
 */
class HireGuardsTest {
    private static final Persona HIRE = new Persona("hire:950", "Mira", "", "HUNTER", new Content.LevelBand(30, 30),
            Content.Strength.SEASONED, null, "relaxed-v1", null, "henesys", List.of(), false, LivingContent.HIRED_HAND_CONTRACT,
            List.of("fenna-first-shot"), "fenna-arc", List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0,
            new Content.Look("female", 2, 21001, 31002), "living_citizen");
    private static final Loadout OUTFIT = new Loadout("hunter", List.of(1452002), List.of(), Map.of(),
            Map.of("hat", List.of(1002067)));

    @Test
    void noLookIsEverWrittenForAHire() {
        try (MockedConstruction<ResidentLooks> looks = mockConstruction(ResidentLooks.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            ResidentLooks real = new ResidentLooks();

            assertFalse(real.apply(HIRE, 950));

            db.verifyNoInteractions();
            assertEquals(1, looks.constructed().size());
        }
    }

    @Test
    void theWardrobeAndTheShaperLeaveAHireAlone() {
        Character agent = mock(Character.class);

        assertFalse(ResidentWardrobe.dress(agent, HIRE, OUTFIT));
        ResidentShaper.shape(agent, HIRE, OUTFIT, 7);

        verifyNoInteractions(agent);
    }

    @Test
    void aHireSpeaksUnderTheBulletinBoard() {
        assertEquals(CompanionMenus.BOARD_NPC, ResidentDialogue.portraitOf(HIRE));
        assertEquals(9040011, CompanionMenus.BOARD_NPC);
    }

    @Test
    void aHireHasNoArcAndOffersNoQuests() {
        LivingWorld world = mock(LivingWorld.class);
        LivingContent content = mock(LivingContent.class);
        when(world.content()).thenReturn(content);
        server.living.store.LivingStore store = mock(server.living.store.LivingStore.class);
        when(world.store()).thenReturn(store);
        when(content.arc("fenna-arc")).thenReturn(new Content.Arc("fenna-arc", "hire:950", List.of(), "test"));

        assertNull(new ArcService(world).arcOf(HIRE));
        assertTrue(new QuestLedger(world).available(mock(Character.class), HIRE).isEmpty());
        verifyNoInteractions(store); // nothing read for it: no quest state, no flags
    }
}
