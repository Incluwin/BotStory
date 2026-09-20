package server.living.config;

import server.living.config.Content.Arc;
import server.living.config.Content.Contract;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.Content.Phrase;
import server.living.config.Content.Quest;
import server.living.config.Content.Rumor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything loaded from the content directory, indexed by id. Immutable once built. */
public final class LivingContent {
    private final Map<String, Persona> personas;
    private final Map<String, Persona> personasByName;
    private final Map<String, Quest> quests;
    private final Map<String, Contract> contracts;
    private final Map<String, Arc> arcs;
    private final Map<String, Rumor> rumors;
    private final Map<String, Loadout> loadouts;
    private final List<Phrase> phrases;
    private final List<String> warnings;

    LivingContent(Map<String, Persona> personas,
                  Map<String, Quest> quests,
                  Map<String, Contract> contracts,
                  Map<String, Arc> arcs,
                  Map<String, Rumor> rumors,
                  Map<String, Loadout> loadouts,
                  List<Phrase> phrases,
                  List<String> warnings) {
        this.personas = Collections.unmodifiableMap(new LinkedHashMap<>(personas));
        Map<String, Persona> byName = new LinkedHashMap<>();
        for (Persona persona : personas.values()) {
            byName.put(persona.name().toLowerCase(Locale.ROOT), persona);
        }
        this.personasByName = Collections.unmodifiableMap(byName);
        this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        this.contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        this.arcs = Collections.unmodifiableMap(new LinkedHashMap<>(arcs));
        this.rumors = Collections.unmodifiableMap(new LinkedHashMap<>(rumors));
        this.loadouts = Collections.unmodifiableMap(new LinkedHashMap<>(loadouts));
        this.phrases = List.copyOf(phrases);
        this.warnings = List.copyOf(warnings);
    }

    public static LivingContent empty() {
        return new LivingContent(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    public Map<String, Persona> personas() {
        return personas;
    }

    public Persona persona(String id) {
        return id == null ? null : personas.get(id);
    }

    public Persona personaByName(String name) {
        return name == null ? null : personasByName.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Quest> quests() {
        return quests;
    }

    public Quest quest(String id) {
        return id == null ? null : quests.get(id);
    }

    public List<Quest> questsOf(String personaId) {
        List<Quest> out = new ArrayList<>();
        for (Quest quest : quests.values()) {
            if (quest.persona().equals(personaId)) {
                out.add(quest);
            }
        }
        return out;
    }

    public Map<String, Contract> contracts() {
        return contracts;
    }

    public Contract contract(String id) {
        return id == null ? null : contracts.get(id);
    }

    public Map<String, Arc> arcs() {
        return arcs;
    }

    public Arc arc(String id) {
        return id == null ? null : arcs.get(id);
    }

    public Map<String, Rumor> rumors() {
        return rumors;
    }

    public Rumor rumor(String id) {
        return id == null ? null : rumors.get(id);
    }

    public Map<String, Loadout> loadouts() {
        return loadouts;
    }

    public Loadout loadout(String id) {
        return id == null ? null : loadouts.get(id);
    }

    public List<Phrase> phrases() {
        return phrases;
    }

    public List<String> warnings() {
        return warnings;
    }

    public String census() {
        int recruitable = 0;
        Map<String, Integer> perTown = new LinkedHashMap<>();
        for (Persona persona : personas.values()) {
            if (persona.recruitable()) {
                recruitable++;
            }
            perTown.merge(persona.homeTown(), 1, Integer::sum);
        }
        return "personas=" + personas.size() + " recruitable=" + recruitable + " towns=" + perTown
                + " quests=" + quests.size() + " contracts=" + contracts.size() + " arcs=" + arcs.size()
                + " rumors=" + rumors.size() + " loadouts=" + loadouts.size() + " phrases=" + phrases.size();
    }
}
