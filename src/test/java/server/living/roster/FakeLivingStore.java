package server.living.roster;

import server.living.store.LivingStore;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An in-memory {@code living_roster}, haul, persona state and event log behind a mocked {@link LivingStore}, with the
 * same write semantics as the SQL: saveRoster is an upsert, saveRosterProgress an UPDATE that keeps the row's state,
 * recruited_at and lineup slot, and removeHaul never goes below zero. Everything else the store does answers the
 * Mockito default.
 */
final class FakeLivingStore {
    /** Keyed by owner:persona. */
    final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    /** Every full-row write (saveRoster), in order. */
    final List<RosterEntry> saved = new ArrayList<>();
    /** owner:persona -> item id -> quantity. */
    final Map<String, Map<Integer, Integer>> haul = new HashMap<>();
    /** "owner persona kind detail" per living_event_log row. */
    final List<String> events = new ArrayList<>();
    final Map<Integer, Integer> levels = new HashMap<>();
    final Map<String, PersonaState> personaStates = new HashMap<>();
    final LivingStore store = mock(LivingStore.class);

    FakeLivingStore() {
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(key(inv.getArgument(0), inv.getArgument(1))));
        when(store.roster(anyInt())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.characterId() == (int) inv.getArgument(0) && e.state() != RosterState.RELEASED).toList());
        when(store.rosterOfPersona(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.personaId().equals(inv.getArgument(0)) && e.state() != RosterState.RELEASED).findFirst().orElse(null));
        when(store.allActive()).thenAnswer(inv -> rows.values().stream().filter(e -> e.state() == RosterState.ACTIVE).toList());
        when(store.allBenched()).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.state() == RosterState.BENCHED || e.state() == RosterState.AWAY).toList());
        when(store.lineupSlots(anyInt())).thenReturn(Map.of());
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(key(entry.characterId(), entry.personaId()), entry);
            saved.add(entry);
            return null;
        }).when(store).saveRoster(any());
        doAnswer(inv -> {
            RosterEntry progress = inv.getArgument(0);
            RosterEntry stored = rows.get(key(progress.characterId(), progress.personaId()));
            if (stored != null) {
                rows.put(key(progress.characterId(), progress.personaId()), new RosterEntry(progress.characterId(),
                        progress.personaId(), stored.state(), progress.assignment(), progress.assignmentItem(),
                        progress.assignmentCount(), progress.campMapId(), progress.accruedExp(), progress.haulMeso(),
                        progress.wagesPaidUntilMs(), stored.recruitedAtMs(), progress.benchedAtMs(), progress.lastLedgerAtMs(),
                        progress.farmSecondsToday(), progress.farmDay()));
            }
            return null;
        }).when(store).saveRosterProgress(any());
        doAnswer(inv -> rows.remove(key(inv.getArgument(0), inv.getArgument(1)))).when(store).deleteRoster(anyInt(), anyString());
        when(store.haul(anyInt(), anyString())).thenAnswer(inv -> {
            List<HaulItem> out = new ArrayList<>();
            haul.getOrDefault(key(inv.getArgument(0), inv.getArgument(1)), Map.of()).forEach((item, quantity) -> {
                if (quantity > 0) {
                    out.add(new HaulItem(item, quantity));
                }
            });
            return out;
        });
        doAnswer(inv -> {
            haul.computeIfAbsent(key(inv.getArgument(0), inv.getArgument(1)), k -> new TreeMap<>())
                    .merge(inv.getArgument(2), (int) inv.getArgument(3), Integer::sum);
            return null;
        }).when(store).addHaul(anyInt(), anyString(), anyInt(), anyInt());
        doAnswer(inv -> {
            Map<Integer, Integer> items = haul.get(key(inv.getArgument(0), inv.getArgument(1)));
            if (items != null) {
                items.computeIfPresent(inv.getArgument(2), (item, quantity) -> Math.max(0, quantity - (int) inv.getArgument(3)));
            }
            return null;
        }).when(store).removeHaul(anyInt(), anyString(), anyInt(), anyInt());
        doAnswer(inv -> events.add(inv.getArgument(0) + " " + inv.getArgument(1) + " " + inv.getArgument(2) + " " + inv.getArgument(3)))
                .when(store).event(anyInt(), any(), anyString(), any());
        when(store.characterLevel(anyInt())).thenAnswer(inv -> levels.getOrDefault((int) inv.getArgument(0), 0));
        when(store.personaState(anyString())).thenAnswer(inv -> personaStates.getOrDefault((String) inv.getArgument(0),
                new PersonaState(inv.getArgument(0), 0, null, 0, 0)));
        doAnswer(inv -> {
            PersonaState state = inv.getArgument(0);
            personaStates.put(state.personaId(), state);
            return null;
        }).when(store).savePersonaState(any());
    }

    void seed(RosterEntry entry) {
        rows.put(key(entry.characterId(), entry.personaId()), entry);
    }

    RosterEntry row(int ownerId, String personaId) {
        return rows.get(key(ownerId, personaId));
    }

    /** Events of this kind, as "owner persona kind detail". */
    List<String> events(String kind) {
        return events.stream().filter(line -> line.split(" ", 4)[2].equals(kind)).toList();
    }

    static String key(int ownerId, String personaId) {
        return ownerId + ":" + personaId;
    }
}
