package server.living.crew;

import server.living.config.Content;
import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.config.Content.Spouse;
import server.living.crew.CrewTies.SharedClear;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared fakes for the crew tests: personas, and CrewTies ports over plain maps. */
final class CrewKit {
    static final int OWNER = 10;

    private CrewKit() {
    }

    static Persona persona(String id, boolean recruitable, List<Relationship> relationships, String spouse) {
        String name = id.substring(0, 1).toUpperCase() + id.substring(1);
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "henesys", List.of(), recruitable, null, List.of(), null, relationships, List.of(),
                Map.of("greet", List.of("hi")), -1, -1, -1, List.of(), 0, Content.Look.NONE, "test",
                spouse == null ? null : new Spouse(spouse, null, null));
    }

    static Relationship rel(String persona, String kind) {
        return new Relationship(persona, kind);
    }

    /** CrewTies ports over maps: personas, their characters, owners and loaded partners, and the owners' clears. */
    static final class TiePorts implements CrewTies.Ports {
        final Map<String, Persona> personas = new HashMap<>();
        final Map<String, Integer> characters = new HashMap<>();
        final Map<String, Integer> owners = new HashMap<>();
        final Map<Integer, Integer> partners = new HashMap<>();
        final Map<Integer, List<SharedClear>> clears = new HashMap<>();
        boolean readFails;
        int reads;

        TiePorts add(Persona persona, int characterId, int ownerId) {
            personas.put(persona.id(), persona);
            characters.put(persona.id(), characterId);
            if (ownerId > 0) {
                owners.put(persona.id(), ownerId);
            }
            return this;
        }

        /** A hire: its persona id is "hire:<id>", with no relationships or spouse. */
        TiePorts hire(int characterId, int ownerId) {
            return add(CrewKit.persona("hire:" + characterId, false, List.of(), null), characterId, ownerId);
        }

        @Override
        public Persona persona(String personaId) {
            return personas.get(personaId);
        }

        @Override
        public Collection<Persona> personas() {
            return personas.values();
        }

        @Override
        public int characterIdOf(String personaId) {
            return characters.getOrDefault(personaId, 0);
        }

        @Override
        public String personaIdOfCharacter(int characterId) {
            for (Map.Entry<String, Integer> entry : characters.entrySet()) {
                if (entry.getValue() == characterId) {
                    return entry.getKey();
                }
            }
            return null;
        }

        @Override
        public int ownerOf(String personaId) {
            return owners.getOrDefault(personaId, 0);
        }

        @Override
        public int partnerIdOf(int characterId) {
            return partners.getOrDefault(characterId, -1);
        }

        @Override
        public List<SharedClear> clearsWith(int ownerId) {
            reads++;
            if (readFails) {
                throw new IllegalStateException("database down");
            }
            return clears.getOrDefault(ownerId, new ArrayList<>());
        }

        @Override
        public Map<TieKind, Integer> seeds() {
            return BanterCatalog.defaultSeeds();
        }

        @Override
        public int clearPoints() {
            return 3;
        }

        @Override
        public int friendAffinity() {
            return 40;
        }
    }
}
