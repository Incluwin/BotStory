package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.needs.Need;
import server.living.population.needs.NeedLedger;

import java.util.List;

/**
 * {@code !needs} shows the needs board (docs/BOT_CONCEPT_PLAN.md F1): {@code !needs <citizen>} every request of one
 * citizen with its state, hold and deadline (live check L1: it matches what the citizen lacks); {@code !needs map} the
 * open requests of the citizens on your map; {@code !needs} the board's counts.
 */
public class NeedsCommand extends Command {
    {
        setDescription("Needs board. Usage: !needs [<citizen>|map]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Population population = Population.get();
        if (population == null) {
            gm.yellowMessage("Population is disabled (population.ENABLED in config.yaml, needs living_world.ENABLED).");
            return;
        }
        NeedLedger needs = population.needs();
        long nowMs = System.currentTimeMillis();
        if (params.length == 0) {
            gm.yellowMessage(needs.report());
            return;
        }
        if (params[0].equalsIgnoreCase("map")) {
            int shown = 0;
            for (CitizenSession session : population.presence().sessions()) {
                if (session.virtual || session.mapId != gm.getMapId() || session.channel != c.getChannel()) {
                    continue;
                }
                for (String line : needs.describe(session.characterId, nowMs)) {
                    gm.yellowMessage(session.name() + " " + line);
                    shown++;
                }
            }
            gm.yellowMessage(shown == 0 ? "No requests from citizens on this map." : shown + " requests on this map.");
            return;
        }
        Citizen citizen = population.citizenByName(params[0]);
        if (citizen == null) {
            gm.yellowMessage("No citizen named " + params[0] + ".");
            return;
        }
        List<String> lines = needs.describe(citizen.characterId(), nowMs);
        gm.yellowMessage(citizen.name() + ": " + (lines.isEmpty() ? "asks for nothing right now" : lines.size() + " requests")
                + (needs.on() ? "" : " (NEEDS_ENABLED is off)"));
        for (String line : lines) {
            gm.yellowMessage(line);
        }
        for (Need need : needs.of(citizen.characterId())) {
            if (need.state() == Need.State.ESCROW) {
                gm.yellowMessage("#" + need.id() + " waits in escrow for its next load");
            }
        }
    }
}
