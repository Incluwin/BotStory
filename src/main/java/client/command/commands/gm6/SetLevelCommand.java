package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.Job;
import client.LevelSetPlan;
import client.command.Command;
import client.command.CommandTargetPolicy;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.roster.CompanionLevelTool;
import server.living.roster.CompanionLevelTool.Ask;
import server.living.roster.GmLevelHolds;
import server.living.roster.GmLevelHolds.Original;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * {@code !setlevel} (GM 6): sets a character's level up or down, keeping a consistent character (LevelSetPlan,
 * Character.adminSetLevel), for building test parties. Forms:
 * <ul>
 *   <li>{@code !setlevel <me|name> <level> [job] [auto]}: a level, optionally a job (an id or a name: "fighter", "412");
 *   {@code auto} (players only) spends the AP and SP as an Agent would;</li>
 *   <li>{@code !setlevel <me|name> restore}: back to the level, job and (a player) stats before the first set;</li>
 *   <li>{@code !setlevel <name> keep}: keep the level it has now and drop the record;</li>
 *   <li>{@code !setlevel party <level|restore>}: everyone in the GM's party, players first, then companions.</li>
 * </ul>
 * A name is an online player (CommandTargetPolicy; nothing they wear may need to come off), or one of the GM's own
 * companions out with them, or a plain Agent they lead (CompanionLevelTool, on companion-ops). The record of what each
 * character was before its first set is kept in the living world (GmLevelHolds); without it players can still be set,
 * with no record, and companions cannot.
 */
public final class SetLevelCommand extends Command {
    static final String USAGE = "Syntax: !setlevel <me|name> <level> [job] [auto] | !setlevel <me|name> restore"
            + " | !setlevel <name> keep | !setlevel party <level|restore>";
    private static final Logger log = LoggerFactory.getLogger(SetLevelCommand.class);

    {
        setDescription("Set a level up or down (you, a player, your companion or your party); restore it later.");
    }

    /** One parsed line: the target ("me", a name, or the party) and what it asks. */
    record Line(boolean party, String target, Ask ask, boolean auto) {
    }

    private final Function<LivingWorld, CompanionLevelTool> tools;
    /** What a player wears (production: their EQUIPPED tab with ItemInformationProvider's level requirements). */
    private final Function<Character, List<LevelSetPlan.GearPiece>> worn;

    public SetLevelCommand() {
        this(CompanionLevelTool::new, SetLevelCommand::equipped);
    }

    SetLevelCommand(Function<LivingWorld, CompanionLevelTool> tools, Function<Character, List<LevelSetPlan.GearPiece>> worn) {
        this.tools = tools;
        this.worn = worn;
    }

    /** Null when the line matches no form. */
    static Line parse(String[] params) {
        if (params.length < 2) {
            return null;
        }
        String target = params[0];
        boolean party = target.equalsIgnoreCase("party");
        String what = params[1].toLowerCase(Locale.ROOT);
        if (what.equals("restore") || what.equals("keep")) {
            if (params.length != 2 || (party && what.equals("keep"))) {
                return null;
            }
            return new Line(party, target, what.equals("restore") ? Ask.restoreIt() : Ask.keepIt(), false);
        }
        int level;
        try {
            level = Integer.parseInt(what);
        } catch (NumberFormatException notALevel) {
            return null;
        }
        Job job = null;
        boolean auto = false;
        for (int i = 2; i < params.length; i++) {
            if (params[i].equalsIgnoreCase("auto") && !auto) {
                auto = true;
            } else if (job == null && LevelSetPlan.parseJob(params[i]) != null) {
                job = LevelSetPlan.parseJob(params[i]);
            } else {
                return null;
            }
        }
        return new Line(party, target, Ask.to(level, job), auto);
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Line line = parse(params);
        if (line == null) {
            gm.yellowMessage(USAGE);
            return;
        }
        LivingWorld world = LivingWorld.get();
        if (line.party()) {
            party(gm, world, line);
            return;
        }
        Character human = line.target().equalsIgnoreCase("me") ? gm : onlinePlayer(c, line.target());
        if (human != null) {
            human(gm, human, world, line.ask(), line.auto()).thenAccept(reply -> reply(gm, reply));
            return;
        }
        if (world == null) {
            gm.yellowMessage("'" + line.target() + "' is no online player, and companions need the living world"
                    + " (living_world.ENABLED in config.yaml).");
            return;
        }
        if (line.auto()) {
            gm.yellowMessage("'auto' is for players: a companion spends its own AP and SP.");
            return;
        }
        CompanionLevelTool tool = tools.apply(world);
        world.executor().submit("gm.setlevel " + line.target(),
                () -> tool.use(gm, line.target(), line.ask(), true).thenAccept(reply -> reply(gm, reply)));
    }

    /** An online player of that name (not an Agent), else null. */
    private static Character onlinePlayer(Client c, String name) {
        Character found = c.getWorldServer().getPlayerStorage().getCharacterByName(name);
        return found != null && !AgentPresence.isAgent(found) ? found : null;
    }

    /**
     * One player's use, on the GM's thread (the record is read here, at most one indexed row, and written on
     * companion-ops). Completes with the reply; null when CommandTargetPolicy already answered.
     */
    CompletableFuture<String> human(Character gm, Character target, LivingWorld world, Ask ask, boolean auto) {
        boolean self = target == gm;
        if (!self && !CommandTargetPolicy.canAffect(gm, target, false)) {
            return done(null);
        }
        GmLevelHolds holds = world == null ? null : world.gmLevelHolds();
        String name = target.getName();
        Optional<Original> existing = holds == null ? Optional.empty() : holds.record(target.getId(), GmLevelHolds.SELF);
        if (ask.keep()) {
            if (holds == null) {
                return done(name + " has no record (the living world is off).");
            }
            if (existing == null || existing.isEmpty()) {
                return done(existing == null ? name + "'s record could not be read; try again." : name + " has no record.");
            }
            return holds.release(target.getId(), GmLevelHolds.SELF, "keep")
                    .thenApply(saved -> name + " keeps level " + target.getLevel() + " for good." + note(saved));
        }
        LevelSetPlan.Request request;
        LevelSetPlan.Stats override = null;
        if (ask.restore()) {
            if (existing == null) {
                return done(name + "'s record could not be read; try again.");
            }
            if (existing.isEmpty()) {
                return done(name + " has no record to restore" + (holds == null ? " (the living world is off)." : "."));
            }
            request = LevelSetPlan.Request.restoreTo(existing.get().level(), existing.get().job());
            override = existing.get().stats();
        } else {
            request = LevelSetPlan.Request.to(ask.level(), ask.job());
        }
        LevelSetPlan.Resolution resolution = LevelSetPlan.resolve(request, target.getJob(), target.getLevel(),
                target.isGM(), null);
        if (resolution instanceof LevelSetPlan.Refusal refusal) {
            return done(name + ": " + refusal.reason());
        }
        if (resolution instanceof LevelSetPlan.NoOp noOp) {
            if (noOp.restore() && holds != null) {
                return holds.release(target.getId(), GmLevelHolds.SELF, "restored")
                        .thenApply(saved -> name + " is at the original level already; record released." + note(saved));
            }
            return done(name + " is level " + target.getLevel() + " " + LevelSetPlan.jobName(target.getJob()) + " already.");
        }
        LevelSetPlan.Ok ok = (LevelSetPlan.Ok) resolution;
        LevelSetPlan.GearPlan gear = LevelSetPlan.gearPlan(ok.job(), ok.level(), false, worn.apply(target),
                target.getInventory(InventoryType.EQUIP).getNumFreeSlot());
        if (!gear.unequip().isEmpty()) {
            if (!self) {
                return done(name + " wears " + gear.unequip().size() + " piece(s) level " + ok.level()
                        + " does not allow; they take them off first.");
            }
            if (gear.refused()) {
                return done(gear.refusal());
            }
            for (short slot : gear.unequip()) {
                InventoryManipulator.unequip(target.getClient(), slot,
                        target.getInventory(InventoryType.EQUIP).getNextFreeSlot());
            }
        }
        long startedMs = System.currentTimeMillis();
        Original from = new Original(target.getLevel(), target.getJob(), new LevelSetPlan.Stats(target.getStr(),
                target.getDex(), target.getInt(), target.getLuk(), target.getRemainingAp()));
        GmLevelHolds.Pending pending = holds == null ? null : holds.begin(target.getId(), GmLevelHolds.SELF, existing,
                from, ask.restore(), true, ok.level(), ok.job());
        LevelSetPlan.AdminLevelChange change = target.adminSetLevel(ok, override);
        if (auto) {
            AgentSpBuildProfileService.assignOffline(target);
            AgentCharacterShaper.assignAp(target, target.getJob(), Map.of());
        }
        target.saveCharToDB();
        if (world != null && world.markers() != null) {
            world.markers().forget(target);
        }
        if (!self) {
            target.dropMessage(5, "A GM set you to level " + change.toLevel() + " "
                    + LevelSetPlan.jobName(change.toJob()) + ".");
        }
        String text = name + " is now level " + change.toLevel() + " " + LevelSetPlan.jobName(change.toJob())
                + " (was " + change.fromLevel() + " " + LevelSetPlan.jobName(change.fromJob()) + "): "
                + target.getRemainingAp() + " AP, " + target.getRemainingSps()[0] + " SP"
                + (change.dropped().isEmpty() ? "" : ", " + change.dropped().size() + " skills dropped")
                + (pending != null && pending.decision() == GmLevelHolds.Decision.RELEASE ? "; record released." : ".");
        log.info("gm setlevel: gm={} target={} ({}) from={}/{} to={}/{} ap={} sp={} dropped={} auto={} record={} ms={}",
                gm.getName(), name, target.getId(), change.fromLevel(), change.fromJob().getId(), change.toLevel(),
                change.toJob().getId(), target.getRemainingAp(), change.spRemaining(), change.dropped().size(), auto,
                pending == null ? "none" : pending.decision(), System.currentTimeMillis() - startedMs);
        if (pending == null) {
            return done(text + " (no record: the living world is off)");
        }
        return pending.settle(true).thenApply(saved -> text + note(saved));
    }

    /**
     * Everyone in the GM's party: the players on this thread first, then each companion on its own companion-ops leg; one
     * reply once every leg answered (or timed out), on companion-ops. Nothing here waits for a leg.
     */
    private void party(Character gm, LivingWorld world, Line line) {
        Party party = gm.getParty();
        if (party == null) {
            gm.yellowMessage("You are not in a party.");
            return;
        }
        if (line.auto() || (line.ask().job() != null)) {
            gm.yellowMessage("'!setlevel party' takes a level or 'restore' only: every member keeps its own job line.");
            return;
        }
        long startedMs = System.currentTimeMillis();
        GmLevelHolds holds = world == null ? null : world.gmLevelHolds();
        if (holds != null) {
            holds.busy(gm.getId(), true);
        }
        try {
            partyLegs(gm, world, line, party, holds, startedMs);
        } catch (RuntimeException failure) {
            if (holds != null) {
                holds.busy(gm.getId(), false);
            }
            log.warn("gm setlevel party failed for {}", gm.getName(), failure);
            gm.yellowMessage("The party set failed: " + failure.getMessage());
        }
    }

    private void partyLegs(Character gm, LivingWorld world, Line line, Party party, GmLevelHolds holds, long startedMs) {
        List<PartyCharacter> members = new ArrayList<>(party.getMembers());
        String[] lines = new String[members.size()];
        List<CompletableFuture<Void>> legs = new ArrayList<>();
        List<Integer> companions = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            Character player = members.get(i).getPlayer();
            int at = i;
            if (player == null) {
                lines[at] = members.get(i).getName() + " is not on this channel.";
            } else if (!AgentPresence.isAgent(player)) {
                legs.add(guard(human(gm, player, world, line.ask(), false), player.getName()).thenAccept(l -> lines[at] = l));
            } else {
                companions.add(i);
            }
        }
        CompanionLevelTool tool = world == null ? null : tools.apply(world);
        for (int i : companions) {
            String name = members.get(i).getName();
            if (tool == null) {
                lines[i] = name + ": companions need the living world.";
                continue;
            }
            CompletableFuture<String> leg = world.executor()
                    .call("gm.setlevel party " + name, () -> tool.use(gm, name, line.ask(), false))
                    .thenCompose(f -> f);
            legs.add(guard(leg, name).thenAccept(l -> lines[i] = l));
        }
        CompletableFuture.allOf(legs.toArray(new CompletableFuture[0])).whenComplete((done, failure) -> {
            Runnable answer = () -> {
                if (holds != null) {
                    holds.busy(gm.getId(), false);
                }
                List<String> said = new ArrayList<>();
                for (String l : lines) {
                    if (l != null) {
                        said.add(l);
                    }
                }
                gm.yellowMessage("Party " + (line.ask().restore() ? "restore" : "level " + line.ask().level()) + ": "
                        + String.join(" | ", said));
                log.info("gm setlevel party: gm={} members={} {} total_ms={}", gm.getName(), members.size(),
                        line.ask().restore() ? "restore" : "level=" + line.ask().level(),
                        System.currentTimeMillis() - startedMs);
            };
            if (world != null) {
                world.executor().submit("gm.setlevel party reply", answer);
            } else {
                answer.run();
            }
        });
    }

    private static CompletableFuture<String> guard(CompletableFuture<String> leg, String name) {
        return leg.exceptionally(failure -> {
            log.warn("gm setlevel: {} failed", name, failure);
            return name + ": the set failed.";
        });
    }

    private static List<LevelSetPlan.GearPiece> equipped(Character chr) {
        List<LevelSetPlan.GearPiece> pieces = new ArrayList<>();
        ItemInformationProvider ii = null;
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            if (!(item instanceof Equip equip)) {
                continue;
            }
            if (ii == null) {
                ii = ItemInformationProvider.getInstance();
            }
            Integer reqLevel = ii.getEquipLevelReq(equip.getItemId());
            pieces.add(new LevelSetPlan.GearPiece(equip.getPosition(), equip.getItemId(),
                    reqLevel == null ? 0 : reqLevel, 0, ii.isCash(equip.getItemId())));
        }
        return pieces;
    }

    private static void reply(Character gm, String reply) {
        if (reply != null) {
            gm.yellowMessage(reply);
        }
    }

    private static String note(boolean saved) {
        return saved ? "" : " (record not saved)";
    }

    private static CompletableFuture<String> done(String line) {
        return CompletableFuture.completedFuture(line);
    }
}
