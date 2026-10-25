package server.mods.features;

import client.Character;
import client.Client;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.mods.ServerMods;
import server.mods.features.FeaturePackets.Answer;
import server.mods.features.FeaturePackets.Feature;
import server.mods.features.FeaturePackets.Report;
import server.mods.features.FeaturePolicy.Decision;
import server.mods.features.FeaturePolicy.Outcome;
import server.mods.features.FeaturePolicy.Rule;
import server.mods.features.FeaturePolicy.Shortfall;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The login feature handshake (docs/CLIENT_FEATURE_HANDSHAKE.md): a kaentake client reports its feature list once per
 * connection, after its first SET_FIELD; the server answers allow, deny or require per feature from the policy
 * table, and tells the player about a required feature their client lacks. A client that never reports is never sent
 * anything. With the {@code client_feature_policy} switch off every answer is allow, which also lifts a veto a client
 * kept from an earlier login. Reports and answers for one connection run on its I/O thread.
 */
public final class FeatureHandshake {
    private static final Logger log = LoggerFactory.getLogger(FeatureHandshake.class);

    private static volatile FeatureHandshake instance;

    private final ServerMods mods;
    private volatile FeaturePolicy policy;

    public FeatureHandshake(ServerMods mods, FeaturePolicy policy) {
        this.mods = mods;
        this.policy = policy;
    }

    /** The handshake with config.yaml's policy, made on first use. */
    public static FeatureHandshake get() {
        FeatureHandshake handshake = instance;
        if (handshake == null) {
            synchronized (FeatureHandshake.class) {
                handshake = instance;
                if (handshake == null) {
                    handshake = new FeatureHandshake(ServerMods.get(),
                            FeaturePolicy.parse(YamlConfig.config.mods.FEATURES, YamlConfig.config.mods.SERVER_GATED_DEFAULT));
                    instance = handshake;
                }
            }
        }
        return handshake;
    }

    /** The table {@code mods.FEATURES} plus any {@code !mods feature} change since boot. */
    public FeaturePolicy policy() {
        return policy;
    }

    /** What clients are answered from: the table, or allow-all while the policy switch is off. */
    public FeaturePolicy effectivePolicy() {
        return mods.isOn(ServerMods.CLIENT_FEATURE_POLICY) ? policy : FeaturePolicy.ALLOW_ALL;
    }

    /**
     * Whether the client's last report has the feature active and the policy leaves it so: a REPORT stands until the
     * client's APPLIED arrives, and a denied feature goes inert there. False for a client that never reported (bots).
     */
    public boolean isActive(Client client, String id) {
        Report report = client == null ? null : client.getClientFeatures();
        if (report == null) {
            return false;
        }
        for (Feature feature : report.features()) {
            if (feature.id().equalsIgnoreCase(id)) {
                return feature.active()
                        && effectivePolicy().evaluate(List.of(feature)).answers().get(0).decision() != Decision.DENY;
            }
        }
        return false;
    }

    /** {@code !mods feature <id> ...}: the line until the next restart (null removes it). */
    public void setRule(String id, Rule rule) {
        policy = policy.with(id, rule);
    }

    /** A decoded CLIENT_FEATURES packet: a REPORT is kept and answered, an APPLIED is kept. */
    public void receive(Client client, Character chr, Report report) {
        client.setClientFeatures(report);
        if (report.applied()) {
            List<String> inert = report.features().stream()
                    .filter(feature -> feature.attached() && !feature.active()).map(Feature::id).toList();
            log.info("Client features of {} applied: {} of {} active{}", chr.getName(),
                    report.features().stream().filter(Feature::active).count(), report.features().size(),
                    inert.isEmpty() ? "" : ", inert " + inert);
            return;
        }
        Outcome outcome = answer(client, chr, report);
        log.info("Client features of {}: {} reported (protocol {}, DLL {}); denied {}; lacking {}", chr.getName(),
                report.features().size(), report.protocol(), report.dllBuild(), denied(outcome),
                outcome.lacking().stream().map(Shortfall::id).toList());
    }

    /**
     * The policy changed (a {@code !mods} flip): every online client that reported hears its new answer, on its own
     * I/O thread.
     */
    public void policyChanged(Iterable<Character> online) {
        for (Character chr : online) {
            Client client = chr.getClient();
            if (client == null || client.getClientFeatures() == null) {
                continue;
            }
            client.runOnIoThread(() -> {
                Report report = client.getClientFeatures();
                if (report != null) {
                    answer(client, chr, report);
                }
            });
        }
    }

    private Outcome answer(Client client, Character chr, Report report) {
        Outcome outcome = effectivePolicy().evaluate(report.features());
        client.sendPacket(FeaturePackets.policy(Math.min(report.protocol(), FeaturePackets.PROTOCOL), outcome.answers()));
        String popup = lackingMessage(outcome.lacking());
        if (popup != null) {
            chr.dropMessage(1, popup);
        }
        String nextStart = nextStartMessage(outcome);
        if (nextStart != null) {
            chr.dropMessage(5, nextStart);
        }
        return outcome;
    }

    private static List<String> denied(Outcome outcome) {
        return outcome.answers().stream().filter(answer -> answer.decision() == Decision.DENY).map(Answer::id).toList();
    }

    /** The popup for required features the client lacks, or null when it lacks none. */
    static String lackingMessage(List<Shortfall> lacking) {
        if (lacking.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("This server needs client features your game does not have on:");
        for (Shortfall shortfall : lacking) {
            out.append("\r\n- ").append(shortfall.title()).append(": ").append(switch (shortfall.lack()) {
                case MISSING -> "your game does not have it. Close the game and start Launch Game.exe again to update it.";
                case OUTDATED -> "version " + shortfall.version() + " here, the server needs " + shortfall.minVersion()
                        + " or newer. Close the game and start Launch Game.exe again to update it.";
                case OFF -> "switched off. Turn it on in the Features page (hold Shift as Launch Game.exe starts),"
                        + " then restart the game.";
                case VETOED -> "kept off by an earlier server veto. It comes back when you restart the game.";
            });
        }
        return out.toString();
    }

    /** The chat line for answers the client can only carry out at its next start, or null. */
    static String nextStartMessage(Outcome outcome) {
        if (outcome.atNextStart().isEmpty()) {
            return null;
        }
        return "Client features at your next start: " + outcome.atNextStart().stream()
                .map(feature -> (feature.title().isBlank() ? feature.id() : feature.title())
                        + (feature.attached() ? " off (the server turned it off)" : " back on"))
                .collect(Collectors.joining("; ")) + ".";
    }

    /** {@code !mods client <player>}: one line per reported feature with its state and the answer it gets now. */
    public List<String> describe(Report report) {
        Outcome outcome = effectivePolicy().evaluate(report.features());
        List<String> lines = new ArrayList<>();
        lines.add((report.applied() ? "Applied" : "Reported") + " " + report.features().size() + " features, protocol "
                + report.protocol() + ", DLL " + report.dllBuild() + ".");
        for (int i = 0; i < report.features().size(); i++) {
            Feature feature = report.features().get(i);
            String state = !feature.attached() ? (feature.vetoed() ? "off (vetoed)" : "off")
                    : feature.active() ? "active" : "inert";
            lines.add(feature.id() + " v" + feature.version() + " " + state + " -> "
                    + outcome.answers().get(i).decision().word());
        }
        for (Shortfall shortfall : outcome.lacking()) {
            if (shortfall.lack() == FeaturePolicy.Lack.MISSING) {
                lines.add(shortfall.id() + " missing -> require");
            }
        }
        return lines;
    }
}
