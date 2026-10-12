package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Rumor;
import server.living.config.ContentException;
import server.living.config.ContentLint;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;

import java.util.List;
import java.util.Locale;

/**
 * {@code !world} operates the living world: reload content, inspect and wake residents, move
 * arcs, set flags and bonds, play rumors, review learned phrases, read a player's ledger.
 */
public class LivingWorldCommand extends Command {
    {
        setDescription("Living world ops. Usage: !world status|reload|residents|spawn <persona>|sleep|wake <town>|"
                + "arc <persona> <chapter>|flag set|clear <flag>|bond <player> <persona> <points>|rumor play <id>|"
                + "phrases [approve|reject <text>]|teach <canonical> <phrase>|haul <player>|events <player>|census|"
                + "crewwindow [off|gm|all]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            gm.yellowMessage("Living world is disabled (living_world.ENABLED in config.yaml).");
            return;
        }
        String verb = params.length == 0 ? "status" : params[0].toLowerCase(Locale.ROOT);
        try {
            switch (verb) {
                case "status" -> status(gm, world);
                case "census" -> gm.yellowMessage(world.content().census());
                case "reload" -> {
                    List<String> findings = world.reload();
                    gm.yellowMessage("Reloaded: " + world.content().census());
                    for (String finding : findings) {
                        if (finding.startsWith("ERROR")) {
                            gm.yellowMessage(finding);
                        }
                    }
                    gm.yellowMessage(ContentLint.hasErrors(findings) ? "Content has errors (see above)." : findings.size() + " warnings.");
                }
                case "residents" -> {
                    for (ResidentRuntime.Session session : world.residents().sessions()) {
                        Character agent = session.agent();
                        gm.yellowMessage(session.persona.id() + " '" + session.persona.name() + "' "
                                + (agent == null ? "standby" : "map " + agent.getMapId() + " lv" + agent.getLevel()
                                + " @" + agent.getPosition().x + "," + agent.getPosition().y));
                    }
                    gm.yellowMessage(world.residents().sessions().size() + " materialized of " + world.content().personas().size());
                }
                case "spawn", "wake" -> {
                    if (params.length < 2) {
                        gm.yellowMessage("Usage: !world wake <town|persona>");
                        return;
                    }
                    Persona persona = world.content().persona(params[1]);
                    String town = persona != null ? persona.homeTown() : params[1];
                    world.residents().setForcedAwake(town, true);
                    world.residents().tick();
                    gm.yellowMessage("Residents of " + town + " forced awake (" + world.residents().sessions().size() + " materialized).");
                }
                case "sleep" -> {
                    if (params.length < 2) {
                        gm.yellowMessage("Usage: !world sleep <town>");
                        return;
                    }
                    world.residents().setForcedAwake(params[1], false);
                    for (ResidentRuntime.Session session : world.residents().sessions()) {
                        if (session.persona.homeTown().equals(params[1])) {
                            world.residents().standby(session, "gm sleep");
                        }
                    }
                    gm.yellowMessage("Residents of " + params[1] + " sent to standby.");
                }
                case "arc" -> {
                    if (params.length < 3) {
                        gm.yellowMessage("Usage: !world arc <persona> <chapter>");
                        return;
                    }
                    Persona persona = world.content().persona(params[1]);
                    if (persona == null) {
                        gm.yellowMessage("Unknown persona " + params[1]);
                        return;
                    }
                    gm.yellowMessage(world.arcs().advance(persona, params[2], "gm", true) ? "Arc moved." : "No such chapter.");
                }
                case "flag" -> {
                    if (params.length < 3) {
                        gm.yellowMessage("Usage: !world flag set|clear <flag>");
                        return;
                    }
                    if (params[1].equalsIgnoreCase("set")) {
                        world.store().setWorldFlag(params[2], gm.getId());
                        world.arcs().evaluateGates();
                        gm.yellowMessage("World flag set: " + params[2]);
                    } else {
                        world.store().clearWorldFlag(params[2]);
                        gm.yellowMessage("World flag cleared: " + params[2]);
                    }
                }
                case "bond" -> {
                    if (params.length < 4) {
                        gm.yellowMessage("Usage: !world bond <player> <persona> <points>");
                        return;
                    }
                    Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[1]);
                    Persona persona = world.content().persona(params[2]);
                    if (target == null || persona == null) {
                        gm.yellowMessage("Player must be online and persona must exist.");
                        return;
                    }
                    LivingStore.Bond bond = world.bonds().addPoints(target, persona, Integer.parseInt(params[3]), "gm");
                    gm.yellowMessage(target.getName() + " / " + persona.name() + ": " + bond.stage() + " " + bond.points() + " points");
                }
                case "rumor" -> {
                    if (params.length < 3) {
                        gm.yellowMessage("Usage: !world rumor play <id>");
                        return;
                    }
                    Rumor rumor = world.content().rumor(params[2]);
                    gm.yellowMessage(rumor == null ? "Unknown rumor." : world.rumors().perform(rumor, true));
                }
                case "phrases" -> {
                    if (params.length >= 3) {
                        String text = String.join(" ", java.util.Arrays.copyOfRange(params, 2, params.length));
                        if (params[1].equalsIgnoreCase("approve")) {
                            world.store().setPhraseApproved(server.living.phrases.PhraseMatcher.normalize(text), true);
                            gm.yellowMessage("Approved.");
                        } else {
                            gm.yellowMessage(world.phrases().forget(text) ? "Removed." : "Not found.");
                        }
                        return;
                    }
                    gm.yellowMessage(world.phrases().commandCount() + " pack commands, " + world.phrases().learnedCount() + " learned:");
                    int shown = 0;
                    for (LivingStore.LearnedPhrase phrase : world.store().learnedPhrases()) {
                        if (shown++ >= 25) {
                            gm.yellowMessage("...");
                            break;
                        }
                        gm.yellowMessage((phrase.approved() ? "[ok] " : "[new] ") + "\"" + phrase.normalizedText() + "\" -> "
                                + phrase.canonical() + " x" + phrase.hits());
                    }
                }
                case "teach" -> {
                    if (params.length < 3) {
                        gm.yellowMessage("Usage: !world teach <canonical> <phrase...>");
                        return;
                    }
                    String phrase = String.join(" ", java.util.Arrays.copyOfRange(params, 2, params.length));
                    world.phrases().teach(phrase, params[1]);
                    gm.yellowMessage("Taught \"" + phrase + "\" -> " + params[1]);
                }
                case "haul", "events" -> {
                    if (params.length < 2) {
                        gm.yellowMessage("Usage: !world " + verb + " <player>");
                        return;
                    }
                    Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[1]);
                    if (target == null) {
                        gm.yellowMessage("Player must be online.");
                        return;
                    }
                    if (verb.equals("haul")) {
                        for (String line : world.roster().summary(target)) {
                            gm.yellowMessage(line);
                        }
                    } else {
                        for (String line : world.store().recentEvents(target.getId(), 20)) {
                            gm.yellowMessage(line);
                        }
                    }
                }
                case "crewwindow" -> crewWindow(gm, world, params);
                default -> gm.yellowMessage(getDescription());
            }
        } catch (ContentException failure) {
            gm.yellowMessage("Content error: " + failure.getMessage());
        } catch (RuntimeException failure) {
            gm.yellowMessage("Failed: " + failure.getMessage());
        }
    }

    /**
     * {@code !world crewwindow [off|gm|all]}: shows or sets living_world.CREW_WINDOW_NATIVE until the next restart (P10).
     * Every online client with the P10 DLL hears what it allows now, and a window it no longer allows closes.
     */
    private static void crewWindow(Character gm, LivingWorld world, String[] params) {
        if (params.length < 2) {
            gm.yellowMessage("Native Crew window: " + world.config().CREW_WINDOW_NATIVE + ". Usage: !world crewwindow off|gm|all");
            return;
        }
        String mode = params[1].toLowerCase(Locale.ROOT);
        if (!List.of("off", "gm", "all").contains(mode)) {
            gm.yellowMessage("Usage: !world crewwindow off|gm|all");
            return;
        }
        world.config().CREW_WINDOW_NATIVE = mode;
        List<Character> online = new java.util.ArrayList<>();
        for (net.server.world.World server : net.server.Server.getInstance().getWorlds()) {
            online.addAll(server.getPlayerStorage().getAllCharacters());
        }
        world.crewWindow().switchChanged(online);
        gm.yellowMessage("Native Crew window: " + mode + " until the next restart (config.yaml keeps its own value).");
    }

    private static void status(Character gm, LivingWorld world) {
        gm.yellowMessage("Living world: " + world.content().census());
        gm.yellowMessage("Residents materialized: " + world.residents().sessions().size()
                + ", learned phrases: " + world.phrases().learnedCount()
                + ", world flags: " + world.store().worldFlags().size());
    }
}
