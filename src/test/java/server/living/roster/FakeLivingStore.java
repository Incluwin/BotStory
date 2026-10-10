package server.living.roster;

import server.living.store.LivingStore;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.CompanionRunStats;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.PqRunPaceRow;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.living.store.LivingStore.YieldRow;

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
 * living_crew is {@link #crews} (its keep_fresh {@link #keptFresh}) and companion_yield (an upsert by job branch, level band and camp) {@link #yields}.
 * companion_performance is {@link #performance} (saveCompanionRun appends; companionRunStats counts each companion's
 * runs and clears in an activity), and fails as the database would while {@link #performanceUnreadable} or
 * {@link #performanceUnwritable}. pq_run_pace is {@link #paces} (savePqRunPace appends with the next id; pqRunPaces
 * reads a quest's epoch newest first, up to its limit), failing while {@link #pacesUnreadable} or
 * {@link #pacesUnwritable}. dropStoredItems takes the items its predicate picks out of {@link #storedItems} (a position
 * below 0 is worn, above 0 in the Equip tab) and lets its redress move carried ones onto worn slots first. latestEvent
 * reads the newest of {@link #events} with that owner, persona and one of the kinds.
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
    /** owner:LINEUP -> persona ids in place order (companion_named_lineup), as saveNamedLineup keeps them. */
    final Map<String, List<String>> namedLineups = new LinkedHashMap<>();
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
    /** Owners whose living_crew.keep_fresh is 1 ("Keep my crew fresh" on). */
    final Set<Integer> keptFresh = new java.util.LinkedHashSet<>();
    /** "branch:band:camp" -> companion_yield row. */
    final Map<String, YieldRow> yields = new LinkedHashMap<>();
    /** companion_performance, in the order the rows were added. */
    final List<CompanionRunRow> performance = new ArrayList<>();
    boolean performanceUnreadable;
    boolean performanceUnwritable;
    /** pq_run_pace, in the order the rows were added (each given the next id). */
    final List<PqRunPaceRow> paces = new ArrayList<>();
    boolean pacesUnreadable;
    boolean pacesUnwritable;
    /** owner:persona -> item id -> units a live bench stall holds (living_stall_stock). */
    final Map<String, Map<Integer, Integer>> stallStock = new HashMap<>();
    /** owner:persona keys on "Your call" (living_roster.your_call). */
    final Set<String> yourCall = new java.util.LinkedHashSet<>();
    /** Character id -> the item ids in its saved USE tab (a test seeds the ones it wants). */
    final Map<Integer, Set<Integer>> usable = new HashMap<>();
    /**
     * Character id -> the items in its saved inventory (a test seeds the ones it wants), as dropStoredItems reads and
     * rewrites them; each character it rewrote is in {@link #storedWrites}, in order.
     */
    final Map<Integer, List<client.inventory.Item>> storedItems = new HashMap<>();
    final List<Integer> storedWrites = new ArrayList<>();
    /** owner:persona keys whose settings_json the database cannot read, or write, right now. */
    final Set<String> unreadable = new java.util.HashSet<>();
    final Set<String> unwritable = new java.util.HashSet<>();
    /** living_world_flag rows (flag -> set_by). */
    final Map<String, Integer> flags = new LinkedHashMap<>();
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
        when(store.namedLineups(anyInt())).thenAnswer(inv -> {
            Map<String, List<String>> out = new LinkedHashMap<>();
            String owner = inv.getArgument(0) + ":";
            namedLineups.forEach((key, personas) -> {
                if (key.startsWith(owner) && !personas.isEmpty()) {
                    out.put(key.substring(owner.length()), new ArrayList<>(personas));
                }
            });
            return out;
        });
        when(store.saveNamedLineup(anyInt(), anyString(), any())).thenAnswer(inv -> {
            List<String> personas = inv.getArgument(2);
            namedLineups.put(inv.getArgument(0) + ":" + inv.getArgument(1),
                    List.copyOf(personas.subList(0, Math.min(personas.size(), LivingStore.NAMED_LINEUP_PLACES))));
            return true;
        });
        when(store.crewsKeptFresh()).thenAnswer(inv -> new java.util.LinkedHashSet<>(keptFresh));
        when(store.saveCrewKeepFresh(anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(inv -> {
            if ((boolean) inv.getArgument(1)) {
                keptFresh.add(inv.getArgument(0));
            } else {
                keptFresh.remove((Integer) inv.getArgument(0));
            }
            return true;
        });
        when(store.companionYields()).thenAnswer(inv -> List.copyOf(yields.values()));
        when(store.stallStock(anyInt(), anyString())).thenAnswer(inv ->
                new LinkedHashMap<>(stallStock.getOrDefault(key(inv.getArgument(0), inv.getArgument(1)), Map.of())));
        when(store.saveStallStock(anyInt(), anyString(), any())).thenAnswer(inv -> {
            Map<Integer, Integer> units = new LinkedHashMap<>();
            inv.<Map<Integer, Integer>>getArgument(2).forEach((itemId, quantity) -> {
                if (quantity > 0) {
                    units.put(itemId, quantity);
                }
            });
            if (units.isEmpty()) {
                stallStock.remove(key(inv.getArgument(0), inv.getArgument(1)));
            } else {
                stallStock.put(key(inv.getArgument(0), inv.getArgument(1)), units);
            }
            return true;
        });
        when(store.yourCalls()).thenAnswer(inv -> {
            Map<Integer, Set<String>> out = new HashMap<>();
            for (String row : yourCall) {
                String[] parts = row.split(":", 2);
                out.computeIfAbsent(Integer.parseInt(parts[0]), owner -> new java.util.LinkedHashSet<>()).add(parts[1]);
            }
            return out;
        });
        when(store.saveYourCall(anyInt(), anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(inv -> {
            String row = key(inv.getArgument(0), inv.getArgument(1));
            if ((boolean) inv.getArgument(2)) {
                yourCall.add(row);
            } else {
                yourCall.remove(row);
            }
            return true;
        });
        when(store.usableItems(anyInt())).thenAnswer(inv -> new java.util.LinkedHashSet<>(usable.getOrDefault((int) inv.getArgument(0), Set.of())));
        when(store.saveCompanionRun(any())).thenAnswer(inv -> !performanceUnwritable && performance.add(inv.getArgument(0)));
        when(store.companionRunStats(anyString())).thenAnswer(inv -> {
            if (performanceUnreadable) {
                throw new LivingStore.ReadFailed("companion_performance", new SQLException("unreadable"));
            }
            Map<Integer, CompanionRunStats> out = new HashMap<>();
            for (CompanionRunRow row : performance) {
                if (row.activity().equals(inv.getArgument(0))) {
                    CompanionRunStats known = out.getOrDefault(row.companionCharacterId(), new CompanionRunStats(0, 0));
                    out.put(row.companionCharacterId(), new CompanionRunStats(known.runs() + 1,
                            known.clears() + (row.outcome().equals("cleared") ? 1 : 0)));
                }
            }
            return out;
        });
        when(store.savePqRunPace(any())).thenAnswer(inv -> {
            if (pacesUnwritable) {
                return false;
            }
            PqRunPaceRow row = inv.getArgument(0);
            return paces.add(new PqRunPaceRow(paces.size() + 1L, row.pqKey(), row.sessionId(), row.leaderId(),
                    row.partySize(), row.humans(), row.epoch(), row.outcome(), row.estP10S(), row.estP50S(),
                    row.estP90S(), row.estRecords(), row.actualS(), row.startedAtMs(), row.clearedAtMs(), row.spans(),
                    row.members(), row.drawn(), row.recordedAtMs()));
        });
        when(store.pqRunPaces(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            if (pacesUnreadable) {
                throw new LivingStore.ReadFailed("pq_run_pace", new SQLException("unreadable"));
            }
            List<PqRunPaceRow> out = new ArrayList<>();
            for (int i = paces.size() - 1; i >= 0 && out.size() < (int) inv.getArgument(2); i--) {
                PqRunPaceRow row = paces.get(i);
                if (row.pqKey().equals(inv.getArgument(0)) && row.epoch() == (int) inv.getArgument(1)) {
                    out.add(row);
                }
            }
            return out;
        });
        when(store.saveCompanionYield(any())).thenAnswer(inv -> {
            YieldRow row = inv.getArgument(0);
            yields.put(row.jobBranch() + ":" + row.levelBand() + ":" + row.campMapId(), row);
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
        when(store.latestEvent(anyInt(), anyString(), any())).thenAnswer(inv -> {
            String who = inv.getArgument(0) + " " + inv.getArgument(1);
            List<String> kinds = inv.getArgument(2);
            for (int i = events.size() - 1; i >= 0; i--) {
                String[] row = events.get(i).split(" ", 4);
                if ((row[0] + " " + row[1]).equals(who) && kinds.contains(row[2])) {
                    return java.util.Optional.of(new LivingStore.EventRow(row[2], row.length > 3 ? row[3] : null));
                }
            }
            return java.util.Optional.empty();
        });
        when(store.characterLevel(anyInt())).thenAnswer(inv -> levels.getOrDefault((int) inv.getArgument(0), 0));
        when(store.personaState(anyString())).thenAnswer(inv -> personaStates.getOrDefault((String) inv.getArgument(0),
                new PersonaState(inv.getArgument(0), 0, null, 0, 0)));
        doAnswer(inv -> {
            PersonaState state = inv.getArgument(0);
            personaStates.put(state.personaId(), state);
            return null;
        }).when(store).savePersonaState(any());
        when(store.worldFlags()).thenAnswer(inv -> new java.util.LinkedHashSet<>(flags.keySet()));
        doAnswer(inv -> flags.putIfAbsent(inv.getArgument(0), inv.getArgument(1))).when(store).setWorldFlag(anyString(), anyInt());
        doAnswer(inv -> flags.remove((String) inv.getArgument(0))).when(store).clearWorldFlag(anyString());
        when(store.dropStoredItems(anyInt(), any(), any())).thenAnswer(inv -> {
            List<client.inventory.Item> items = storedItems.get((int) inv.getArgument(0));
            java.util.function.Predicate<client.inventory.Item> drop = inv.getArgument(1);
            if (items == null || items.stream().noneMatch(drop)) {
                return 0;
            }
            int before = items.size();
            List<client.inventory.Item> takenWorn = items.stream()
                    .filter(item -> drop.test(item) && item.getPosition() < 0).toList();
            items.removeIf(drop);
            LivingStore.Redress redress = inv.getArgument(2);
            redress.apply(takenWorn, items.stream().filter(item -> item.getPosition() < 0).toList(),
                    items.stream().filter(item -> item.getPosition() > 0).toList());
            storedWrites.add(inv.getArgument(0));
            return before - items.size();
        });
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
