package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Party quests with companions (docs/BOT_MANAGEMENT_PLAN.md P6, flow 3): a party leader who asks a listed party quest's
 * entry NPC to start it (living_world.COMPANION_PQ_ENABLED_KEYS; Lakelis for "kpq") is offered a lineup of their party
 * and companions instead of the stock start. So far only the gate is here: every click still takes the stock path.
 */
public final class CompanionPartyQuestService {
    private final LivingWorld world;

    public CompanionPartyQuestService(LivingWorld world) {
        this.world = world;
    }

    /**
     * The party leader asked {@code pqKey}'s entry NPC to start it (on their I/O thread, inside the stock script).
     * True when this answered the click; false leaves the stock start to the script, with nothing sent or changed.
     */
    public boolean offerAtDesk(Character leader, String pqKey) {
        if (!world.config().companionPqEnabled(pqKey) || standsAside(leader, pqKey)) {
            return false; // the stock start, untouched
        }
        return false; // a party with companions: its lineup window is not built yet, so it starts as stock meanwhile
    }

    /**
     * True when the stock path takes the click and nothing here is touched: an Agent clicked (a background party's
     * headless entry), there is no party or the clicker does not lead it, a KPQ session already holds the leader or a
     * member, a !kpqtest run (a TEST_OBSERVATION engagement) owns one of them, or nobody in the party is a companion.
     * Owners are looked up only for the party's bots, so a human member never costs a persona lookup.
     */
    boolean standsAside(Character leader, String pqKey) {
        if (leader == null || AgentPresence.isAgent(leader)) {
            return true;
        }
        Party party = leader.getParty();
        if (party == null || party.getLeaderId() != leader.getId()) {
            return true;
        }
        Collection<PartyCharacter> members = party.getMembers();
        List<Integer> ids = new ArrayList<>();
        for (PartyCharacter member : members) {
            ids.add(member.getId());
        }
        if (!AgentKpqSessionRegistry.registrationBlocker(leader.getId(), ids).isEmpty()
                || testRun(AgentPartyQuestEngagementRegistry.forOperator(leader.getId()))) {
            return true;
        }
        boolean companions = false;
        for (PartyCharacter member : members) {
            if (testRun(AgentPartyQuestEngagementRegistry.forMember(member.getId()))) {
                return true;
            }
            companions |= member.isBot() && world.directory().ownerOfCharacter(member.getId()) != 0;
        }
        return !companions;
    }

    private static boolean testRun(AgentPartyQuestEngagement engagement) {
        return engagement != null && engagement.mode() == AgentPartyQuestEngagement.Mode.TEST_OBSERVATION;
    }
}
