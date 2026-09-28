package server.living.roster;

import client.Character;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.roster.CompanionPartySync.Reason;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CompanionPartySync.Crew} over the living world's game world: the roster, CompanionSlots and the live
 * parties. It runs on companion-ops; every change goes through the same calls a player's own action would.
 */
final class LiveCrew implements CompanionPartySync.Crew {
    private static final Logger log = LoggerFactory.getLogger(LiveCrew.class);
    private static final CompanionSlots.View NO_PARTY = new CompanionSlots.View(List.of(), List.of(), Map.of(), Map.of());
    /** Overflow stand-downs to one owner this close together are one burst: only the first whispers. */
    static final long OVERFLOW_BURST_MS = 3_000L;

    private final LivingWorld world;
    /** owner id -> when a companion last whispered that it had no seat (companion-ops only). */
    private final Map<Integer, Long> overflowWhisperAtMs = new HashMap<>();

    LiveCrew(LivingWorld world) {
        this.world = world;
    }

    // ---- reads ----------------------------------------------------------------------------

    @Override
    public boolean onlineHuman(int characterId) {
        Character character = online(characterId);
        return character != null && !AgentPresence.isAgent(character);
    }

    @Override
    public String keyOf(int characterId) {
        return world.slots().keyOf(characterId);
    }

    @Override
    public int ownerOf(String key) {
        return world.roster().cachedOwnerOf(key);
    }

    @Override
    public int hostOf(int characterId) {
        CompanionSlots.Reservation held = world.slots().reservation(keyOf(characterId));
        return held != null && held.kind() == CompanionSlots.Kind.GUEST ? held.ownerId() : 0;
    }

    @Override
    public int loaded(String key) {
        Character bot = world.roster().loaded(key);
        return bot == null ? 0 : bot.getId();
    }

    @Override
    public int partyOf(int characterId) {
        Character character = online(characterId);
        Party party = character == null ? null : character.getParty();
        return party == null ? 0 : party.getId();
    }

    @Override
    public List<Integer> members(int partyId) {
        Party party = party(partyId);
        List<Integer> ids = new ArrayList<>();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                ids.add(member.getId());
            }
        }
        return ids;
    }

    @Override
    public boolean companionParty(int partyId) {
        return world.directory().isCompanionParty(party(partyId));
    }

    @Override
    public CompanionSlots.View view(int partyId) {
        Party party = party(partyId);
        return party == null ? NO_PARTY : world.slots().view(party, true);
    }

    @Override
    public int free(int ownerId) {
        Character owner = online(ownerId);
        return owner == null ? 0 : world.slots().free(owner);
    }

    @Override
    public List<String> active(int ownerId) {
        List<String> out = new ArrayList<>();
        for (RosterEntry entry : byLineup(ownerId)) {
            if (entry.state() == RosterState.ACTIVE) {
                out.add(entry.personaId());
            }
        }
        return out;
    }

    @Override
    public List<String> waiting(int ownerId, int partyId) {
        List<RosterEntry> rows = byLineup(ownerId);
        List<String> out = new ArrayList<>();
        for (RosterEntry entry : rows) {
            if (entry.state() == RosterState.ACTIVE && world.roster().loaded(entry.personaId()) == null) {
                out.add(entry.personaId());
            }
        }
        for (RosterEntry entry : rows) {
            String key = entry.personaId();
            if (entry.state() == RosterState.BENCHED && world.slots().waitingForRoom(key)
                    && !world.slots().returnBlocked(key, partyId)) {
                out.add(key);
            }
        }
        return out;
    }

    @Override
    public boolean committed(int characterId) {
        Character character = online(characterId);
        return character != null && character.getEventInstance() != null;
    }

    // ---- changes --------------------------------------------------------------------------

    @Override
    public void standDown(int ownerId, String key, Reason reason, int partyId) {
        Character owner = online(ownerId);
        Persona persona = world.content().persona(key);
        if (owner == null || persona == null) {
            return;
        }
        RosterEntry row = world.roster().entry(ownerId, key);
        boolean out = row != null && row.state() != RosterState.BENCHED;
        String line = reason == Reason.OVERFLOW && out ? overflowLine(owner, persona) : reason.line();
        RosterResult result = world.roster().standDown(owner, persona, line, reason.event());
        if (line != null && line.isEmpty() && result.ok()) {
            owner.dropMessage(5, RosterVoice.line(persona, result)); // the first one of the burst said it already
        }
        if (reason.returnWhenRoom()) {
            world.slots().waitForRoom(key, reason == Reason.EXPELLED ? partyId : 0);
        }
    }

    /**
     * A companion with no seat left says where it goes, in its own words or its duty at camp; when several step out
     * at once only the first whispers, and the rest are noted in pink ("" whispers nothing).
     */
    private String overflowLine(Character owner, Persona persona) {
        long now = System.currentTimeMillis();
        Long last = overflowWhisperAtMs.put(owner.getId(), now);
        return last != null && now - last < OVERFLOW_BURST_MS ? "" : world.roster().overflowLine(persona, owner.getId());
    }

    @Override
    public void guestLeaves(int hostId, int guestId, String line) {
        Character guest = online(guestId);
        Character host = online(hostId);
        if (guest != null) {
            if (line != null && host != null && guest.getClient() != null) {
                host.sendPacket(PacketCreator.getWhisperReceive(guest.getName(), guest.getClient().getChannel() - 1,
                        false, line));
            }
            AgentPartyLifecycleService.leaveAgentParty(guest);
        }
        world.slots().release(keyOf(guestId), CompanionSlots.Kind.GUEST);
        world.store().event(hostId, null, "companion.guest", (guest == null ? guestId : guest.getName()) + " stepped out");
    }

    @Override
    public void releaseSeat(String key) {
        world.slots().release(key);
    }

    @Override
    public void leave(int characterId) {
        Character character = online(characterId);
        Party party = character == null ? null : character.getParty();
        if (party == null) {
            return;
        }
        if (AgentPresence.isAgent(character)) {
            AgentPartyLifecycleService.leaveAgentParty(character);
            return;
        }
        List<Character> members = character.getPartyMembersOnline();
        try (PartyEvents.Scope actor = PartyEvents.actor(characterId)) {
            Party.leaveParty(party, character.getClient());
        }
        character.updatePartySearchAvailability(true);
        character.partyOperationUpdate(party, members);
    }

    /** On a seat held for the join, as the roster seats a companion (the party's seat gate asks for it). */
    @Override
    public boolean seat(int ownerId, String key) {
        Character owner = online(ownerId);
        Character bot = world.roster().loaded(key);
        if (owner == null || bot == null) {
            return false;
        }
        if (owner.getParty() == null && !Party.createParty(owner, true, true)) {
            return false;
        }
        if (!world.slots().reserve(ownerId, key, CompanionSlots.Kind.TRAVEL,
                System.currentTimeMillis() + CompanionSlots.SEAT_HOLD_MS)) {
            return false;
        }
        JoinOutcome joined = AgentPartyLifecycleService.joinAgentToLeaderParty(owner, bot);
        if (joined != JoinOutcome.JOINED && joined != JoinOutcome.ALREADY_MEMBER) {
            world.slots().release(key, CompanionSlots.Kind.TRAVEL);
            return false;
        }
        return true;
    }

    @Override
    public boolean join(int humanId, int partyId) {
        Character human = online(humanId);
        if (human == null) {
            return false;
        }
        try (PartyEvents.Scope actor = PartyEvents.actor(humanId)) {
            return Party.joinParty(human, partyId, false);
        }
    }

    @Override
    public void recall(int ownerId, String key) {
        Character owner = online(ownerId);
        Persona persona = world.content().persona(key);
        if (owner != null && persona != null) {
            world.roster().call(owner, persona);
        }
    }

    @Override
    public void recordLeaderExpel(int partyId, int ownerId) {
        world.slots().recordLeaderExpel(partyId, ownerId);
    }

    @Override
    public void refuse(int humanId, boolean partyFull) {
        Character human = online(humanId);
        if (human != null) {
            human.sendPacket(partyFull ? PacketCreator.partyStatusMessage(17)
                    : PacketCreator.serverNotice(5, "You can't join the party as you are already in one."));
        }
    }

    @Override
    public void drift(int partyId, String detail) {
        log.warn("companion.drift: party {}: {}", partyId, detail);
        world.store().event(0, null, "companion.drift", "party " + partyId + ": " + detail);
    }

    // ---- lookups --------------------------------------------------------------------------

    private Character online(int characterId) {
        return world.roster().onlineCharacter(characterId);
    }

    private Party party(int partyId) {
        try {
            World game = Server.getInstance().getWorld(world.config().WORLD);
            return game == null ? null : game.getParty(partyId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** The owner's roster rows, usual lineup first, then as the roster lists them. */
    private List<RosterEntry> byLineup(int ownerId) {
        Map<String, Integer> slots = world.store().lineupSlots(ownerId);
        List<RosterEntry> rows = new ArrayList<>(world.store().roster(ownerId));
        rows.sort(Comparator.comparingInt(entry -> slots.getOrDefault(entry.personaId(), Integer.MAX_VALUE)));
        return rows;
    }
}
