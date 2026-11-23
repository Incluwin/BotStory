package server.living.resident;

import org.junit.jupiter.api.Test;
import server.living.config.Content;
import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.config.LivingContent;
import server.living.crew.TieKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residents talk about each other (docs/WORLD_SYSTEMS_PLAN.md N2.2): the tie a Resident has to another of its own town
 * is read from either side's authored relationship (mirrored from theirs) or a marriage, through TieKind.of; a Resident
 * of another town, or one with nothing authored, is no one to talk about.
 */
class ResidentTieLinesTest {
    private static Persona persona(String id, String town, List<Relationship> relationships, String spouse) {
        String name = id.substring(0, 1).toUpperCase() + id.substring(1);
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, town, List.of(), true, null, List.of(), null, relationships, List.of(),
                Map.of("greet", List.of("hi")), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test",
                spouse == null ? null : new Content.Spouse(spouse, null, null));
    }

    @Test
    void eitherSideOfTheTieCountsInTheSameTownOnly() {
        Persona mira = persona("mira", "henesys", List.of(new Relationship("brand", "IS_OWED_BY")), null);
        Persona brand = persona("brand", "henesys", List.of(), null);
        Persona kit = persona("kit", "henesys", List.of(new Relationship("mira", "OWES_A_FAVOUR")), null);
        Persona rook = persona("rook", "henesys", List.of(new Relationship("mira", "RIVAL_OF")), "mira");
        Persona far = persona("far", "perion", List.of(new Relationship("mira", "OLD_FRIEND")), null);
        Persona stranger = persona("stranger", "henesys", List.of(), null);
        Map<String, Persona> all = new LinkedHashMap<>();
        for (Persona p : List.of(mira, brand, kit, rook, far, stranger)) {
            all.put(p.id(), p);
        }
        LivingContent content = mock(LivingContent.class);
        when(content.personas()).thenReturn(all);

        assertEquals(TieKind.OWED, ResidentTieLines.kind(mira, brand), "its own: brand owes it");
        assertEquals(TieKind.OWED, ResidentTieLines.kind(mira, kit), "kit's OWES, mirrored");
        assertEquals(TieKind.SPOUSE, ResidentTieLines.kind(mira, rook), "a marriage first");
        assertNull(ResidentTieLines.kind(mira, stranger));
        List<ResidentTieLines.Tied> tied = ResidentTieLines.tied(content, mira);
        assertEquals(List.of("brand", "kit", "rook"), tied.stream().map(t -> t.other().id()).toList(),
                "far is in Perion, the stranger has no tie");
        assertNull(ResidentTieLines.about(content, mira, far), "no Population: no line, nothing breaks");
    }
}
