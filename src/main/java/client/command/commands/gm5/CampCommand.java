package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.roster.BenchCampForm;
import server.living.roster.CrewCamp;

/**
 * {@code !camp [enter|leave|status|tell <companion>]} (GM 5, docs/WORLD_SYSTEMS_PLAN.md N7): the GM's own crew camp,
 * for the live checks. {@code enter} and {@code leave} go through the same gates and teardown as the Crew window;
 * {@code status} (the default) prints the switch, the camp map, whether the GM is there, who sits there, who is away and
 * where, and the CAMP pool's use; {@code tell} has a companion tell its camp report (it writes the camp.told marker, as
 * talking to it at camp does). On companion-ops.
 */
public final class CampCommand extends Command {
    static final String USAGE = "Syntax: !camp [enter|leave|status|tell <companion>]";

    {
        setDescription("Enter, leave or inspect your crew camp.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        LivingWorld world = LivingWorld.get();
        if (world == null || world.crewCamp() == null) {
            gm.yellowMessage("The living world is off.");
            return;
        }
        CrewCamp camp = world.crewCamp();
        String verb = params.length == 0 ? "status" : params[0].toLowerCase(java.util.Locale.ROOT);
        switch (verb) {
            case "enter" -> world.executor().submit("gm.camp.enter " + gm.getId(), () -> camp.enter(gm));
            case "leave" -> world.executor().submit("gm.camp.leave " + gm.getId(), () -> camp.leave(gm));
            case "status" -> world.executor().submit("gm.camp.status " + gm.getId(), () -> {
                gm.yellowMessage("camp: switch " + (camp.on() ? "on" : "off") + ", map " + world.config().CREW_CAMP_MAP_ID
                        + ", " + (camp.inCamp(gm.getId()) ? "inside" : "outside") + ", pool " + camp.liveCount() + "/"
                        + world.config().CREW_CAMP_MAX);
                gm.yellowMessage("camp here: " + String.join(", ", camp.seated(gm.getId())));
                for (BenchCampForm.Absent absent : camp.away(gm.getId())) {
                    gm.yellowMessage("camp away: " + absent.personaId() + " " + absent.where());
                }
            });
            case "tell" -> {
                if (params.length != 2) {
                    gm.yellowMessage(USAGE);
                    return;
                }
                String name = params[1];
                world.executor().submit("gm.camp.tell " + name, () -> {
                    int characterId = Character.getIdByName(name);
                    Persona persona = characterId <= 0 ? null : world.residents().personaOfCharacterId(characterId);
                    if (persona == null || world.roster().ownerOf(persona.id()) != gm.getId() || world.bench() == null) {
                        gm.yellowMessage(name + " is not your companion.");
                        return;
                    }
                    String line = world.bench().tellAtCamp(gm.getId(), persona.id(), world.store().characterLevel(characterId));
                    gm.yellowMessage("camp tell " + name + ": " + (line == null ? "(nothing new)" : line));
                });
            }
            default -> gm.yellowMessage(USAGE);
        }
    }
}
