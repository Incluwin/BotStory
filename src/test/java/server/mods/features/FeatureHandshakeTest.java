package server.mods.features;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.mods.ServerMods;
import server.mods.features.FeaturePackets.Answer;
import server.mods.features.FeaturePackets.Feature;
import server.mods.features.FeaturePackets.Report;
import server.mods.features.FeaturePolicy.Decision;
import server.mods.features.FeaturePolicy.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.mods.features.FeaturePackets.FLAG_LIVE;
import static server.mods.features.FeaturePackets.FLAG_SERVER_GATED;
import static server.mods.features.FeaturePackets.STATE_ACTIVE;
import static server.mods.features.FeaturePackets.STATE_ATTACHED;

/**
 * The handshake around the policy table: a REPORT is kept on the connection and answered with a POLICY, an APPLIED is
 * only kept, a lacking required feature pops up a message, the policy switch off answers allow for everything, and a
 * policy change reaches every connection that reported (on its own I/O thread) and no other.
 */
class FeatureHandshakeTest {
    private static final Feature HP_TAG = new Feature("mob_hp_tag", "Mob HP bar with numbers", 1, FLAG_LIVE,
            STATE_ATTACHED | STATE_ACTIVE);
    private static final Feature ADDY = new Feature("free_addy", "Gameplay patches", 1, FLAG_SERVER_GATED | FLAG_LIVE,
            STATE_ATTACHED | STATE_ACTIVE);
    private static final Feature CREW_OFF = new Feature("crew_window", "Crew window", 1, 0, 0);

    private final ServerMods mods = new ServerMods(ServerMods.ALL, Map.of());
    private final FeatureHandshake handshake = new FeatureHandshake(mods,
            FeaturePolicy.parse(Map.of("free_addy", "allow"), "deny"));

    private final Client client = mock(Client.class);
    private final Character chr = mock(Character.class);
    private Report kept;

    FeatureHandshakeTest() {
        when(chr.getName()).thenReturn("Bossfriend");
        when(chr.getClient()).thenReturn(client);
        doAnswer(inv -> kept = inv.getArgument(0)).when(client).setClientFeatures(any());
        when(client.getClientFeatures()).thenAnswer(inv -> kept);
    }

    private static Report report(Feature... features) {
        return new Report(false, 1, 20261017, List.of(features));
    }

    private byte[] sentPolicy() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        return sent.getValue().getBytes();
    }

    @Test
    void aReportIsKeptAndAnsweredFromThePolicy() {
        Report report = report(HP_TAG, ADDY);
        handshake.receive(client, chr, report);
        assertEquals(report, kept);
        assertArrayEquals(FeaturePackets.policy(1, List.of(
                new Answer("mob_hp_tag", Decision.ALLOW), new Answer("free_addy", Decision.ALLOW))).getBytes(), sentPolicy());
        verify(chr, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void theAnswerSpeaksTheLowerProtocol() {
        handshake.receive(client, chr, new Report(false, 7, 0, List.of(HP_TAG)));
        assertArrayEquals(FeaturePackets.policy(1, List.of(new Answer("mob_hp_tag", Decision.ALLOW))).getBytes(),
                sentPolicy());
    }

    @Test
    void anAppliedListIsKeptAndNeverAnswered() {
        Report applied = new Report(true, 1, 20261017, List.of(HP_TAG));
        handshake.receive(client, chr, applied);
        assertEquals(applied, kept);
        verify(client, never()).sendPacket(any());
    }

    @Test
    void aLackingRequiredFeaturePopsUpWhatToDo() {
        handshake.setRule("crew_window", new Rule(Decision.REQUIRE, 0));
        handshake.receive(client, chr, report(CREW_OFF));
        verify(chr).dropMessage(1, "This server needs client features your game does not have on:\r\n"
                + "- Crew window: switched off. Turn it on in the Features page (hold Shift as Launch Game.exe starts),"
                + " then restart the game.");
    }

    @Test
    void aDenyThatWaitsForTheNextStartIsSaidInChat() {
        handshake.setRule("crew_window", new Rule(Decision.DENY, 0));
        handshake.receive(client, chr, report(new Feature("crew_window", "Crew window", 1, 0, STATE_ATTACHED | STATE_ACTIVE)));
        verify(chr).dropMessage(5, "Client features at your next start: Crew window off (the server turned it off).");
    }

    @Test
    void withThePolicySwitchOffEverythingIsAllowed() {
        handshake.setRule("mob_hp_tag", new Rule(Decision.DENY, 0));
        mods.set(ServerMods.CLIENT_FEATURE_POLICY, false);
        handshake.receive(client, chr, report(HP_TAG, new Feature("new_mod", "", 1, FLAG_SERVER_GATED, STATE_ATTACHED)));
        assertArrayEquals(FeaturePackets.policy(1, List.of(
                new Answer("mob_hp_tag", Decision.ALLOW), new Answer("new_mod", Decision.ALLOW))).getBytes(), sentPolicy());
        assertEquals(FeaturePolicy.ALLOW_ALL, handshake.effectivePolicy());
    }

    @Test
    void aPolicyChangeReachesEveryClientThatReportedOnItsOwnThread() {
        handshake.receive(client, chr, report(HP_TAG));
        Client silent = mock(Client.class);
        Character oldClientPlayer = mock(Character.class);
        when(oldClientPlayer.getClient()).thenReturn(silent);
        List<Runnable> io = new ArrayList<>();
        doAnswer(inv -> io.add(inv.getArgument(0))).when(client).runOnIoThread(any());

        handshake.setRule("mob_hp_tag", new Rule(Decision.DENY, 0));
        handshake.policyChanged(List.of(chr, oldClientPlayer));
        verify(silent, never()).runOnIoThread(any());
        verify(silent, never()).sendPacket(any());
        assertEquals(1, io.size());
        io.get(0).run();

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, org.mockito.Mockito.times(2)).sendPacket(sent.capture());
        assertArrayEquals(FeaturePackets.policy(1, List.of(new Answer("mob_hp_tag", Decision.DENY))).getBytes(),
                sent.getAllValues().get(1).getBytes());
    }

    @Test
    void theMessagesNameEachLackAndChange() {
        assertNull(FeatureHandshake.lackingMessage(List.of()));
        assertEquals("This server needs client features your game does not have on:\r\n"
                        + "- fancy_ui: your game does not have it. Close the game and start Launch Game.exe again to update it.\r\n"
                        + "- Tool tips: version 1 here, the server needs 2 or newer. Close the game and start Launch Game.exe"
                        + " again to update it.\r\n"
                        + "- Boss HP: kept off by an earlier server veto. It comes back when you restart the game.",
                FeatureHandshake.lackingMessage(List.of(
                        new FeaturePolicy.Shortfall("fancy_ui", "fancy_ui", FeaturePolicy.Lack.MISSING, 0, 0),
                        new FeaturePolicy.Shortfall("tool_tip", "Tool tips", FeaturePolicy.Lack.OUTDATED, 1, 2),
                        new FeaturePolicy.Shortfall("boss_hp", "Boss HP", FeaturePolicy.Lack.VETOED, 1, 0))));
        FeaturePolicy.Outcome outcome = new FeaturePolicy.Outcome(List.of(), List.of(), List.of(
                new Feature("crew_window", "Crew window", 1, 0, STATE_ATTACHED | STATE_ACTIVE),
                new Feature("boss_hp", "", 1, 0, FeaturePackets.STATE_VETOED)));
        assertEquals("Client features at your next start: Crew window off (the server turned it off); boss_hp back on.",
                FeatureHandshake.nextStartMessage(outcome));
    }

    @Test
    void describeShowsEachFeaturesStateAndItsAnswerNow() {
        handshake.setRule("fancy_ui", new Rule(Decision.REQUIRE, 0));
        List<String> lines = handshake.describe(new Report(true, 1, 20261017, List.of(
                new Feature("mob_hp_tag", "", 1, FLAG_LIVE, STATE_ATTACHED), ADDY, CREW_OFF)));
        assertEquals(List.of("Applied 3 features, protocol 1, DLL 20261017.",
                "mob_hp_tag v1 inert -> allow",
                "free_addy v1 active -> allow",
                "crew_window v1 off -> allow",
                "fancy_ui missing -> require"), lines);
        verify(client, never()).sendPacket(any());
        verify(chr, never()).dropMessage(eq(1), anyString());
    }
}
