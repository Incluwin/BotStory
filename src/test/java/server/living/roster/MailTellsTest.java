package server.living.roster;

import client.inventory.Equip;
import client.inventory.Item;
import client.processor.npc.DueyProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;

/**
 * The two mail tells of "send it" (docs/BOT_CONCEPT_PLAN.md F3.0): a mailed equip arrives as a fresh drop would, made
 * drop-like (randomizeStats) since the haul keeps only id and count, each piece of an equip stack in a package of its
 * own; and the meso alone go in a package with no item, as a player's meso-only Duey package, not around a Snail Shell.
 * The owner gets Duey's live notice.
 */
class MailTellsTest {
    private static final int SWORD = 1302000;
    private static final int STOLEN_FENCE = 4000021;

    private static void seedHaulMeso(LifecycleHarness game, Persona persona, long meso) {
        RosterEntry row = game.row(persona);
        game.store.seed(new RosterEntry(ANN, persona.id(), RosterState.BENCHED, Assignment.TRAIN, 0, 0, HENESYS, 0L, meso, 0L,
                row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(), 0, null));
    }

    @Test
    void anEquipIsMadeDropLikeAndAStackStaysAStack() {
        List<Integer> made = new ArrayList<>();
        Item sword = RosterService.mailItem(itemId -> {
            made.add(itemId);
            return Equip.restored(itemId, (short) 0);
        }, SWORD, 1);
        assertEquals(List.of(SWORD), made);
        assertEquals(SWORD, sword.getItemId());

        Item fences = RosterService.mailItem(itemId -> {
            throw new AssertionError("a stack is no equip");
        }, STOLEN_FENCE, 143);
        assertEquals(STOLEN_FENCE, fences.getItemId());
        assertEquals(143, fences.getQuantity());
    }

    @Test
    void mesoAloneGoInAPackageWithNoItemAndTheOwnerIsNotified() {
        try (LifecycleHarness game = new LifecycleHarness();
             MockedStatic<DueyProcessor> duey = mockStatic(DueyProcessor.class)) {
            Persona mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, HENESYS);
            seedHaulMeso(game, mira, 500L);

            assertEquals("Mira sends you 1 package with 500 meso by Duey.", game.roster().sendIt(game.ann, mira));

            duey.verify(() -> DueyProcessor.dueyCreatePackage(isNull(), eq(500), isNull(), eq("Mira"), eq(ANN)));
            duey.verify(() -> DueyProcessor.dueyCreatePackage(argThat(item -> item != null), anyInt(), any(), anyString(),
                    anyInt()), never());
            duey.verify(() -> DueyProcessor.dueyNotify(game.ann));
        }
    }

    @Test
    void eachPieceOfAnEquipStackIsAFreshDropInAPackageOfItsOwn() {
        try (LifecycleHarness game = new LifecycleHarness();
             MockedStatic<DueyProcessor> duey = mockStatic(DueyProcessor.class)) {
            Persona mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, HENESYS);
            assertSame(RosterService.DROP_LIKE, game.roster().mailedEquips, "live, a mailed equip is a randomizeStats drop");
            List<Equip> made = new ArrayList<>();
            game.roster().mailedEquips = itemId -> {
                Equip piece = Equip.restored(itemId, (short) 0);
                piece.setWatk((short) (95 + made.size() * 7)); // each one its own roll
                made.add(piece);
                return piece;
            };
            game.store.store.addHaul(ANN, "mira", SWORD, 2);
            game.store.store.addHaul(ANN, "mira", STOLEN_FENCE, 30);
            seedHaulMeso(game, mira, 800L);

            assertEquals("Mira sends you 3 packages with 800 meso by Duey.", game.roster().sendIt(game.ann, mira));

            assertEquals(2, made.size());
            for (Equip piece : made) {
                duey.verify(() -> DueyProcessor.dueyCreatePackage(same(piece), anyInt(), isNull(), eq("Mira"), eq(ANN)));
            }
            duey.verify(() -> DueyProcessor.dueyCreatePackage(argThat(item -> item != null && item.getItemId() == STOLEN_FENCE
                    && item.getQuantity() == 30), eq(0), isNull(), eq("Mira"), eq(ANN)));
            duey.verify(() -> DueyProcessor.dueyCreatePackage(any(), eq(800), isNull(), eq("Mira"), eq(ANN)), times(1));
            assertFalse(game.store.store.haul(ANN, "mira").stream().anyMatch(stack -> stack.quantity() > 0), "the haul is sent");
        }
    }

    @Test
    void oneSendMailsAHundredPackagesAtMostAndTheRestWaitsInTheHaul() {
        try (LifecycleHarness game = new LifecycleHarness();
             MockedStatic<DueyProcessor> duey = mockStatic(DueyProcessor.class)) {
            Persona mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, HENESYS);
            game.roster().mailedEquips = itemId -> Equip.restored(itemId, (short) 0);
            game.store.store.addHaul(ANN, "mira", SWORD, 150);
            game.store.store.addHaul(ANN, "mira", STOLEN_FENCE, 30);

            assertEquals("Mira sends you 100 packages by Duey. The rest is still in its haul.",
                    game.roster().sendIt(game.ann, mira));
            assertEquals(50, game.store.haul.get(FakeLivingStore.key(ANN, "mira")).get(SWORD));
            assertEquals(30, game.store.haul.get(FakeLivingStore.key(ANN, "mira")).get(STOLEN_FENCE));
        }
    }
}
