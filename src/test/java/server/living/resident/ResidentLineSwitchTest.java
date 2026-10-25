package server.living.resident;

import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * CYGNUS_ARAN_CAREERS_ENABLED (docs/CYGNUS_ARAN_BOTS_PLAN.md): with the switch off the Cygnus Knight and Aran Residents
 * (Aldric, Linnea, Torvin) are not materialized; the Explorers' Residents are, either way.
 */
class ResidentLineSwitchTest {
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private MockedConstruction<ResidentLooks> looks;
    private ResidentRuntime runtime;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(mock(LivingContent.class));
        looks = mockConstruction(ResidentLooks.class); // ResidentLooks reads Character.wz
        runtime = new ResidentRuntime(world);
    }

    @AfterEach
    void close() {
        looks.close();
    }

    @Test
    void knightAndAranResidentsAreHereWithTheSwitchOnAsShipped() {
        assertTrue(config.CYGNUS_ARAN_CAREERS_ENABLED);
        assertTrue(runtime.present(persona("aldric", "DAWNWARRIOR2", "ereve")));
        assertTrue(runtime.present(persona("linnea", "WINDARCHER1", "ereve")));
        assertTrue(runtime.present(persona("torvin", "ARAN2", "rien")));
        assertTrue(runtime.present(persona("yara", "WARRIOR", "perion")));
    }

    @Test
    void withTheSwitchOffOnlyTheExplorersResidentsAreHere() {
        config.CYGNUS_ARAN_CAREERS_ENABLED = false;
        assertFalse(runtime.present(persona("aldric", "DAWNWARRIOR2", "ereve")));
        assertFalse(runtime.present(persona("torvin", "ARAN2", "rien")));
        assertFalse(runtime.present(persona("young", "NOBLESSE", "ereve")));
        assertTrue(runtime.present(persona("yara", "WARRIOR", "perion")));
        assertTrue(runtime.present(persona("olwen", "BEGINNER", "henesys")));
    }

    private static Persona persona(String id, String job, String town) {
        return new Persona(id, id, "", job, new Content.LevelBand(30, 30), Content.Strength.ROOKIE, null, "relaxed-v1",
                null, town, List.of(), true, null, List.of(), null, List.of(), List.of(), Map.of(), -1, -1, -1, List.of(),
                0, Look.NONE, "test");
    }
}
