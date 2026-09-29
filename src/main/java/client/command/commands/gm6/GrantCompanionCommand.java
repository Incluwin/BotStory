package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Citizen;
import server.living.population.Population;

import java.util.Locale;

/**
 * {@code !grantcompanion <personaId> [characterName]} puts a Resident persona on an online player's
 * roster (default: yourself) as a benched companion, with no contract and no change to its character.
 * {@code !grantcompanion release <personaId>} takes it back through the normal release path, a hired citizen's
 * ({@code hire:<characterId>}) too, which goes back to the population. For tests and demos
 * (docs/BOT_MANAGEMENT_PLAN.md, "GrantCompanionCommand"); citizens are hired through the Crew window, never granted.
 */
public final class GrantCompanionCommand extends Command {
    static final String USAGE = "Syntax: !grantcompanion <personaId> [characterName] | !grantcompanion release <personaId>";

    {
        setDescription("Put a Resident persona on a player's roster, benched (tests and demos).");
    }

    /** One command line: grant or release, the persona id, and the named owner (null = the GM). */
    record Request(boolean release, String personaId, String characterName) {
    }

    /** Null when the line does not match either form. */
    static Request parse(String[] params) {
        if (params.length >= 1 && params[0].equalsIgnoreCase("release")) {
            return params.length == 2 ? new Request(true, params[1], null) : null;
        }
        if (params.length == 1 || params.length == 2) {
            return new Request(false, params[0], params.length == 2 ? params[1] : null);
        }
        return null;
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Request request = parse(params);
        if (request == null) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            gm.yellowMessage("Living world is disabled (living_world.ENABLED in config.yaml).");
            return;
        }
        Persona persona = persona(gm, world, request.personaId(), request.release());
        if (persona == null) {
            return;
        }
        if (request.release()) {
            release(gm, world, persona);
            return;
        }
        Character owner = request.characterName() == null ? gm
                : c.getWorldServer().getPlayerStorage().getCharacterByName(request.characterName());
        if (owner == null) {
            gm.yellowMessage("'" + request.characterName() + "' must be online.");
            return;
        }
        if (AgentPresence.isAgent(owner)) {
            gm.yellowMessage("'" + owner.getName() + "' is a bot; companions belong to players.");
            return;
        }
        // roster rows are written on companion-ops (plan "CompanionExecutor")
        world.executor().submit("gm.grant " + persona.id(),
                () -> gm.yellowMessage(world.roster().grant(owner, persona)));
    }

    /**
     * A content persona by id, or for a release a hired citizen's ({@code hire:<cid>}, which the installed resolver
     * answers). A grant never takes a citizen: it would enroll one without its hand-over, so the population and the
     * roster would both run it.
     */
    private static Persona persona(Character gm, LivingWorld world, String personaId, boolean release) {
        if (!release && personaId.toLowerCase(Locale.ROOT).startsWith("hire:")) {
            gm.yellowMessage("Hire citizens through the Crew window.");
            return null;
        }
        Persona persona = world.content().persona(personaId);
        if (persona != null) {
            return persona;
        }
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizenByName(personaId);
        gm.yellowMessage(citizen == null ? "No persona with id '" + personaId + "'."
                : release ? "'" + personaId + "' is a citizen; release a hired one as hire:" + citizen.characterId() + "."
                : "'" + personaId + "' is a citizen; hire citizens through the Crew window.");
        return null;
    }

    private static void release(Character gm, LivingWorld world, Persona persona) {
        int ownerId = world.roster().ownerOf(persona.id());
        if (ownerId == 0) {
            gm.yellowMessage(persona.name() + " is on nobody's roster.");
            return;
        }
        Character owner = world.roster().onlineCharacter(ownerId);
        if (owner == null) {
            gm.yellowMessage(persona.name() + "'s owner (character " + ownerId + ") must be online.");
            return;
        }
        world.executor().submit("gm.release " + persona.id(),
                () -> gm.yellowMessage(owner.getName() + ": " + world.roster().release(owner, persona, "gm release")));
    }
}
