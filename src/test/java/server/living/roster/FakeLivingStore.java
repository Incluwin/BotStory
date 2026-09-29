package server.living.roster;

import server.living.store.LivingStore;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * recruited_at, lineup slot and eta/hold/park columns, saveLineupSlot sets or clears the lineup slot, and removeHaul never goes below zero. Everything else the store does answers the
 * Mockito default. saveSettings keeps a row's settings_json only while the row exists (an UPDATE, true when it took
 * it), deleteRoster takes its settings_json with it, settings and saveSettings fail as the database would for a key in
 * {@link #unreadable} or {@link #unwritable}, addCharacterMeso (on the economy transaction's connection) adds to
 * {@link #purses} only for a character it has a purse for, and never past the limit (else it throws, failing that
 * commit), characterMeso reads the purses (-1 for none), characterLevelAndJob reads {@link #levels} and {@link #jobs},
 * and living_crew is {@link #crews}.
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
    /** owner:persona -> lineup_slot (the usual lineup), as saveLineupSlot keeps it. */
    final Map<String, Integer> lineup = new HashMap<>();
    /** owner:persona -> settings_json, as saveSettings keeps it. */
    final Map<String, String> settings = new HashMap<>();
    /** Character id -> meso in its characters row (a test seeds the purses it wants). */
    final Map<Integer, Integer> purses = new HashMap<>();
    /** Character id -> job id in its characters row (-1 when a test gives none). */
    final Map<Integer, Integer> jobs = new HashMap<>();
    /** Owner id -> living_crew.formation. */
    final Map<Integer, String> crews = new HashMap<>();
    /** owner:persona keys whose settings_json the database cannot read, or write, right now. */
    final Set<String> unreadable = new java.util.HashSet<>();
    final Set<String> unwritable = new java.util.HashSet<>();
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
        when(store.allInTransit()).thenAnswer(inv -> rows.values().stream().filter(e -> e.state() == RosterState.TRAVELLING
                || e.state() == RosterState.HOLD || e.state() == RosterState.PARKED).toList());
        when(store.lineupSlots(anyInt())).thenAnswer(inv -> {
            Map<String, Integer> out = new HashMap<>();
            lineup.forEach((key, slot) -> {
                RosterEntry entry = rows.get(key);
                if (entry != null && entry.characterId() == (int) inv.getArgument(0) && entry.state() != RosterState.RELEASED) {
                    out.put(entry.personaId(), slot);
                }
            });
            return out;
        });
        doAnswer(inv -> {
            Integer slot = inv.getArgument(2);
            if (slot == null) {
                lineup.remove(key(inv.getArgument(0), inv.getArgument(1)));
            } else {
                lineup.put(key(inv.getArgument(0), inv.getArgument(1)), slot);
            }
            return null;
        }).when(store).saveLineupSlot(anyInt(), anyString(), any());
        when(store.settings(anyInt(), anyString())).thenAnswer(inv -> {
            String key = key(inv.getArgument(0), inv.getArgument(1));
            if (unreadable.contains(key)) {
                throw new LivingStore.ReadFailed(key, new SQLException("unreadable"));
            }
            return rows.containsKey(key) ? settings.get(key) : null;
        });
        when(store.saveSettings(anyInt(), anyString(), any())).thenAnswer(inv -> {
            String key = key(inv.getArgument(0), inv.getArgument(1));
            if (unwritable.contains(key) || !rows.containsKey(key)) {
                return false;
            }
            settings.put(key, inv.getArgument(2));
            return true;
        });
        try {
            doAnswer(inv -> {
                Integer purse = purses.get((int) inv.getArgument(1));
                int meso = inv.getArgument(2);
                if (purse == null || (long) purse + meso > Integer.MAX_VALUE) {
                    throw new SQLException("no room in the purse");
                }
                purses.put(inv.getArgument(1), purse + meso);
                return null;
            }).when(store).addCharacterMeso(any(Connection.class), anyInt(), anyInt());
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        when(store.characterMeso(anyInt())).thenAnswer(inv -> {
            Integer purse = purses.get((int) inv.getArgument(0));
            return purse == null ? -1L : (long) purse;
        });
        when(store.characterLevelAndJob(anyInt())).thenAnswer(inv -> new int[] {
                levels.getOrDefault((int) inv.getArgument(0), 0), jobs.getOrDefault((int) inv.getArgument(0), -1)});
        when(store.crewFormation(anyInt())).thenAnswer(inv -> crews.get((int) inv.getArgument(0)));
        when(store.saveCrewFormation(anyInt(), any())).thenAnswer(inv -> {
            crews.put(inv.getArgument(0), inv.getArgument(1));
            return true;
        });
        doAnswer(inv -> {
            RosterEntry entry = asStored(inv.getArgument(0));
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
                        progress.farmSecondsToday(), progress.farmDay(), stored.etaAtMs(), stored.holdMapId(), stored.parkedAtMs()));
            }
            return null;
        }).when(store).saveRosterProgress(any());
        doAnswer(inv -> {
            settings.remove(key(inv.getArgument(0), inv.getArgument(1))); // the row's settings_json goes with it
            return rows.remove(key(inv.getArgument(0), inv.getArgument(1)));
        }).when(store).deleteRoster(anyInt(), anyString());
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

    /** The row as MySQL keeps it: eta_at and parked_at are whole-second TIMESTAMPs, so their milliseconds are rounded away. */
    static RosterEntry asStored(RosterEntry e) {
        return new RosterEntry(e.characterId(), e.personaId(), e.state(), e.assignment(), e.assignmentItem(),
                e.assignmentCount(), e.campMapId(), e.accruedExp(), e.haulMeso(), e.wagesPaidUntilMs(), e.recruitedAtMs(),
                e.benchedAtMs(), e.lastLedgerAtMs(), e.farmSecondsToday(), e.farmDay(), toSecond(e.etaAtMs()), e.holdMapId(),
                toSecond(e.parkedAtMs()));
    }

    private static long toSecond(long ms) {
        return ms <= 0L ? ms : Math.round(ms / 1000.0d) * 1000L;
    }
}
