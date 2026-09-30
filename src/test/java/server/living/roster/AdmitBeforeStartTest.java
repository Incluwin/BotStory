package server.living.roster;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Lakelis answers a refused admission in her own words (P6, flow 3): every refusal admission, the engagement registry
 * and the session registry can give (AgentKpqAdmissionService.validate, prepare and admitFromLobby) becomes one of her
 * lines, with the member's name kept, never the log text ("KPQ admission is already reserved: member-901"). One she has
 * no line for is the generic one.
 */
class AdmitBeforeStartTest {
    private static final Map<String, String> REFUSALS = new LinkedHashMap<>();

    static {
        // validate
        REFUSALS.put("Operator, leader, and party members are required", LakelisLines.EVERYONE_HERE);
        REFUSALS.put("The current Kerning event accepts three or four members", LakelisLines.THREE_OR_FOUR);
        REFUSALS.put("Agent-assisted KPQ requires at least one Agent execution participant", LakelisLines.EVERYONE_HERE);
        REFUSALS.put("The event leader has no party", LakelisLines.LEADER_ONLY);
        REFUSALS.put("Every authoritative party member must be included and online for KPQ", LakelisLines.EVERYONE_HERE);
        REFUSALS.put("The selected event leader is not the party leader", LakelisLines.LEADER_ONLY);
        REFUSALS.put("Every KPQ member must already be in the event leader's party", LakelisLines.EVERYONE_HERE);
        REFUSALS.put("Mira is not at the Kerning KPQ entrance", "Mira isn't here yet.");
        REFUSALS.put("Mira must be level 21-30 for KPQ", "Mira needs to be between level 21 and 30 for this.");
        REFUSALS.put("Every KPQ member must be on the same world and channel", LakelisLines.THIS_CHANNEL);
        REFUSALS.put("Mira needs free Use and ETC capacity for KPQ items and rewards",
                "Mira's bags are full. Free a Use and an Etc slot first.");
        // prepare
        REFUSALS.put("Tobi could not leave its current activity", "Tobi is busy with something. Give them a moment.");
        // admitFromLobby
        REFUSALS.put("KPQ admission requires a registered matching lobby engagement", LakelisLines.CATCHING_BREATH);
        REFUSALS.put("The authoritative party changed before KPQ entry", LakelisLines.CATCHING_BREATH);
        REFUSALS.put("Tobi is not owned by this KPQ lobby", "Tobi is busy with something. Give them a moment.");
        REFUSALS.put("KPQ admission is already reserved: operator-session", LakelisLines.CATCHING_BREATH);
        REFUSALS.put("KPQ admission is already reserved: member-901", LakelisLines.CATCHING_BREATH);
        // the engagement and session registries
        REFUSALS.put("operator already owns a party-quest engagement", LakelisLines.CATCHING_BREATH);
        REFUSALS.put("party-quest member already belongs to another engagement", LakelisLines.CATCHING_BREATH);
        REFUSALS.put("Operator already owns a KPQ session", LakelisLines.CATCHING_BREATH);
        REFUSALS.put("Party member already belongs to another KPQ session", LakelisLines.CATCHING_BREATH);
    }

    @Test
    void everyRefusalGetsHerOwnLineWithTheNameKept() {
        for (Map.Entry<String, String> refusal : REFUSALS.entrySet()) {
            String line = LakelisLines.forAdmit(refusal.getKey());

            assertEquals(refusal.getValue(), line, refusal.getKey());
            assertFalse(line.equals(LakelisLines.GENERIC), refusal.getKey());
            assertFalse(line.contains("KPQ admission"), refusal.getKey());
        }
    }

    @Test
    void anUnknownRefusalGetsTheGenericLine() {
        assertEquals(LakelisLines.GENERIC, LakelisLines.forAdmit("unknown admission failure"));
        assertEquals(LakelisLines.GENERIC, LakelisLines.forAdmit("Cannot invoke \"client.Character.getId()\""));
        assertEquals(LakelisLines.GENERIC, LakelisLines.forAdmit(null));
    }

    @Test
    void theStockStartsRefusalsAreTheScriptsWordForWord() {
        assertEquals("Another party has already entered the #rParty Quest#k in this channel. Please try another "
                + "channel, or wait for the current party to finish.", LakelisLines.forStart(PqEntryPort.Start.NO_LOBBY));
        assertEquals("You cannot start this party quest yet, because either your party is not in the range size, some "
                        + "of your party members are not eligible to attempt it or they are not in this map. If you're "
                        + "having trouble finding party members, try Party Search.",
                LakelisLines.forStart(PqEntryPort.Start.NOT_ELIGIBLE));
        assertEquals("The Kerning PQ has encountered an error.", LakelisLines.forStart(PqEntryPort.Start.NO_EVENT));
        assertEquals("Another party has already entered the Party Quest in this channel. Please try another channel, "
                + "or wait for the current party to finish.", LakelisLines.plain(LakelisLines.NO_LOBBY), "pink text");
    }
}
