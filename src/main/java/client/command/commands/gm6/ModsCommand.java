package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import net.server.world.World;
import server.mods.ServerMod;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;
import server.mods.features.FeaturePackets;
import server.mods.features.FeaturePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code !mods}: the server mod switches and the client feature policy (docs/CLIENT_FEATURE_HANDSHAKE.md). Every change
 * holds until the next restart; config.yaml {@code mods:} keeps its own values.
 */
public class ModsCommand extends Command {
    {
        setDescription("Server mods and client features. Usage: !mods | !mods <mod> on|off | "
                + "!mods feature <id> allow|deny|require [version]|unset | !mods client <player>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ServerMods mods = ServerMods.get();
        FeatureHandshake handshake = FeatureHandshake.get();
        if (params.length == 0) {
            for (ServerMod mod : mods.mods()) {
                gm.yellowMessage(mod.id() + ": " + (mods.isOn(mod) ? "on" : "off") + " - " + mod.title());
            }
            gm.yellowMessage("Client features: " + handshake.policy().summary());
            return;
        }
        String verb = params[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "feature" -> feature(gm, handshake, params);
            case "client" -> client(gm, handshake, params);
            default -> toggle(gm, mods, handshake, params);
        }
    }

    private static void toggle(Character gm, ServerMods mods, FeatureHandshake handshake, String[] params) {
        ServerMod mod = mods.find(params[0]);
        Boolean on = params.length == 2 ? ServerMods.parseSwitch(params[1]) : null;
        if (mod == null || on == null) {
            gm.yellowMessage(mod == null ? "No server mod '" + params[0] + "'. Usage: !mods <mod> on|off"
                    : "Usage: !mods " + mod.id() + " on|off");
            return;
        }
        mods.set(mod, on);
        if (mod == ServerMods.CLIENT_FEATURE_POLICY) {
            handshake.policyChanged(online());
        }
        gm.yellowMessage(mod.id() + ": " + (on ? "on" : "off") + " until the next restart.");
    }

    /** {@code !mods feature <id> allow|deny|require [version]|unset}: every online client that reported hears it. */
    private static void feature(Character gm, FeatureHandshake handshake, String[] params) {
        String usage = "Usage: !mods feature <id> allow|deny|require [version]|unset";
        if (params.length < 3 || params.length > 4) {
            gm.yellowMessage(usage);
            return;
        }
        String id = params[1].toLowerCase(Locale.ROOT);
        FeaturePolicy.Rule rule = null;
        if (!params[2].equalsIgnoreCase("unset")) {
            rule = FeaturePolicy.parseRule(params.length == 4 ? params[2] + " " + params[3] : params[2]);
            if (rule == null) {
                gm.yellowMessage(usage);
                return;
            }
        }
        handshake.setRule(id, rule);
        handshake.policyChanged(online());
        gm.yellowMessage("Client feature " + id + ": " + (rule == null ? "no line" : rule)
                + " until the next restart. " + handshake.policy().summary());
    }

    /** {@code !mods client <player>}: what that player's client reported, and the answer it gets now. */
    private static void client(Character gm, FeatureHandshake handshake, String[] params) {
        if (params.length != 2) {
            gm.yellowMessage("Usage: !mods client <player>");
            return;
        }
        Character target = gm.getClient().getWorldServer().getPlayerStorage().getCharacterByName(params[1]);
        if (target == null || target.getClient() == null) {
            gm.yellowMessage(params[1] + " is not online in this world.");
            return;
        }
        FeaturePackets.Report report = target.getClient().getClientFeatures();
        if (report == null) {
            gm.yellowMessage(target.getName() + "'s client sent no feature list (no handshake): it runs as it is.");
            return;
        }
        for (String line : handshake.describe(report)) {
            gm.yellowMessage(line);
        }
    }

    private static List<Character> online() {
        List<Character> online = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            online.addAll(world.getPlayerStorage().getAllCharacters());
        }
        return online;
    }
}
