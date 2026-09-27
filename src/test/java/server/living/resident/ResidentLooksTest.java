package server.living.resident;

import org.junit.jupiter.api.Test;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidentLooksTest {
    private final ResidentLooks looks = new ResidentLooks();

    private static Persona persona(String id, Look look) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, look, "test");
    }

    @Test
    void looksAreStablePerPersona() {
        assertEquals(looks.resolve(persona("olwen", Look.NONE)), looks.resolve(persona("olwen", Look.NONE)));
    }

    @Test
    void genderPicksThatGendersFacesAndHair() {
        for (int i = 0; i < 20; i++) {
            ResidentLooks.Resolved female = looks.resolve(persona("f" + i, new Look("female", -1, 0, 0)));
            assertEquals(1, female.gender());
            assertTrue(female.face() >= 21000 && female.face() < 22000, "face " + female.face());
            assertTrue(female.hair() / 1000 == 31 || female.hair() / 1000 == 34, "hair " + female.hair());
            ResidentLooks.Resolved male = looks.resolve(persona("m" + i, new Look("male", -1, 0, 0)));
            assertEquals(0, male.gender());
            assertTrue(male.face() >= 20000 && male.face() < 21000, "face " + male.face());
            assertTrue(male.hair() / 1000 == 30 || male.hair() / 1000 == 33, "hair " + male.hair());
        }
    }

    @Test
    void authoredFieldsWin() {
        assertEquals(new ResidentLooks.Resolved(0, 2, 20005, 30030),
                looks.resolve(persona("pinned", new Look("male", 2, 20005, 30030))));
    }

    @Test
    void aTownFullOfResidentsLooksVaried() {
        Set<Integer> hairs = new HashSet<>();
        Set<Integer> faces = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            ResidentLooks.Resolved look = looks.resolve(persona("resident" + i, Look.NONE));
            hairs.add(look.hair());
            faces.add(look.face());
        }
        assertTrue(hairs.size() >= 25, "distinct hair " + hairs.size());
        assertTrue(faces.size() >= 20, "distinct faces " + faces.size());
    }
}
