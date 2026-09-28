package server.living.roster;

import net.server.world.CauseScope;
import net.server.world.PartyEvents;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyEvents.Snapshot;
import net.server.world.PartyOperation;
import server.living.roster.CompanionPartySync.Reason;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small party world for {@link CompanionPartySync} in plain Java: humans, companions, citizen guests and parties.
 * Party changes publish events the way {@code World.updateParty} does (the actor and cause of the thread, a
 * pre-mutation snapshot), and the seat caches CompanionSlots keeps are applied at once. The roster side mirrors
 * RosterService: a call loads and seats a companion under the roster's cause token, a bench unloads it, a logout
 * unloads the ACTIVE ones and a login restores them while there is room. {@link #settle} runs every queued event
 * through the sync, as companion-ops would, until none is left.
 */
final class FakeParties implements CompanionPartySync.Crew {
    static final int CAP = CompanionSlots.PARTY_CAP;

    static final class Group {
        final int id;
        int leader;
        final List<Integer> members = new ArrayList<>();

        Group(int id, int leader) {
            this.id = id;
            this.leader = leader;
        }
    }

    static final class Companion {
        final String key;
        final int id;
        final int owner;
        final int slot;
        RosterState state = RosterState.BENCHED;
        boolean loaded;

        Companion(String key, int id, int owner, int slot) {
            this.key = key;
            this.id = id;
            this.owner = owner;
            this.slot = slot;
        }
    }

    final Map<Integer, Boolean> humans = new LinkedHashMap<>();
    final Map<String, Companion> companions = new LinkedHashMap<>();
    private final Map<Integer, Companion> byId = new HashMap<>();
    final Set<Integer> citizens = new LinkedHashSet<>();
    /** guest -> host: the GUEST seats. */
    final Map<Integer, Integer> guests = new HashMap<>();
    final Map<Integer, Group> parties = new LinkedHashMap<>();
    final Map<Integer, Integer> partyOf = new HashMap<>();
    final Set<Integer> committed = new HashSet<>();
    final Map<Integer, Map<Integer, Integer>> cuts = new HashMap<>();
    /** returnWhenRoom: key -> the party it may not come back into (0 = none). */
    final Map<String, Integer> waiting = new HashMap<>();
    /** ACTIVE rows a login restore found no seat for (RosterService.seatQuietly keeps them ACTIVE). */
    final Set<String> restoring = new HashSet<>();
    final Deque<PartyChangeEvent> events = new ArrayDeque<>();
    final List<PartyChangeEvent> published = new ArrayList<>();
    /** The sync's changes, in order ("OVERFLOW brin", "seat olwen", "guest 950", ...). */
    final List<String> changes = new ArrayList<>();
    final List<String> drifts = new ArrayList<>();
    final List<String> refusals = new ArrayList<>();
    int loads;
    int doubleLoads;
    long now = 1_000_000L;
    boolean keepPublished = true;
    private int nextParty = 100;
    final CompanionPartySync sync = new CompanionPartySync(this, () -> now);

    // ---- set-up -----------------------------------------------------------------------------

    FakeParties human(int id) {
        humans.put(id, true);
        return this;
    }

    FakeParties companion(String key, int id, int owner, int slot) {
        Companion companion = new Companion(key, id, owner, slot);
        companions.put(key, companion);
        byId.put(id, companion);
        return this;
    }

    FakeParties citizen(int id) {
        citizens.add(id);
        return this;
    }

    Companion companion(String key) {
        return companions.get(key);
    }

    // ---- the world: party operations publish as World.updateParty does -----------------------

    int create(int leader) {
        Group group = new Group(nextParty++, leader);
        group.members.add(leader);
        parties.put(group.id, group);
        partyOf.put(leader, group.id);
        return group.id;
    }

    boolean joinParty(int characterId, int partyId) {
        Group group = parties.get(partyId);
        if (group == null || group.members.size() >= CAP || partyOf.containsKey(characterId)) {
            return false;
        }
        Companion companion = byId.get(characterId);
        Integer host = guests.get(characterId);
        if (companion != null && !group.members.contains(companion.owner) || host != null && !group.members.contains(host)) {
            return false; // the seat gate: a companion joins its owner's party, a guest its host's
        }
        Snapshot before = snapshot(group);
        group.members.add(characterId);
        partyOf.put(characterId, partyId);
        publish(group, PartyOperation.JOIN, characterId, before);
        return true;
    }

    /** Party.leaveParty: the leader leaving disbands. */
    void leaveParty(int characterId) {
        Integer partyId = partyOf.get(characterId);
        if (partyId == null) {
            return;
        }
        Group group = parties.get(partyId);
        Snapshot before = snapshot(group);
        if (group.leader == characterId) {
            parties.remove(partyId);
            for (int member : group.members) {
                partyOf.remove(member);
            }
            publish(group, PartyOperation.DISBAND, characterId, before);
        } else {
            group.members.remove(Integer.valueOf(characterId));
            partyOf.remove(characterId);
            publish(group, PartyOperation.LEAVE, characterId, before);
        }
    }

    /** Op 2: a human leaves (a leader's leave disbands). */
    void quit(int humanId) {
        try (PartyEvents.Scope actor = PartyEvents.actor(humanId)) {
            leaveParty(humanId);
        }
    }

    /** Op 5, by the leader. */
    boolean expel(int leaderId, int targetId) {
        Group group = parties.get(partyOf.getOrDefault(leaderId, 0));
        if (group == null || group.leader != leaderId || targetId == leaderId || !group.members.contains(targetId)) {
            return false;
        }
        try (PartyEvents.Scope actor = PartyEvents.actor(leaderId)) {
            Snapshot before = snapshot(group);
            group.members.remove(Integer.valueOf(targetId));
            partyOf.remove(targetId);
            publish(group, PartyOperation.EXPEL, targetId, before);
        }
        return true;
    }

    /** Op 6, to a human. */
    boolean changeLeader(int leaderId, int targetId) {
        Group group = parties.get(partyOf.getOrDefault(leaderId, 0));
        if (group == null || group.leader != leaderId || targetId == leaderId || !group.members.contains(targetId)
                || !humans.getOrDefault(targetId, false)) {
            return false;
        }
        try (PartyEvents.Scope actor = PartyEvents.actor(leaderId)) {
            Snapshot before = snapshot(group);
            group.leader = targetId;
            publish(group, PartyOperation.CHANGE_LEADER, targetId, before);
        }
        return true;
    }

    /** The logout: the hook takes the owner's companions off first, then LOG_ONOFF and a human-first successor. */
    void logOff(int humanId) {
        for (Companion companion : crewOf(humanId)) {
            if (companion.state == RosterState.ACTIVE && companion.loaded) {
                unload(companion);
            }
        }
        humans.put(humanId, false);
        Group group = parties.get(partyOf.getOrDefault(humanId, 0));
        if (group == null) {
            return;
        }
        try (PartyEvents.Scope actor = PartyEvents.actor(humanId)) {
            publish(group, PartyOperation.LOG_ONOFF, humanId, snapshot(group));
            if (group.leader == humanId) {
                for (int member : group.members) {
                    if (member != humanId && humans.getOrDefault(member, false)) {
                        Snapshot before = snapshot(group);
                        group.leader = member;
                        publish(group, PartyOperation.CHANGE_LEADER, member, before);
                        break;
                    }
                }
            }
        }
    }

    /** The login: LOG_ONOFF, then the restore seats ACTIVE rows while there is room and leaves the rest waiting. */
    void logOn(int humanId) {
        humans.put(humanId, true);
        Group group = parties.get(partyOf.getOrDefault(humanId, 0));
        if (group != null) {
            try (PartyEvents.Scope actor = PartyEvents.actor(humanId)) {
                publish(group, PartyOperation.LOG_ONOFF, humanId, snapshot(group));
            }
        }
        for (Companion companion : crewOf(humanId)) {
            if (companion.state == RosterState.ACTIVE && !companion.loaded) {
                if (free(humanId) > 0) {
                    seatByRoster(companion);
                } else {
                    restoring.add(companion.key);
                }
            }
        }
    }

    // ---- the router and the roster ------------------------------------------------------------

    /** Op 3 through CompanionPartyRouter.accept: a merge or make-room goes to the sync, anything else is stock. */
    void accept(int humanId, int partyId) {
        Group target = parties.get(partyId);
        int own = partyOf.getOrDefault(humanId, 0);
        if (target == null || own == partyId) {
            return;
        }
        boolean merge = own != 0 && companionParty(own);
        boolean full = target.members.size() >= CAP;
        if (!merge && (own != 0 || !full)) {
            if (own != 0) {
                refusals.add("already in a party " + humanId);
                return;
            }
            try (PartyEvents.Scope actor = PartyEvents.actor(humanId)) {
                joinParty(humanId, partyId);
            }
            return;
        }
        sync.accept(humanId, partyId);
    }

    /** A citizen's yes: a GUEST seat charged to the host, then the join (on a timer, no cause). */
    boolean inviteCitizen(int hostId, int citizenId) {
        if (partyOf.containsKey(citizenId) || guests.containsKey(citizenId)) {
            return false;
        }
        int partyId = partyOf.getOrDefault(hostId, 0);
        if (partyId == 0) {
            partyId = create(hostId);
        }
        if (free(hostId) <= 0) {
            return false;
        }
        guests.put(citizenId, hostId);
        if (!joinParty(citizenId, partyId)) {
            guests.remove(citizenId);
            return false;
        }
        return true;
    }

    /** RosterService.call. */
    boolean call(int ownerId, String key) {
        Companion companion = companions.get(key);
        if (!onlineHuman(ownerId) || companion.owner != ownerId) {
            return false;
        }
        if (companion.state == RosterState.ACTIVE && companion.loaded) {
            return true;
        }
        if (free(ownerId) <= 0) {
            return false;
        }
        seatByRoster(companion);
        waiting.remove(key);
        return companion.loaded;
    }

    /** RosterService.bench. */
    void bench(int ownerId, String key) {
        Companion companion = companions.get(key);
        if (companion.owner == ownerId) {
            waiting.remove(key);
            benchRow(companion);
        }
    }

    private void seatByRoster(Companion companion) {
        try (PartyEvents.Scope cause = CauseScope.open(RosterService.CAUSE)) {
            load(companion);
            if (!partyOf.containsKey(companion.owner)) {
                create(companion.owner);
            }
            if (joinParty(companion.id, partyOf.get(companion.owner))) {
                companion.state = RosterState.ACTIVE;
                restoring.remove(companion.key);
            } else {
                unload(companion); // RosterService.refuse: nothing stays loaded, the row stays as it was
            }
        }
    }

    private void load(Companion companion) {
        if (companion.loaded) {
            doubleLoads++;
        }
        companion.loaded = true;
        loads++;
    }

    private void unload(Companion companion) {
        try (PartyEvents.Scope cause = CauseScope.open(RosterService.CAUSE)) {
            leaveParty(companion.id);
        }
        companion.loaded = false;
    }

    private void benchRow(Companion companion) {
        if (companion.state == RosterState.BENCHED) {
            return;
        }
        if (companion.loaded) {
            unload(companion);
        }
        companion.state = RosterState.BENCHED;
        restoring.remove(companion.key);
    }

    List<Companion> crewOf(int ownerId) {
        List<Companion> out = new ArrayList<>();
        for (Companion companion : companions.values()) {
            if (companion.owner == ownerId) {
                out.add(companion);
            }
        }
        out.sort(Comparator.comparingInt(companion -> companion.slot));
        return out;
    }

    // ---- events -------------------------------------------------------------------------------

    /** Runs the queued events through the sync until none is left. */
    void settle() {
        while (!events.isEmpty()) {
            sync.onPartyChanged(events.poll());
        }
    }

    private Snapshot snapshot(Group group) {
        return new Snapshot(group.members, group.leader);
    }

    private void publish(Group group, PartyOperation op, int targetId, Snapshot before) {
        Snapshot after = op == PartyOperation.DISBAND ? Snapshot.EMPTY : snapshot(group);
        PartyChangeEvent event = new PartyChangeEvent(group.id, op, targetId, PartyEvents.currentActor(), before, after,
                CauseScope.current());
        // CompanionSlots.onPartyChanged: guest seats go back with the guest, leader caps and blocks with the party
        switch (op) {
            case LEAVE, EXPEL -> guests.remove(targetId);
            case DISBAND -> {
                cuts.remove(group.id);
                waiting.replaceAll((key, blocked) -> blocked == group.id ? 0 : blocked);
                before.memberIds().forEach(guests::remove);
            }
            default -> { }
        }
        events.add(event);
        if (keepPublished) {
            published.add(event);
        }
    }

    // ---- CompanionPartySync.Crew --------------------------------------------------------------

    @Override
    public boolean onlineHuman(int characterId) {
        return humans.getOrDefault(characterId, false);
    }

    @Override
    public String keyOf(int characterId) {
        Companion companion = byId.get(characterId);
        return companion != null ? companion.key : CompanionSlots.characterKey(characterId);
    }

    @Override
    public int ownerOf(String key) {
        Companion companion = companions.get(key);
        return companion == null ? 0 : companion.owner;
    }

    @Override
    public int hostOf(int characterId) {
        return guests.getOrDefault(characterId, 0);
    }

    @Override
    public int loaded(String key) {
        Companion companion = companions.get(key);
        return companion != null && companion.loaded ? companion.id : 0;
    }

    @Override
    public int partyOf(int characterId) {
        return partyOf.getOrDefault(characterId, 0);
    }

    @Override
    public List<Integer> members(int partyId) {
        Group group = parties.get(partyId);
        return group == null ? List.of() : List.copyOf(group.members);
    }

    @Override
    public boolean companionParty(int partyId) {
        Group group = parties.get(partyId);
        if (group == null) {
            return false;
        }
        int human = 0;
        for (int member : group.members) {
            if (humans.containsKey(member)) {
                if (human != 0) {
                    return false;
                }
                human = member;
            }
        }
        for (int member : group.members) {
            Companion companion = byId.get(member);
            if (companion != null && companion.owner != human) {
                return false;
            }
        }
        return human != 0;
    }

    @Override
    public CompanionSlots.View view(int partyId) {
        Group group = parties.get(partyId);
        if (group == null) {
            return new CompanionSlots.View(List.of(), List.of(), Map.of(), Map.of());
        }
        List<CompanionSlots.Member> members = new ArrayList<>();
        Map<String, Integer> lineup = new HashMap<>();
        for (int member : group.members) {
            if (humans.containsKey(member)) {
                members.add(new CompanionSlots.Member(CompanionSlots.characterKey(member), member, onlineHuman(member), 0, false));
                continue;
            }
            Integer host = guests.get(member);
            int owner = host != null ? host : ownerOf(keyOf(member));
            boolean charged = owner != 0 && onlineHuman(owner) && group.members.contains(owner);
            members.add(new CompanionSlots.Member(keyOf(member), member, false, charged ? owner : 0, host != null));
        }
        for (Companion companion : companions.values()) {
            if (onlineHuman(companion.owner) && group.members.contains(companion.owner)) {
                lineup.put(companion.key, companion.slot);
            }
        }
        return new CompanionSlots.View(members, List.of(), cuts.getOrDefault(partyId, Map.of()), lineup);
    }

    @Override
    public int free(int ownerId) {
        int partyId = partyOf(ownerId);
        CompanionSlots.View view = partyId != 0 ? view(partyId) : new CompanionSlots.View(
                List.of(new CompanionSlots.Member(CompanionSlots.characterKey(ownerId), ownerId, true, 0, false)),
                List.of(), Map.of(), Map.of());
        return CompanionSlots.free(view, ownerId);
    }

    @Override
    public List<String> active(int ownerId) {
        List<String> out = new ArrayList<>();
        for (Companion companion : crewOf(ownerId)) {
            if (companion.state == RosterState.ACTIVE) {
                out.add(companion.key);
            }
        }
        return out;
    }

    @Override
    public List<String> waiting(int ownerId, int partyId) {
        List<String> out = new ArrayList<>();
        for (Companion companion : crewOf(ownerId)) {
            if (companion.state == RosterState.ACTIVE && !companion.loaded) {
                out.add(companion.key);
            }
        }
        for (Companion companion : crewOf(ownerId)) {
            Integer blocked = waiting.get(companion.key);
            if (companion.state == RosterState.BENCHED && blocked != null && (blocked == 0 || blocked != partyId)) {
                out.add(companion.key);
            }
        }
        return out;
    }

    @Override
    public boolean committed(int characterId) {
        return committed.contains(characterId);
    }

    @Override
    public void standDown(int ownerId, String key, Reason reason, int partyId) {
        changes.add(reason + " " + key);
        Companion companion = companions.get(key);
        if (companion == null || companion.owner != ownerId) {
            return;
        }
        benchRow(companion);
        if (reason.returnWhenRoom()) {
            waiting.put(key, reason == Reason.EXPELLED ? partyId : 0);
        }
    }

    @Override
    public void guestLeaves(int hostId, int guestId) {
        changes.add("guest " + guestId);
        leaveParty(guestId);
        guests.remove(guestId);
    }

    @Override
    public void releaseSeat(String key) {
        changes.add("release " + key);
    }

    @Override
    public void leave(int characterId) {
        changes.add("leave " + characterId);
        if (humans.containsKey(characterId)) {
            quit(characterId);
        } else {
            leaveParty(characterId);
        }
    }

    @Override
    public boolean seat(int ownerId, String key) {
        changes.add("seat " + key);
        Companion companion = companions.get(key);
        if (companion == null || !companion.loaded || !onlineHuman(ownerId)) {
            return false;
        }
        if (!partyOf.containsKey(ownerId)) {
            create(ownerId);
        }
        int ownParty = partyOf.get(ownerId);
        if (partyOf.getOrDefault(companion.id, 0) != ownParty) {
            leaveParty(companion.id);
        }
        return joinParty(companion.id, ownParty);
    }

    @Override
    public boolean join(int humanId, int partyId) {
        changes.add("join " + humanId + " " + partyId);
        try (PartyEvents.Scope actor = PartyEvents.actor(humanId)) {
            return joinParty(humanId, partyId);
        }
    }

    @Override
    public void recall(int ownerId, String key) {
        changes.add("recall " + key);
        call(ownerId, key);
    }

    @Override
    public void recordLeaderExpel(int partyId, int ownerId) {
        changes.add("cap " + ownerId);
        cuts.computeIfAbsent(partyId, id -> new HashMap<>()).merge(ownerId, 1, Integer::sum);
    }

    @Override
    public void refuse(int humanId, boolean partyFull) {
        refusals.add((partyFull ? "full " : "already in a party ") + humanId);
    }

    @Override
    public void drift(int partyId, String detail) {
        drifts.add(detail);
    }
}
