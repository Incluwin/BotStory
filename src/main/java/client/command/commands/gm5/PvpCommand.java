package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.pvp.probe.DamageMeter;
import server.pvp.probe.PvpCalibration;
import server.pvp.probe.PvpProbe;
import tools.PacketCreator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code !pvp} (docs/PVP_PLAN.md section 3, the V0 probes). Measurement only.
 * <ul>
 *   <li>{@code probe self [src] [mobId]}: a 1234 DAMAGE_PLAYER about you, sent to your client alone (V0.1)</li>
 *   <li>{@code probe remote <name> [src] [mobId]}: the same about a character on your map, to you alone (V0.2)</li>
 *   <li>{@code probe move [seconds]}: gaps between your movement packets (V0.3)</li>
 *   <li>{@code probe attacks [seconds]}: your attack packets as parsed, targets or not (V0.4)</li>
 *   <li>{@code meter start|stop|status}: sustained single-target damage per job path and band (V0.5)</li>
 *   <li>{@code calibrate}: H_ref, gear base and the meter into {@code logs/pvp/reference-*.yaml} (V0.5)</li>
 * </ul>
 * {@code src} is the packet's attack byte: -4 (no mob; the default), -3, -2, -1 (touch) or 0 (a mob's first attack).
 */
public class PvpCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(PvpCommand.class);
    private static final int PROBE_DAMAGE = 1234;

    {
        setDescription("PvP V0 probes. Usage: !pvp probe self|remote <name>|move|attacks ..., !pvp meter start|stop|status,"
                + " !pvp calibrate");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 0) {
            gm.yellowMessage(getDescription());
            return;
        }
        switch (params[0].toLowerCase()) {
            case "probe" -> probe(gm, params);
            case "meter" -> meter(gm, params.length > 1 ? params[1].toLowerCase() : "status");
            case "calibrate" -> calibrate(gm);
            default -> gm.yellowMessage(getDescription());
        }
    }

    private void probe(Character gm, String[] params) {
        String kind = params.length > 1 ? params[1].toLowerCase() : "";
        switch (kind) {
            case "self" -> damageProbe(gm, gm, params, 2);
            case "remote" -> {
                Character target = params.length > 2 ? gm.getMap().getCharacterByName(params[2]) : null;
                if (target == null) {
                    gm.yellowMessage("Usage: !pvp probe remote <name on this map> [src] [mobId]");
                    return;
                }
                damageProbe(gm, target, params, 3);
            }
            case "move" -> {
                int seconds = number(params, 2, 30);
                PvpProbe.watchMoves(gm, seconds);
                gm.yellowMessage("PvP probe move: walking, jumping and attacking for " + seconds + " s.");
            }
            case "attacks" -> {
                int seconds = number(params, 2, 60);
                PvpProbe.watchAttacks(gm, seconds);
                gm.yellowMessage("PvP probe attacks: attack empty air for " + seconds + " s.");
            }
            default -> gm.yellowMessage(getDescription());
        }
    }

    private static void damageProbe(Character gm, Character about, String[] params, int at) {
        int src = number(params, at, -4);
        int mobId = number(params, at + 1, 0);
        if (src < -4 || src > 0) {
            gm.yellowMessage("src must be -4, -3, -2, -1 or 0.");
            return;
        }
        int direction = about.getPosition().x < gm.getPosition().x ? 0 : 1;
        gm.sendPacket(PacketCreator.damagePlayer(src, mobId, about.getId(), PROBE_DAMAGE, 0, direction, false, 0,
                false, 0, 0, 0));
        String line = "PvP probe damage about " + about.getName() + (about == gm ? " (self)" : "") + " src=" + src
                + " mob=" + mobId + " dmg=" + PROBE_DAMAGE + ": look for the number and the hit pose.";
        log.info(line);
        gm.yellowMessage(line);
    }

    private static void meter(Character gm, String action) {
        DamageMeter meter = PvpProbe.meter();
        switch (action) {
            case "start" -> {
                meter.start();
                gm.yellowMessage("PvP meter started (cleared).");
            }
            case "stop" -> {
                meter.stop();
                gm.yellowMessage("PvP meter stopped; " + meter.measured() + " characters measured.");
            }
            default -> {
                gm.yellowMessage("PvP meter " + (meter.running() ? "running" : "stopped") + ", " + meter.measured()
                        + " characters; rows with 60 s+ engaged:");
                for (DamageMeter.Row row : meter.report()) {
                    gm.yellowMessage(row.toString());
                }
            }
        }
    }

    private static void calibrate(Character gm) {
        List<PvpCalibration.Sample> samples = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isGM() && chr.getLevel() >= 30) {
                    samples.add(PvpCalibration.of(chr));
                }
            }
        }
        List<PvpCalibration.Band> bands = PvpCalibration.referenceHp(samples);
        Map<String, Long> gear = PvpCalibration.gearMedians(samples);
        for (PvpCalibration.Band band : bands) {
            gm.yellowMessage("H_ref " + band.band() * 10 + "s = " + band.hRef() + " (n=" + band.samples() + " "
                    + band.classMedians() + ")");
        }
        try {
            Path file = PvpCalibration.write(Path.of("logs", "pvp"), bands, gear, PvpProbe.meter().report());
            gm.yellowMessage("PvP calibration: " + samples.size() + " characters -> " + file);
            log.info("PvP calibration: {} characters -> {}", samples.size(), file.toAbsolutePath());
        } catch (java.io.IOException failure) {
            gm.yellowMessage("PvP calibration: could not write logs/pvp (" + failure.getMessage() + ").");
        }
    }

    private static int number(String[] params, int index, int fallback) {
        if (params.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(params[index]);
        } catch (NumberFormatException notNumber) {
            return fallback;
        }
    }
}
