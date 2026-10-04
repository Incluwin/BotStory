package server.living.roster;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.AgentLevelSetService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentShaper;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.GmLevelHolds.Original;
import server.living.store.LivingStore.RosterState;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The companion side of {@code !setlevel} (GM 6): sets one of the GM's own companions out with them, or a plain Agent
 * they lead, through its own mailbox (AgentLevelSetService), with its GmLevelHolds record kept. Runs on companion-ops.
 *
 * <p>Refused for anyone else's companion, a Resident, a citizen, a companion that is not ACTIVE or not loaded, one on
 * a live bench slice, in an event instance, in a party quest engagement or a companion run, or whose owner's Yes is
 * being carried out. With "Keep my crew fresh" on, a single set that leaves the companion AUTO_ROTATE_LEVEL_GAP or more
 * levels off its owner is refused (the rotation would swap it straight out).</p>
 *
 * <p>The record is written before the set goes to the mailbox and reconciled when the mailbox's own future completes,
 * however late; the reply waits at most {@link #ANSWER_TIMEOUT_MS} (a copy of the future times out, never the
 * original).</p>
 */
public final class CompanionLevelTool {
    public static final long ANSWER_TIMEOUT_MS = 10_000L;
    private static final Logger log = LoggerFactory.getLogger(CompanionLevelTool.class);

    /** The Agent engine as this tool uses it (production: {@link #LIVE}). */
    interface Agents {
        AgentRuntimeEntry runtime(int characterId);

        int leaderOf(int characterId);

        boolean engaged(int characterId);

        CompletableFuture<AgentLevelSetService.Outcome> dispatch(AgentRuntimeEntry runtime, LevelSetPlan.Request request,
                                                                 LevelSetPlan.Companion companion, Character owner);
    }

    static final Agents LIVE = new Agents() {
        @Override
        public AgentRuntimeEntry runtime(int characterId) {
            return AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        }

        @Override
        public int leaderOf(int characterId) {
            return AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId);
        }

        @Override
        public boolean engaged(int characterId) {
            return AgentPartyQuestEngagementRegistry.forMember(characterId) != null;
        }

        @Override
        public CompletableFuture<AgentLevelSetService.Outcome> dispatch(AgentRuntimeEntry runtime,
                                                                        LevelSetPlan.Request request,
                                                                        LevelSetPlan.Companion companion,
                                                                        Character owner) {
            return AgentMailboxRuntime.dispatch(runtime, entry -> AgentLevelSetService.apply(entry, entry.bot(), request,
                    companion, owner, System.currentTimeMillis()));
        }
    };

    /** What one use asks of a target: a level (and job), its record back ({@code restore}), or its record dropped. */
    public record Ask(int level, Job job, boolean restore, boolean keep) {
        public static Ask to(int level, Job job) {
            return new Ask(level, job, false, false);
        }

        public static Ask restoreIt() {
            return new Ask(0, null, true, false);
        }

        public static Ask keepIt() {
            return new Ask(0, null, false, true);
        }
    }

    private final LivingWorld world;
    private final Agents agents;
    private final long answerTimeoutMs;

    public CompanionLevelTool(LivingWorld world) {
        this(world, LIVE, ANSWER_TIMEOUT_MS);
    }

    CompanionLevelTool(LivingWorld world, Agents agents, long answerTimeoutMs) {
        this.world = world;
        this.agents = agents;
        this.answerTimeoutMs = answerTimeoutMs;
    }

    /** A target: its character, and for a companion its persona id (null for a plain Agent). */
    private record Target(Character bot, String personaId, Persona persona) {
    }

    /**
     * One target's use, on companion-ops. {@code single}: the only target of the command (the Keep my crew fresh gap
     * applies). Completes with the line to reply once its record is reconciled, or at the answer timeout; never
     * exceptionally.
     */
    public CompletableFuture<String> use(Character gm, String name, Ask ask, boolean single) {
        try {
            return useChecked(gm, name, ask, single);
        } catch (RuntimeException failure) {
            log.warn("gm setlevel: {} failed for {}", name, gm.getName(), failure);
            return CompletableFuture.completedFuture(name + ": the set failed (" + failure.getMessage() + ").");
        }
    }

    private CompletableFuture<String> useChecked(Character gm, String name, Ask ask, boolean single) {
        Object found = target(gm, name);
        if (found instanceof String refusal) {
            return CompletableFuture.completedFuture(refusal);
        }
        Target target = (Target) found;
        Character bot = target.bot();
        GmLevelHolds holds = world.gmLevelHolds();
        boolean companion = target.personaId() != null;
        Optional<Original> existing = companion ? holds.record(bot.getId(), target.personaId()) : Optional.empty();
        if (ask.keep()) {
            if (!companion) {
                return done(bot.getName() + " has no record to keep (a plain Agent has none).");
            }
            if (existing == null || existing.isEmpty()) {
                return done(existing == null ? bot.getName() + "'s record could not be read; try again."
                        : bot.getName() + " has no record to keep.");
            }
            return holds.release(bot.getId(), target.personaId(), "keep")
                    .thenApply(saved -> bot.getName() + " keeps level " + bot.getLevel() + " for good." + note(saved));
        }
        LevelSetPlan.Request request;
        if (ask.restore()) {
            if (existing == null) {
                return done(bot.getName() + "'s record could not be read; try again.");
            }
            if (existing.isEmpty()) {
                return done(bot.getName() + " has no record to restore.");
            }
            request = LevelSetPlan.Request.restoreTo(existing.get().level(), existing.get().job());
        } else {
            request = LevelSetPlan.Request.to(ask.level(), ask.job());
        }
        LevelSetPlan.Companion family = companion ? new LevelSetPlan.Companion(
                existing == null ? null : existing.map(Original::job).orElse(null), personaJob(target.persona())) : null;
        LevelSetPlan.Resolution resolution = LevelSetPlan.resolve(request, bot.getJob(), bot.getLevel(), bot.isGM(),
                family);
        if (resolution instanceof LevelSetPlan.Refusal refusal) {
            return done(bot.getName() + ": " + refusal.reason());
        }
        if (resolution instanceof LevelSetPlan.NoOp noOp) {
            if (noOp.restore() && companion) {
                return holds.release(bot.getId(), target.personaId(), "restored")
                        .thenApply(saved -> bot.getName() + " is at its original level already; record released."
                                + note(saved));
            }
            return done(bot.getName() + " is level " + bot.getLevel() + " " + LevelSetPlan.jobName(bot.getJob())
                    + " already.");
        }
        LevelSetPlan.Ok ok = (LevelSetPlan.Ok) resolution;
        if (companion && single && world.crewRotation() != null && world.crewRotation().keepsFresh(gm.getId())
                && Math.abs(ok.level() - gm.getLevel()) >= world.config().AUTO_ROTATE_LEVEL_GAP) {
            return done("Keep my crew fresh is on and " + bot.getName() + " would be " + Math.abs(ok.level() - gm.getLevel())
                    + " levels off you, so it would be swapped straight out: turn it off in the Crew window, or set"
                    + " everyone with '!setlevel party " + ok.level() + "'.");
        }
        GmLevelHolds.Pending pending = companion ? holds.begin(bot.getId(), target.personaId(), existing,
                new Original(bot.getLevel(), bot.getJob(), null), ask.restore(), true, ok.level(), ok.job()) : null;
        AgentRuntimeEntry runtime = agents.runtime(bot.getId());
        CompletableFuture<AgentLevelSetService.Outcome> raw = runtime == null
                ? CompletableFuture.failedFuture(new IllegalStateException("not running"))
                : agents.dispatch(runtime, request, family, gm);
        CompletableFuture<String> line = new CompletableFuture<>();
        long startedMs = System.currentTimeMillis();
        raw.whenComplete((outcome, failure) -> world.executor().submit("gm.setlevel reconcile " + bot.getName(),
                () -> reconcile(gm, target, pending, outcome, failure, startedMs, line)));
        raw.copy().orTimeout(answerTimeoutMs, TimeUnit.MILLISECONDS).whenComplete((outcome, failure) -> {
            if (unwrap(failure) instanceof TimeoutException) {
                world.executor().submit("gm.setlevel timeout " + bot.getName(), () -> line.complete(bot.getName()
                        + " did not answer within " + answerTimeoutMs / 1000 + " s; the set still applies on its next"
                        + " tick and its record follows it."));
            }
        });
        return line;
    }

    /** On companion-ops, once the mailbox's own future completed: the record, the views, the markers, the reply. */
    private void reconcile(Character gm, Target target, GmLevelHolds.Pending pending,
                           AgentLevelSetService.Outcome outcome, Throwable failure, long startedMs,
                           CompletableFuture<String> line) {
        try {
            reconcileChecked(gm, target, pending, outcome, failure, startedMs, line);
        } catch (RuntimeException broken) {
            log.warn("gm setlevel: reconciling {} for {} failed", target.bot().getName(), gm.getName(), broken);
            line.complete(target.bot().getName() + ": the set's record could not be reconciled ("
                    + broken.getMessage() + ").");
        }
    }

    private void reconcileChecked(Character gm, Target target, GmLevelHolds.Pending pending,
                                  AgentLevelSetService.Outcome outcome, Throwable failure, long startedMs,
                                  CompletableFuture<String> line) {
        Character bot = target.bot();
        boolean applied = outcome instanceof AgentLevelSetService.Applied
                || (outcome instanceof AgentLevelSetService.Unchanged unchanged && unchanged.restore());
        boolean saved = pending == null || pending.settle(applied).getNow(true);
        String text;
        if (outcome instanceof AgentLevelSetService.Applied set) {
            if (target.personaId() != null) {
                CompanionViews.invalidate(world, gm.getId(), target.personaId());
            }
            if (world.markers() != null) {
                world.markers().forget(bot);
            }
            LevelSetPlan.AdminLevelChange change = set.change();
            text = bot.getName() + " is now level " + change.toLevel() + " " + LevelSetPlan.jobName(change.toJob())
                    + " (was " + change.fromLevel() + " " + LevelSetPlan.jobName(change.fromJob()) + ")"
                    + (pending != null && pending.decision() == GmLevelHolds.Decision.RELEASE ? "; record released." : ".");
            log.info("gm setlevel: gm={} target={} ({}) persona={} from={}/{} to={}/{} ap={} sp={} kept={} dropped={}"
                            + " record={} mailbox_ms={} total_ms={}", gm.getName(), bot.getName(), bot.getId(),
                    target.personaId(), change.fromLevel(), change.fromJob().getId(), change.toLevel(),
                    change.toJob().getId(), change.apRemaining(), change.spRemaining(), change.skillsKept(),
                    change.dropped().size(), pending == null ? "none" : pending.decision(), set.elapsedMs(),
                    System.currentTimeMillis() - startedMs);
        } else if (outcome instanceof AgentLevelSetService.Unchanged) {
            text = bot.getName() + " is level " + bot.getLevel() + " " + LevelSetPlan.jobName(bot.getJob())
                    + " already" + (applied ? "; record released." : ".");
        } else if (outcome instanceof AgentLevelSetService.Refused refused) {
            text = bot.getName() + ": " + refused.reason();
        } else {
            Throwable cause = unwrap(failure);
            log.warn("gm setlevel: {} for {} did not apply", bot.getName(), gm.getName(), cause);
            text = bot.getName() + " could not be set (" + (cause == null ? "no answer" : cause.getMessage()) + ").";
        }
        String reply = text + note(saved);
        if (!line.complete(reply)) {
            gm.yellowMessage("(late) " + reply); // the timeout line went out first
        }
    }

    /** The target {@code name} names for {@code gm}, or the refusal to reply. */
    private Object target(Character gm, String name) {
        Relation relation = world.directory().resolveName(gm, name);
        if (relation instanceof Relation.OwnCompanion own) {
            String personaId = own.entry().personaId();
            if (own.entry().state() != RosterState.ACTIVE) {
                return name + " is not out with you (" + own.entry().state().name().toLowerCase() + ").";
            }
            Character bot = world.roster().loaded(personaId);
            if (bot == null) {
                return name + " is not loaded.";
            }
            if (world.benchDuty().live(personaId)) {
                return name + " is on a bench slice; call it first.";
            }
            String busy = busy(bot);
            if (busy != null) {
                return busy;
            }
            CompanionPartyQuestService pq = world.partyQuests();
            if (pq != null && (pq.runForCharacter(bot.getId()) != null || pq.entering(gm.getId()))) {
                return name + " is in a companion party quest run.";
            }
            return new Target(bot, personaId, world.content().persona(personaId));
        }
        if (relation instanceof Relation.PlainAgent plain) {
            if (agents.leaderOf(plain.characterId()) != gm.getId()) {
                return name + " is an Agent you do not lead.";
            }
            AgentRuntimeEntry runtime = agents.runtime(plain.characterId());
            Character bot = runtime == null ? null : runtime.bot();
            if (bot == null) {
                return name + " is not loaded.";
            }
            String busy = busy(bot);
            return busy != null ? busy : new Target(bot, null, null);
        }
        return switch (relation) {
            case Relation.OthersCompanion others -> name + " is another player's companion.";
            case Relation.Resident resident -> name + " is a Resident; only your own companions can be set.";
            case Relation.Citizen citizen -> name + " is a citizen; only your own companions can be set.";
            case Relation.HireOfferPending offer -> name + " is a citizen; only your own companions can be set.";
            case Relation.Human human -> name + " is a player; use their name while they are online.";
            default -> "No companion or Agent named '" + name + "'.";
        };
    }

    private String busy(Character bot) {
        if (bot.getEventInstance() != null) {
            return bot.getName() + " is in an event instance.";
        }
        if (agents.engaged(bot.getId())) {
            return bot.getName() + " is in a party quest.";
        }
        return null;
    }

    private static Job personaJob(Persona persona) {
        if (persona == null || persona.job() == null) {
            return null;
        }
        try {
            return ResidentShaper.job(persona.job());
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static CompletableFuture<String> done(String line) {
        return CompletableFuture.completedFuture(line);
    }

    static String note(boolean saved) {
        return saved ? "" : " (record not saved)";
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }
}
