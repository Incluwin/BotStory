package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.Job;
import client.LevelSetPlan;
import client.Skill;
import client.command.Command;
import client.command.CommandTargetPolicy;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.roster.CompanionLevelTool;
import server.living.roster.CompanionLevelTool.Ask;
import server.living.roster.CompanionPartyQuestService;
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
 *   <li>{@code !setlevel <me|name> <level> [job] [auto] [force]}: a level, optionally a job (an id or a name:
 *   "fighter", "412"); {@code auto} (players only) spends the AP and SP as an Agent would; {@code force} lets a set drop
 *   another player's skills;</li>
 *   <li>{@code !setlevel <me|name> restore}: back to the level, job, EXP and (a player) stats before the first set;</li>
 *   <li>{@code !setlevel <name> keep}: keep the level it has now and drop the record;</li>
 *   <li>{@code !setlevel party <level> [force] | restore}: everyone in the GM's party, or nobody when any of them
 *   cannot be set.</li>
 * </ul>
 * A name is an online player (CommandTargetPolicy; nothing they wear may need to come off), or one of the GM's own
 * companions out with them, or a plain Agent they lead (CompanionLevelTool, on companion-ops). Nobody dead, in an event
 * instance, in a party quest or a companion run is set. The record of what each character was before its first set is
 * kept in the living world (GmLevelHolds); without it players can still be set, with no record, and companions cannot.
 * A restore does not give back skills a set dropped (nor skill-book master levels), so a set that would drop another
 * player's skills needs {@code force}.
 */
public final class SetLevelCommand extends Command {
    static final String USAGE = "Syntax: !setlevel <me|name> <level> [job] [auto] [force] | !setlevel <me|name> restore"
            + " | !setlevel <name> keep | !setlevel party <level> [force] | !setlevel party restore."
            + " restore gives back the level, job, EXP and stats, not skills or skill-book master levels a set dropped:"
            + " dropping another player's skills needs 'force'.";
    private static final Logger log = LoggerFactory.getLogger(SetLevelCommand.class);

    {
        setDescription("Set a level up or down (you, a player, your companion or your party); restore it later.");
    }

    /** One parsed line: the target ("me", a name, or the party) and what it asks. */
    record Line(boolean party, String target, Ask ask, boolean auto, boolean force) {
    }

    /** What one player's use comes to, worked out before anything changes. */
    sealed interface HumanUse permits Refused, Answer, Release, Apply {
    }

    /** Not set: {@code line} is the reply (null when CommandTargetPolicy already answered the GM). */
    record Refused(String name, String line) implements HumanUse {
    }

    /** Nothing to change: {@code line} is the reply. */
    record Answer(String line) implements HumanUse {
    }

    /** Nothing to set: the record is released ({@code why} is the row's detail) and {@code line} is the reply. */
    record Release(String why, String line) implements HumanUse {
    }

    /**
     * A set to apply: {@code override} the stats to give back (a restore), {@code unequip} what the GM's own character
     * takes off first, {@code existing} the record as read and {@code releasing} a restore.
     */
    record Apply(LevelSetPlan.Ok ok, LevelSetPlan.Stats override, List<Short> unequip, Optional<Original> existing,
                 boolean releasing) implements HumanUse {
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
            return new Line(party, target, what.equals("restore") ? Ask.restoreIt() : Ask.keepIt(), false, false);
        }
        int level;
        try {
            level = Integer.parseInt(what);
        } catch (NumberFormatException notALevel) {
            return null;
        }
        Job job = null;
        boolean auto = false;
        boolean force = false;
        for (int i = 2; i < params.length; i++) {
            if (params[i].equalsIgnoreCase("auto") && !auto) {
                auto = true;
            } else if (params[i].equalsIgnoreCase("force") && !force) {
                force = true;
            } else if (job == null && LevelSetPlan.parseJob(params[i]) != null) {
                job = LevelSetPlan.parseJob(params[i]);
            } else {
                return null;
            }
        }
        return new Line(party, target, Ask.to(level, job), auto, force);
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
            human(gm, human, world, line.ask(), line.auto(), line.force()).thenAccept(reply -> reply(gm, reply));
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
     * One player's use (the record is read here: a query over that character's audit rows, once a boot, and written
     * on companion-ops). Completes with the reply; null when CommandTargetPolicy already answered.
     */
    CompletableFuture<String> human(Character gm, Character target, LivingWorld world, Ask ask, boolean auto,
                                    boolean force) {
        HumanUse use = prepare(gm, target, world, ask, force);
        return switch (use) {
            case Refused refused -> done(refused.line());
            case Answer answer -> done(answer.line());
            case Release release -> world.gmLevelHolds().release(target.getId(), GmLevelHolds.SELF, release.why())
                    .thenApply(saved -> release.line() + note(saved));
            case Apply apply -> apply(gm, target, world, apply, auto);
        };
    }

    /** Everything a player's use checks, with nothing changed yet. */
    HumanUse prepare(Character gm, Character target, LivingWorld world, Ask ask, boolean force) {
        boolean self = target == gm;
        String name = target.getName();
        if (!self && !CommandTargetPolicy.canAffect(gm, target, false)) {
            return new Refused(name, null);
        }
        GmLevelHolds holds = world == null ? null : world.gmLevelHolds();
        if (holds != null && holds.settling(target.getId(), GmLevelHolds.SELF)) {
            return new Refused(name, name + "'s last set is still being recorded; try again in a moment.");
        }
        Optional<Original> existing = holds == null ? Optional.empty() : holds.record(target.getId(), GmLevelHolds.SELF);
        if (existing == null) {
            return new Refused(name, name + "'s record could not be read; try again.");
        }
        if (ask.keep()) {
            if (holds == null) {
                return new Answer(name + " has no record (the living world is off).");
            }
            return existing.isEmpty() ? new Answer(name + " has no record.")
                    : new Release("keep", name + " keeps level " + target.getLevel() + " for good.");
        }
        String busy = busy(world, target);
        if (busy != null) {
            return new Refused(name, busy);
        }
        Original original = existing.orElse(null);
        LevelSetPlan.Request request;
        if (ask.restore()) {
            if (original == null) {
                return new Answer(name + " has no record to restore" + (holds == null ? " (the living world is off)." : "."));
            }
            request = LevelSetPlan.Request.restoreTo(original.level(), original.job(), original.exp());
        } else {
            request = LevelSetPlan.Request.to(ask.level(), ask.job());
        }
        LevelSetPlan.Resolution resolution = LevelSetPlan.resolve(request, target.getJob(), target.getLevel(),
                target.isGM(), null);
        if (resolution instanceof LevelSetPlan.Refusal refusal) {
            return new Refused(name, name + ": " + refusal.reason());
        }
        LevelSetPlan.Ok ok;
        if (resolution instanceof LevelSetPlan.NoOp noOp) {
            if (!noOp.restore() || original == null) {
                return new Answer(name + " is level " + target.getLevel() + " " + LevelSetPlan.jobName(target.getJob())
                        + " already.");
            }
            LevelSetPlan.Stats now = statsOf(target);
            if (original.stats() == null || original.stats().equals(now)) {
                return new Release("restored", name + " is at the original level already; record released.");
            }
            if (!LevelSetPlan.statsFit(original.stats(), target.getJob(), target.getLevel(), target.getHpMpApUsed(),
                    YamlConfig.config.server.USE_STARTING_AP_4)) {
                return new Refused(name, name + " is at the original level and job, but the recorded stats no longer"
                        + " fit its AP total: '!setlevel " + name + " keep' keeps the stats it has and drops the record.");
            }
            ok = new LevelSetPlan.Ok(target.getJob(), target.getLevel(), true, target.getExp()); // the stats only
        } else {
            ok = (LevelSetPlan.Ok) resolution;
            if (!ok.restore() && original != null && original.level() == ok.level() && original.job() == ok.job()) {
                ok = new LevelSetPlan.Ok(ok.job(), ok.level(), true, original.exp()); // back to the record: a restore
            }
        }
        LevelSetPlan.GearPlan gear = LevelSetPlan.gearPlan(ok.job(), ok.level(), false, worn.apply(target),
                target.getInventory(InventoryType.EQUIP).getNumFreeSlot());
        if (!gear.unequip().isEmpty()) {
            if (!self) {
                return new Refused(name, name + " wears " + gear.unequip().size() + " piece(s) level " + ok.level()
                        + " does not allow; they take them off first.");
            }
            if (gear.refused()) {
                return new Refused(name, gear.refusal());
            }
        }
        if (!self && !ok.restore() && !force) {
            int dropped = LevelSetPlan.skillPlan(ok.job(), ok.level(), holdings(target)).drop().size();
            if (dropped > 0) {
                return new Refused(name, name + " would lose " + dropped + " skill" + (dropped == 1 ? "" : "s")
                        + ", and restore does not give skills or skill books back: add 'force' to set them anyway.");
            }
        }
        return new Apply(ok, ok.restore() && original != null ? original.stats() : null, gear.unequip(), existing,
                ask.restore());
    }

    /** Why a player cannot be set now, or null: down, in an event instance, a party quest or a companion run. */
    static String busy(LivingWorld world, Character target) {
        String name = target.getName();
        if (!target.isAlive()) {
            return name + " is down.";
        }
        if (target.getEventInstance() != null) {
            return name + " is in an event instance.";
        }
        if (AgentPartyQuestEngagementRegistry.forMember(target.getId()) != null) {
            return name + " is in a party quest.";
        }
        CompanionPartyQuestService pq = world == null ? null : world.partyQuests();
        if (pq != null && pq.busyFor(target.getId())) {
            return name + " is in a companion party quest run.";
        }
        return null;
    }

    private CompletableFuture<String> apply(Character gm, Character target, LivingWorld world, Apply use, boolean auto) {
        String name = target.getName();
        for (short slot : use.unequip()) {
            InventoryManipulator.unequip(target.getClient(), slot, target.getInventory(InventoryType.EQUIP).getNextFreeSlot());
        }
        long startedMs = System.currentTimeMillis();
        GmLevelHolds holds = world == null ? null : world.gmLevelHolds();
        LevelSetPlan.Ok ok = use.ok();
        Original from = new Original(target.getLevel(), target.getJob(), target.getExp(), statsOf(target));
        GmLevelHolds.Pending pending = holds == null ? null : holds.begin(target.getId(), GmLevelHolds.SELF,
                use.existing(), from, use.releasing(), true, ok.level(), ok.job());
        LevelSetPlan.AdminLevelChange change;
        try {
            change = target.adminSetLevel(ok, use.override());
        } catch (RuntimeException failure) {
            if (pending != null) {
                pending.abandon();
            }
            throw failure;
        }
        if (auto) {
            AgentSpBuildProfileService.assignOffline(target);
            AgentCharacterShaper.assignAp(target, target.getJob(), Map.of());
        }
        target.saveCharToDB();
        if (world != null && world.markers() != null) {
            world.markers().forget(target);
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
     * Everyone in the GM's party, or nobody: every player is checked here and every companion on companion-ops before
     * anyone is set; then the players and the companions are set there, each companion on its own mailbox. One reply
     * once every leg answered (or timed out), on companion-ops. Nothing here waits for a leg.
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
            partyFailed(gm, holds, failure);
        }
    }

    private void partyLegs(Character gm, LivingWorld world, Line line, Party party, GmLevelHolds holds, long startedMs) {
        List<PartyCharacter> members = new ArrayList<>(party.getMembers());
        String[] lines = new String[members.size()];
        List<Integer> players = new ArrayList<>();
        List<Integer> companions = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            Character player = members.get(i).getPlayer();
            if (player == null) {
                lines[i] = members.get(i).getName() + " is not on this channel.";
            } else if (AgentPresence.isAgent(player)) {
                companions.add(i);
                if (world == null) {
                    refusals.add(player.getName() + ": companions need the living world.");
                }
            } else {
                players.add(i);
                if (prepare(gm, player, world, line.ask(), line.force()) instanceof Refused refused) {
                    refusals.add(refused.line() != null ? refused.line() : player.getName() + ": not yours to set.");
                }
            }
        }
        if (!refusals.isEmpty()) {
            partyAnswer(gm, holds, line, members.size(), startedMs, nothingSet(refusals));
            return;
        }
        if (world == null) {
            List<CompletableFuture<Void>> legs = new ArrayList<>();
            for (int i : players) {
                legs.add(playerLeg(gm, members.get(i).getPlayer(), world, line, lines, i));
            }
            whenAllAnswered(gm, world, holds, line, members.size(), startedMs, lines, legs);
            return;
        }
        CompanionLevelTool tool = tools.apply(world);
        world.executor().submit("gm.setlevel party " + gm.getName(), () -> {
            try {
                partyOnOps(gm, world, line, members, players, companions, tool, holds, startedMs, lines);
            } catch (RuntimeException failure) {
                partyFailed(gm, holds, failure);
            }
        });
    }

    /** On companion-ops: every companion checked, then everyone set. */
    private void partyOnOps(Character gm, LivingWorld world, Line line, List<PartyCharacter> members,
                            List<Integer> players, List<Integer> companions, CompanionLevelTool tool,
                            GmLevelHolds holds, long startedMs, String[] lines) {
        List<String> refusals = new ArrayList<>();
        for (int i : companions) {
            String refusal = tool.check(gm, members.get(i).getName(), line.ask());
            if (refusal != null) {
                refusals.add(refusal);
            }
        }
        if (!refusals.isEmpty()) {
            partyAnswer(gm, holds, line, members.size(), startedMs, nothingSet(refusals));
            return;
        }
        List<CompletableFuture<Void>> legs = new ArrayList<>();
        for (int i : players) {
            legs.add(playerLeg(gm, members.get(i).getPlayer(), world, line, lines, i));
        }
        for (int i : companions) {
            String name = members.get(i).getName();
            legs.add(guard(tool.use(gm, name, line.ask(), false), name).thenAccept(l -> lines[i] = l));
        }
        whenAllAnswered(gm, world, holds, line, members.size(), startedMs, lines, legs);
    }

    private CompletableFuture<Void> playerLeg(Character gm, Character player, LivingWorld world, Line line,
                                              String[] lines, int at) {
        CompletableFuture<String> leg;
        try {
            leg = human(gm, player, world, line.ask(), false, line.force());
        } catch (RuntimeException failure) {
            leg = CompletableFuture.failedFuture(failure);
        }
        return guard(leg, player.getName()).thenAccept(l -> lines[at] = l);
    }

    private void whenAllAnswered(Character gm, LivingWorld world, GmLevelHolds holds, Line line, int memberCount,
                                 long startedMs, String[] lines, List<CompletableFuture<Void>> legs) {
        CompletableFuture.allOf(legs.toArray(new CompletableFuture[0])).whenComplete((done, failure) -> {
            Runnable answer = () -> {
                List<String> said = new ArrayList<>();
                for (String l : lines) {
                    if (l != null) {
                        said.add(l);
                    }
                }
                partyAnswer(gm, holds, line, memberCount, startedMs, String.join(" | ", said));
            };
            if (world != null) {
                world.executor().submit("gm.setlevel party reply", answer);
            } else {
                answer.run();
            }
        });
    }

    private static void partyAnswer(Character gm, GmLevelHolds holds, Line line, int memberCount, long startedMs,
                                    String text) {
        if (holds != null) {
            holds.busy(gm.getId(), false);
        }
        gm.yellowMessage("Party " + (line.ask().restore() ? "restore" : "level " + line.ask().level()) + ": " + text);
        log.info("gm setlevel party: gm={} members={} {} total_ms={}", gm.getName(), memberCount,
                line.ask().restore() ? "restore" : "level=" + line.ask().level(), System.currentTimeMillis() - startedMs);
    }

    private static void partyFailed(Character gm, GmLevelHolds holds, RuntimeException failure) {
        if (holds != null) {
            holds.busy(gm.getId(), false);
        }
        log.warn("gm setlevel party failed for {}", gm.getName(), failure);
        gm.yellowMessage("The party set failed: " + failure.getMessage());
    }

    private static String nothingSet(List<String> refusals) {
        return "nobody was set. " + String.join(" | ", refusals);
    }

    private static CompletableFuture<String> guard(CompletableFuture<String> leg, String name) {
        return leg.exceptionally(failure -> {
            log.warn("gm setlevel: {} failed", name, failure);
            return name + ": the set failed.";
        });
    }

    private static LevelSetPlan.Stats statsOf(Character chr) {
        return new LevelSetPlan.Stats(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk(), chr.getRemainingAp());
    }

    private static List<LevelSetPlan.SkillHolding> holdings(Character chr) {
        List<LevelSetPlan.SkillHolding> holdings = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> held : chr.getSkills().entrySet()) {
            holdings.add(new LevelSetPlan.SkillHolding(held.getKey().getId(), held.getValue().skillevel,
                    held.getValue().masterlevel));
        }
        return holdings;
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
