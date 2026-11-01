package server.living.population;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A map's name in citizen talk: the field list's, else the game's own. The Balrog lobby is no field, and a citizen
 * leader's whisper said "kk invd, map 105100100 ch1".
 */
class FieldCatalogNameTest {
    @Test
    void aMapNoFieldListsIsNamedAsTheGameNamesIt() {
        FieldCatalog catalog = ShippedFields.population().fields();

        assertEquals("Henesys", catalog.name(100_000_000));
        assertEquals("Bottom of the Temple", catalog.name(105_100_100), "the Balrog lobby, by its WZ name");
        assertEquals("map 999999999", catalog.name(999_999_999), "a map the game does not have either");
    }
}
