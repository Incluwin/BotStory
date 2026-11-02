package server.living.population.casino;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hosts' rules that need no map (docs/SOLOMAPLING_PORT.md): what a gachapon box holds, the drop game's specials,
 * the words a host listens for, and NX codes a player can type back from a whisper.
 */
class CasinoVenueRulesTest {
    private static final CasinoContent CONTENT = CasinoContent.load(Path.of("scripts/config/population"));

    @Test
    void aGachaponBoxHoldsItsDropsAndAtMostOnePrize() {
        CasinoContent.Gacha gacha = CONTENT.gacha();
        Set<Integer> prizes = new HashSet<>();
        gacha.prizes().forEach(prize -> prizes.add(prize.itemId()));
        Set<Integer> filler = new HashSet<>();
        gacha.filler().values().forEach(tiers -> tiers.values().forEach(filler::addAll));
        Random random = new Random(11);
        int boxesWithPrize = 0;
        long mesos = 0;
        for (int i = 0; i < 2_000; i++) {
            List<ReactorShow.Prize> box = GachaShow.box(gacha, random);
            long won = box.stream().filter(p -> prizes.contains(p.itemId())).count();
            assertTrue(won <= 1);
            boxesWithPrize += (int) won;
            assertEquals(gacha.size() + won, box.size());
            for (ReactorShow.Prize drop : box) {
                if (drop.itemId() == 0) {
                    assertTrue(gacha.mesos().contains(drop.mesos()), "mesos only from the list: " + drop.mesos());
                    mesos += drop.mesos();
                } else {
                    assertTrue(prizes.contains(drop.itemId()) || filler.contains(drop.itemId()), "unknown drop " + drop.itemId());
                }
            }
        }
        double prizeRate = boxesWithPrize / 2_000.0;
        assertEquals(gacha.prizeChance(), prizeRate, 0.02, "the prize comes about as often as casino.yaml says");
        assertTrue(mesos / 2_000.0 < new config.PopulationConfig().GACHA_BOX_PRICE,
                "a box's mesos stay well under its price: " + mesos / 2_000.0);
    }

    @Test
    void theDropGameDropsItsSpecialItemsAboutAThirdOfTheTime() {
        CasinoContent.DropPool pool = CONTENT.dropPools().values().iterator().next();
        Set<Integer> special = new HashSet<>();
        pool.special().forEach(item -> special.add(item.itemId()));
        Random random = new Random(4);
        int specials = 0;
        for (int i = 0; i < 3_000; i++) {
            CasinoContent.Weighted item = DropGame.roll(pool, random);
            assertNotNull(item);
            specials += special.contains(item.itemId()) ? 1 : 0;
        }
        if (!special.isEmpty()) {
            assertEquals(1 / 3.0, specials / 3_000.0, 0.05);
        }
        assertEquals(null, DropGame.roll(null, random));
    }

    @Test
    void aHostHearsWholeWordsOnly() {
        assertTrue(HostPost.says("hit", "hit"));
        assertTrue(HostPost.says("deal me in pls", "deal me in"));
        assertTrue(HostPost.says("ok cho", "cho"));
        assertTrue(HostPost.says("i say han then", "han"));
        assertFalse(HostPost.says("white", "hit"), "a word inside another word is not a call");
        assertFalse(HostPost.says("chocolate", "cho"));
    }

    @Test
    void nxCodesAreThreeGroupsOfFiveWithNoLookAlikes() {
        Random random = new Random(9);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = NxCodes.generate(random);
            assertTrue(code.matches("[A-Z2-9]{5}-[A-Z2-9]{5}-[A-Z2-9]{5}"), code);
            for (char c : code.replace("-", "").toCharArray()) {
                assertTrue(NxCodes.ALPHABET.indexOf(c) >= 0, code);
            }
            assertTrue(code.replace("-", "").length() <= 17, "fits nxcode.code VARCHAR(17)");
            seen.add(code);
        }
        assertEquals(500, seen.size());
        assertFalse(NxCodes.ALPHABET.contains("0") || NxCodes.ALPHABET.contains("O") || NxCodes.ALPHABET.contains("1")
                || NxCodes.ALPHABET.contains("I") || NxCodes.ALPHABET.contains("L"));
    }

    @Test
    void cardsMapToStockMonsterCardsAndTheBack() {
        assertEquals(14, new HashSet<>(Cards.floorItems()).size());
        assertEquals(Cards.CARD_BACK, Cards.floorItems().get(13));
        assertEquals(Cards.item("TH"), Cards.item("TS"), "a rank is one card whatever the suit");
        assertEquals(21, Cards.value(List.of("AH", "AS", "9D")));
        assertTrue(Cards.soft(List.of("AH", "6D")));
        assertFalse(Cards.soft(List.of("AH", "6D", "9C")));
        assertEquals(52, new HashSet<>(Cards.shuffledDeck(new Random(1))).size());
    }
}
