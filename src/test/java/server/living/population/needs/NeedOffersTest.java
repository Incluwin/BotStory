package server.living.population.needs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The board's seam to learned offers (I2.2): no learner, the configured premium and nothing heard; one installed, its
 * premium and every ended request NeedDerivation is told of.
 */
class NeedOffersTest {
    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    @Test
    void withoutALearnerThePremiumIsTheConfiguredOne() {
        assertEquals(1.15d, NeedOffers.premium(1, 2000002, 1.15d), 0.0d);
        NeedDerivation.closed(null, 0L); // nothing to tell, nothing thrown
        assertEquals(115L, NeedDerivation.unitOffer(100, NeedOffers.premium(1, 2000002, 1.15d)));
    }

    @Test
    void anInstalledLearnerPricesAndHearsEndings() {
        List<Need> heard = new ArrayList<>();
        NeedOffers.install(new NeedOffers.Learner() {
            @Override
            public double premium(int citizenId, int itemId, double fixed) {
                return 1.25d;
            }

            @Override
            public void closed(Need closed, long nowMs) {
                heard.add(closed);
            }
        });
        assertEquals(125L, NeedDerivation.unitOffer(100, NeedOffers.premium(1, 2000002, 1.15d)));
        Need need = new Need(9, 1, NeedKind.SUPPLY, 2000002, 5, 5, 125L, "", 0, Need.State.MET, 0, 0L, 0, false, 0L, 0L, 1L);
        NeedDerivation.closed(need, 1L);
        assertEquals(List.of(need), heard);
    }
}
