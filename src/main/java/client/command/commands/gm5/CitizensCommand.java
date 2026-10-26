package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Churn;
import server.living.population.Population;
import server.living.population.PopulationContent.Cohort;
import server.living.population.PopulationContent.Region;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code !citizens} operates the populated server: census, who is online, births, region-targeted
 * seeding and retirements, content reload, forced online counts for load tests, one citizen's card, and for testing
 * job errands: one citizen's stored level raised while it is offline, one citizen loaded now; and who a citizen
 * remembers of the players it met (docs/CITIZEN_PERSON_MEMORY_PLAN.md).
 */
public class CitizensCommand extends Command {
    {
        setDescription("Population ops. Usage: !citizens status|online|cohorts|who <name>|birth [cohort] [backdateDays]|"
                + "seed [regionId count]|retire <name>|purge confirm|force <n>|sweep|reload|guilds|alliances|market|conversation|"
                + "level <name> <level>|login <name>|pq [send <name>]|memory [name]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Population population = Population.get();
        if (population == null) {
            gm.yellowMessage("Population is disabled (population.ENABLED in config.yaml, needs living_world.ENABLED).");
            return;
        }
        String verb = params.length == 0 ? "status" : params[0].toLowerCase(Locale.ROOT);
        try {
            switch (verb) {
                case "status" -> {
                    gm.yellowMessage("Citizens: " + population.activeCount() + " active of " + population.config().TARGET_CITIZENS
                            + " target (" + population.citizens().size() + " rows); online " + population.presence().loadedCount()
                            + " loaded + " + population.presence().virtualCount() + " virtual (max loaded " + population.config().ONLINE_MAX
                            + (population.presence().forcedOnline() > 0 ? ", forced " + population.presence().forcedOnline() : "") + ")");
                    gm.yellowMessage("Content: " + population.content().census() + "; guilds " + population.social().guildCount()
                            + "; chatter lines " + population.chatter().linesSpoken() + "; last sweep " + population.presence().lastSweepDurationMs()
                            + " ms" + (population.churn().seeding() ? "; seeding" : "")
                            + (population.churn().seedQueue().isEmpty() ? "" : "; seed queue " + population.churn().seedQueue()
                            + " (in memory, lost on restart)"));
                    gm.yellowMessage(population.conversation().report());
                    gm.yellowMessage(population.market().report());
                    gm.yellowMessage(population.presence().visibleCapReport());
                    gm.yellowMessage(population.partyQuests().status());
                }
                case "pq" -> {
                    if (params.length >= 3 && params[1].equalsIgnoreCase("send")) {
                        Citizen target = population.citizenByName(params[2]);
                        CitizenSession session = target == null ? null : population.presence().session(target.characterId());
                        gm.yellowMessage(session == null ? params[2] + " is not online"
                                : population.partyQuests().send(session, System.currentTimeMillis()));
                        return;
                    }
                    gm.yellowMessage(population.partyQuests().status() + (population.config().PQ_RUNS_ENABLED ? ""
                            : " (PQ_RUNS_ENABLED is off)"));
                    for (String run : population.partyQuests().recentRuns()) {
                        gm.yellowMessage("  " + run);
                    }
                }
                case "online" -> {
                    for (CitizenSession session : population.presence().sessions()) {
                        Character agent = session.agent();
                        gm.yellowMessage(session.name() + (session.virtual ? " (ledger)" : " lv" + (agent == null ? "?" : agent.getLevel())
                                + " " + session.activity + " map " + session.mapId + " ch" + session.channel
                                + (session.afkUntilMs > 0 ? " afk" : "") + (session.stallOpen ? " stall" : "")));
                    }
                    gm.yellowMessage(population.presence().sessions().size() + " online.");
                }
                case "cohorts" -> {
                    ZonedDateTime now = ZonedDateTime.now();
                    for (Cohort cohort : population.content().cohorts().values()) {
                        int members = 0;
                        int online = 0;
                        for (Citizen citizen : population.citizens()) {
                            if (citizen.cohort().equals(cohort.id()) && citizen.state() == Citizen.State.ACTIVE) {
                                members++;
                                if (population.presence().session(citizen.characterId()) != null) {
                                    online++;
                                }
                            }
                        }
                        gm.yellowMessage(cohort.id() + ": " + members + " citizens, " + online + " online, hour weight " + cohort.weight(now)
                                + "/10 (peak " + cohort.peakOnlinePercent() + "%)");
                    }
                }
                case "who" -> {
                    if (params.length < 2) {
                        gm.yellowMessage("Usage: !citizens who <name>");
                        return;
                    }
                    Citizen citizen = population.citizenByName(params[1]);
                    if (citizen == null) {
                        gm.yellowMessage("No citizen named " + params[1]);
                        return;
                    }
                    int[] progress = population.store().characterProgress(citizen.characterId());
                    client.Job job = client.Job.getById(progress[2]);
                    gm.yellowMessage(citizen.name() + " #" + citizen.characterId() + " lv" + progress[0] + " "
                            + (job == null ? "job " + progress[2] : job.name()) + " " + citizen.career()
                            + " cohort " + citizen.cohort() + " region " + citizen.homeRegion() + " voice " + citizen.voice()
                            + " personality " + citizen.personality() + " pace " + String.format(Locale.ROOT, "%.2f", citizen.pace())
                            + " age " + citizen.ageDays(System.currentTimeMillis()) + "d target lv"
                            + population.ledger().levelTarget(citizen, System.currentTimeMillis()) + " flags " + citizen.flags()
                            + " guild " + citizen.guildId() + " circle " + citizen.circleId() + " " + citizen.state());
                    if (!citizen.lifeSummary().isBlank()) {
                        gm.yellowMessage("life: " + citizen.lifeSummary());
                    }
                    CitizenSession session = population.presence().session(citizen.characterId());
                    if (session != null) {
                        for (var entry : session.journal.recent(6)) {
                            gm.yellowMessage("  journal: " + entry.text());
                        }
                    }
                }
                case "birth" -> {
                    String cohort = params.length > 1 ? params[1] : null;
                    int backdate = params.length > 2 ? Integer.parseInt(params[2]) : 0;
                    Citizen born = population.generator().birth(cohort, backdate);
                    gm.yellowMessage(born == null ? "Birth failed (see log)." : "Born: " + born.name() + " (" + born.cohort() + ", " + born.career() + ")");
                }
                case "seed" -> {
                    if (params.length < 2) {
                        List<String> bands = new ArrayList<>();
                        for (Region region : population.content().regions().values()) {
                            bands.add(population.churn().describe(region));
                        }
                        gm.yellowMessage("Regions: " + String.join(", ", bands));
                        gm.yellowMessage("Seed queue: " + population.churn().seedQueue() + " (in memory, lost on restart). Usage: "
                                + "!citizens seed <regionId> <count 1-" + Churn.SEED_REQUEST_MAX + ">");
                        return;
                    }
                    int count = params.length > 2 ? Integer.parseInt(params[2]) : 0;
                    Region region = population.churn().requestSeeds(params[1], count);
                    gm.yellowMessage("Queued " + count + " citizens for " + population.churn().describe(region) + ", up to "
                            + Churn.SEED_BATCH + " a sweep. The " + population.config().TARGET_CITIZENS + " target does not limit them but they"
                            + " count toward it: below it they replace ordinary births, at or above it ordinary births pause.");
                    gm.yellowMessage("Seed queue: " + population.churn().seedQueue() + " (in memory, lost on restart).");
                }
                case "retire" -> {
                    if (params.length < 2) {
                        gm.yellowMessage("Usage: !citizens retire <name>");
                        return;
                    }
                    Citizen citizen = population.citizenByName(params[1]);
                    if (citizen == null) {
                        gm.yellowMessage("No citizen named " + params[1]);
                        return;
                    }
                    gm.yellowMessage(population.churn().retire(citizen, "retired by " + gm.getName())
                            ? citizen.name() + " retired." : citizen.name() + " works for someone; release them first.");
                }
                case "level" -> {
                    Citizen citizen = params.length < 3 ? null : population.citizenByName(params[1]);
                    if (citizen == null) {
                        gm.yellowMessage("Usage: !citizens level <name> <level> (an offline citizen; never lowers a level)");
                        return;
                    }
                    CitizenSession session = population.presence().session(citizen.characterId());
                    if (session != null && session.loaded()) {
                        gm.yellowMessage(citizen.name() + " is loaded; level it with GM commands or wait until it logs off.");
                        return;
                    }
                    population.ledger().raiseStoredLevel(citizen.characterId(), Integer.parseInt(params[2]));
                    gm.yellowMessage(citizen.name() + " is lv" + population.store().characterProgress(citizen.characterId())[0]
                            + " in the DB now; it catches up (job, AP, SP) when it next loads.");
                }
                case "login" -> {
                    Citizen citizen = params.length < 2 ? null : population.citizenByName(params[1]);
                    if (citizen == null) {
                        gm.yellowMessage("Usage: !citizens login <name>");
                        return;
                    }
                    CitizenSession session = population.presence().bringOnline(citizen, System.currentTimeMillis());
                    if (session == null || !session.loaded()) {
                        gm.yellowMessage(citizen.name() + " could not be loaded (hired, retired, or owned by a runtime).");
                        return;
                    }
                    gm.yellowMessage(citizen.name() + " online: " + session.activity + " at map " + session.mapId + " ch"
                            + session.channel + (session.errandJob == null ? "" : ", going for " + session.errandJob));
                }
                case "force" -> {
                    int count = params.length > 1 ? Integer.parseInt(params[1]) : 0;
                    population.presence().forceOnline(count);
                    gm.yellowMessage("Forced online count set to " + count + " (applies on the next sweep; 0 clears).");
                }
                case "sweep" -> {
                    population.presence().sweep();
                    gm.yellowMessage("Sweep done in " + population.presence().lastSweepDurationMs() + " ms; online "
                            + population.presence().loadedCount() + " loaded + " + population.presence().virtualCount() + " virtual.");
                }
                case "reload" -> {
                    List<String> findings = population.reload();
                    gm.yellowMessage("Reloaded: " + population.content().census());
                    for (String finding : findings) {
                        if (finding.startsWith("ERROR")) {
                            gm.yellowMessage(finding);
                        }
                    }
                    gm.yellowMessage(findings.size() + " findings.");
                }
                case "guilds" -> {
                    for (var meta : population.store().guilds()) {
                        var guild = net.server.Server.getInstance().getGuild(meta.guildId(), population.world().config().WORLD);
                        gm.yellowMessage((guild == null ? "guild " + meta.guildId() : guild.getName() + " (" + guild.getMembers().size() + " members)")
                                + " lv" + meta.levelMin() + "-" + meta.levelMax() + " " + meta.stance() + " - " + meta.motto()
                                + (guild == null || guild.getAllianceId() <= 0 ? "" : " [alliance " + guild.getAllianceId() + "]"));
                    }
                }
                case "alliances" -> {
                    for (String line : population.social().alliances().report()) {
                        gm.yellowMessage(line);
                    }
                }
                case "purge" -> {
                    if (params.length < 2 || !"confirm".equalsIgnoreCase(params[1])) {
                        gm.yellowMessage("Retires every citizen (characters stay; they never log in again) so the registry reseeds. !citizens purge confirm");
                        return;
                    }
                    int retired = 0;
                    for (Citizen citizen : List.copyOf(population.citizens())) {
                        if (citizen.state() == Citizen.State.ACTIVE && !citizen.hired()
                                && population.churn().retire(citizen, "purged by " + gm.getName())) {
                            retired++; // a hired hand works for a friend: it stays
                        }
                    }
                    gm.yellowMessage("Retired " + retired + " citizens; seeding resumes on the next sweep.");
                }
                case "market" -> gm.yellowMessage(population.market().report());
                case "conversation" -> gm.yellowMessage(population.conversation().report());
                case "memory" -> {
                    gm.yellowMessage(population.memories().report());
                    if (params.length < 2) {
                        return;
                    }
                    Citizen citizen = population.citizenByName(params[1]);
                    if (citizen == null) {
                        gm.yellowMessage("No citizen named " + params[1]);
                        return;
                    }
                    List<String> people = population.memories().describe(citizen.characterId());
                    gm.yellowMessage(citizen.name() + " remembers " + people.size() + (people.size() == 1 ? " person" : " people"));
                    for (String person : people.subList(0, Math.min(10, people.size()))) {
                        gm.yellowMessage("  " + person);
                    }
                }
                default -> gm.yellowMessage(getDescription());
            }
        } catch (RuntimeException failure) {
            gm.yellowMessage("citizens " + verb + " failed: " + failure.getMessage());
        }
    }
}
