package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.living.LivingWorld;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player founds a guild with their companions (GPQ plan decision 9, 2026-10-25): at the Guild Headquarters the stock
 * founding wants CREATE_GUILD_MIN_PARTNERS characters on the map in no party and no guild, puts the founder in a new
 * party, and asks each of them to confirm (GuildOperationHandler 0x02, MatchCheckerGuildCreation). A companion is
 * always in its owner's party, so here a guildless companion of the founder's, in the party the founder leads, counts as
 * a cofounder ({@link #cofounder}); a founder whose party holds nobody else keeps it instead of making a new one
 * ({@link #keepsParty}), and a declined or dismissed founding leaves it standing; and as the prompt reaches them each
 * such companion answers yes a moment later, as a player reading it would, and says so in party chat ({@link #asked}).
 * The stock acceptance then makes them Jr. Masters, as cofounders in the founder's party. A companion already in a guild
 * (a hired citizen's) is no cofounder and says so once. Kill switch: living_world.COMPANION_GUILD_COFOUNDERS_ENABLED.
 */
public final class CompanionGuildFounding {
    private static final Logger log = LoggerFactory.getLogger(CompanionGuildFounding.class);
    /** How long a companion reads the founding prompt before it answers, the first one; each next one a little later. */
    static final long READ_MS = 1_500L;
    static final long STAGGER_MS = 700L;
    /** A companion in a guild already says so at most this often. */
    static final long SAY_AGAIN_MS = 60_000L;
    static final List<String> YES = List.of("sure, count me in", "a guild? yes!", "i'm in", "let's do it");
    static final String IN_A_GUILD = "i'm already in a guild, i can't cofound this one";
    /** Companion id -> when it last said it is in a guild already. */
    private static final Map<Integer, Long> SAID = new ConcurrentHashMap<>();

    private final LivingWorld world;

    public CompanionGuildFounding(LivingWorld world) {
        this.world = world;
    }

    private boolean on() {
        return world.config() != null && world.config().COMPANION_GUILD_COFOUNDERS_ENABLED;
    }

    /**
     * True when {@code chr} cofounds {@code founder}'s guild though it is in a party: a guildless companion of the
     * founder's in the party the founder leads (Guild.getEligiblePlayersForGuild). One in a guild already says so.
     */
    public boolean cofounder(Character founder, Character chr) {
        if (!on() || founder == null || chr == null || founder == chr || !ownCompanionInParty(founder, chr)) {
            return false;
        }
        if (chr.getGuildId() > 0) {
            long now = System.currentTimeMillis();
            Long said = SAID.get(chr.getId());
            if (said == null || now - said >= SAY_AGAIN_MS) {
                SAID.put(chr.getId(), now);
                say(chr, IN_A_GUILD);
            }
            return false;
        }
        return true;
    }

    /**
     * True when {@code founder} leads a party in which everyone else is a companion of theirs: the founding keeps that
     * party (the stock one makes a new party and refuses a founder already in one) and a declined founding leaves it.
     */
    public boolean keepsParty(Character founder) {
        Party party = founder == null || !on() ? null : founder.getParty();
        if (party == null || party.getLeaderId() != founder.getId()) {
            return false;
        }
        for (PartyCharacter member : party.getMembers()) {
            if (member.getId() != founder.getId() && world.directory().ownerOfCharacter(member.getId()) != founder.getId()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The founding prompt went out to {@code askedIds}: each companion of the founder's among them answers yes after
     * reading it (MatchCheckerCoordinator.answerMatchConfirmation, as an NPC's confirm does), one after another.
     */
    public void asked(Character founder, Set<Integer> askedIds) {
        if (!on() || founder == null || askedIds == null) {
            return;
        }
        long delay = READ_MS;
        for (Character chr : founder.getMap().getAllPlayers()) {
            if (!askedIds.contains(chr.getId()) || !ownCompanionInParty(founder, chr) || chr.getGuildId() > 0) {
                continue;
            }
            int companionId = chr.getId();
            String line = YES.get(Math.floorMod(companionId, YES.size()));
            world.roster().later("guild.cofound " + companionId, () -> answer(founder, chr, line), delay);
            delay += STAGGER_MS;
        }
    }

    private static void answer(Character founder, Character companion, String line) {
        try {
            boolean taken = founder.getWorldServer().getMatchCheckerCoordinator()
                    .answerMatchConfirmation(companion.getId(), true);
            if (taken) {
                say(companion, line);
            }
            log.info("guild founding: companion {} answered yes for {} (taken={})", companion.getId(), founder.getId(),
                    taken);
        } catch (RuntimeException failure) {
            log.warn("guild founding: companion {} could not answer", companion.getId(), failure);
        }
    }

    private boolean ownCompanionInParty(Character founder, Character chr) {
        int partyId = founder.getPartyId();
        return partyId != -1 && founder.isPartyLeader() && chr.getPartyId() == partyId
                && world.directory().ownerOfCharacter(chr.getId()) == founder.getId();
    }

    private static void say(Character companion, String line) {
        try {
            AgentPartyGatewayRuntime.party().sendPartyChat(companion, line);
        } catch (RuntimeException failure) {
            log.debug("party chat failed for {}", companion.getName(), failure);
        }
    }
}
