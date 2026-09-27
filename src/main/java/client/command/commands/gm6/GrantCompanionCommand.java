package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Population;

import java.util.Locale;

/**
 * {@code !grantcompanion <personaId> [characterName]} puts a Resident persona on an online player's
 * roster (default: yourself) as a benched companion, with no contract and no change to its character.
 * {@code !grantcompanion release <personaId>} takes it back through the normal release path. For tests
 * and demos (docs/BOT_MANAGEMENT_PLAN.md, "GrantCompanionCommand"); hired citizens wait for P4.
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
        Persona persona = persona(gm, world, request.personaId());
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
        gm.yellowMessage(world.roster().grant(owner, persona));
    }

    /** A content persona by id. Hired citizens ({@code hire:<cid>}) and citizens are refused until P4. */
    private static Persona persona(Character gm, LivingWorld world, String personaId) {
        if (personaId.toLowerCase(Locale.ROOT).startsWith("hire:")) {
            gm.yellowMessage("Hired citizens cannot be granted yet (P4).");
            return null;
        }
        Persona persona = world.content().persona(personaId);
        if (persona != null) {
            return persona;
        }
        Population population = Population.get();
        gm.yellowMessage(population != null && population.citizenByName(personaId) != null
                ? "'" + personaId + "' is a citizen; citizens cannot be granted yet (P4)."
                : "No persona with id '" + personaId + "'.");
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
        gm.yellowMessage(owner.getName() + ": " + world.roster().release(owner, persona, "gm release"));
    }
}
