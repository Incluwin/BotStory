package server.living.roster;

import client.Character;
import client.QuestStatus;
import server.agents.capabilities.combat.AgentBuffStateRuntime;
import server.agents.capabilities.combat.AgentCombatBuffStateRuntime;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.capabilities.movement.AgentFormationCommandService;
import server.agents.capabilities.movement.AgentFormationRuntime;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.supplies.AgentPotionThresholds;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.quest.Quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companion settings on the Agents (docs/BOT_MANAGEMENT_PLAN.md P3b, "CompanionSettingsApplier").
 *
 * <p>It answers the Agent layer's owner-free seams from memory, on whatever thread asks: the potion percentages
 * (AgentPotionThresholds) and the loot policy (AgentLootPolicy) of a companion out with its owner, that is one whose
 * runtime leader is its roster owner. A live bench slice (which leads itself), a Resident and every other Agent get
 * no answer, so they drink and loot as configured. A companion whose potion percentages are the configured ones gets
 * no potion answer either, and one that loots everything, boss drops included, no loot answer; the defaults leave a
 * boss's drops to the humans (the plan's NO_BOSS default).</p>
 *
 * <p>On companion-ops it keeps each companion's {@link CompanionSettings}, read from settings_json on first use and
 * written back on every change, and applies them to a loaded companion on every path that loads or seats it (a call
 * and its arrival, a swap, a login restore and so the boot's, channel follow and its fallback, a rejoin): it binds the
 * character to its owner's settings, sets its support toggles quietly on its runtime, and puts each companion that
 * chose a formation of its own in its place. So the settings survive bench, relog and restart.</p>
 */
public final class CompanionSettingsApplier implements AgentPotionThresholds.Rule, AgentLootPolicy.Rule {
    /** Whose settings a loaded companion character runs on. */
    private record Bound(int ownerId, String personaId) { }

    private final LivingWorld world;
    private final RosterService roster;
    /** owner:persona -> the companion's settings, read from settings_json on first use. */
    private final Map<String, CompanionSettings> settings = new ConcurrentHashMap<>();
    /** Companion character id -> whose settings it runs on; bound at every load or seat. */
    private final Map<Integer, Bound> bound = new ConcurrentHashMap<>();

    CompanionSettingsApplier(LivingWorld world, RosterService roster) {
        this.world = world;
        this.roster = roster;
    }

    /** Installs the potion and loot seams (LivingWorld.start). */
    public void install() {
        AgentPotionThresholds.install(this);
        AgentLootPolicy.install(this);
    }

    /** Takes the seams out again (LivingWorld.stop): every Agent drinks and loots as configured. */
    public static void uninstall() {
        AgentPotionThresholds.install(null);
        AgentLootPolicy.install(null);
    }

    /** The companion's settings: from memory, else read from its settings_json once (defaults when it has none). */
    public CompanionSettings settings(int ownerId, String personaId) {
        String key = key(ownerId, personaId);
        CompanionSettings known = settings.get(key);
        if (known != null) {
            return known;
        }
        CompanionSettings read = CompanionSettings.parse(world.store().settings(ownerId, personaId));
        CompanionSettings raced = settings.putIfAbsent(key, read);
        return raced != null ? raced : read;
    }

    /**
     * Keeps {@code next} as the companion's settings, in memory (the seams answer from it at once) and in its
     * settings_json; its card's screens go stale. On companion-ops.
     */
    public void save(int ownerId, String personaId, CompanionSettings next) {
        settings.put(key(ownerId, personaId), next);
        world.store().saveSettings(ownerId, personaId, next.toJson());
        CompanionViews.invalidate(world, ownerId, personaId);
    }

    /**
     * Every path that loads or seats a companion calls this once it is loaded for its owner: the character runs on
     * the owner's settings for it from now, its support toggles are set on its runtime (only when they differ from the
     * Agent's defaults: a fresh runtime has those already, and a runtime an arrival or rejoin keeps is left as chat set
     * it), and the owner's companions who chose
     * a formation of their own take their places again (a load spreads the whole crew by the crew's formation). On
     * companion-ops.
     */
    public void apply(Character owner, Persona persona, Character bot) {
        if (owner == null || persona == null || bot == null) {
            return;
        }
        CompanionSettings own = settings(owner.getId(), persona.id());
        bound.put(bot.getId(), new Bound(owner.getId(), persona.id()));
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (runtime != null && !own.supportAsDefault()) {
            setSupport(runtime, own);
        }
        applyPlaces(owner);
    }

    /**
     * A changed support toggle on the companion's runtime, quietly, when it is loaded and led by its owner but takes
     * no order now (on its way, waiting outside a run): true when it was set. One out with its owner is told instead,
     * and answers for itself (CompanionManager.setSupport). On companion-ops.
     */
    boolean applySupport(Character owner, Persona persona) {
        Character bot = roster.loaded(persona.id());
        AgentRuntimeEntry runtime = bot == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (runtime == null || AgentRuntimeRegistry.leaderIdForAgentCharacter(bot.getId()) != owner.getId()) {
            return false;
        }
        setSupport(runtime, settings(owner.getId(), persona.id()));
        return true;
    }

    /**
     * A changed formation: the owner's crew out with them is spread by the crew's formation again, as a load spreads
     * it, and then each companion with a formation of its own takes its place ({@link #applyPlaces}). On companion-ops.
     */
    void applyFormation(Character owner) {
        AgentFormationService.applyOffsets(AgentRuntimeRegistry.entriesForLeader(owner.getId()),
                AgentFormationService.stateForLeader(AgentFormationService.formationsByLeaderId(), owner.getId(),
                        AgentFormationRuntime.defaultFormationState()));
        applyPlaces(owner);
    }

    private static void setSupport(AgentRuntimeEntry runtime, CompanionSettings own) {
        AgentMailboxRuntime.dispatch(runtime, entry -> {
            AgentCombatBuffStateRuntime.setSkillBuffsEnabled(entry, own.buffs());
            AgentCombatBuffStateRuntime.setSupportHealsEnabled(entry, own.heals());
            AgentBuffStateRuntime.setEnabled(entry, own.buffPotions());
            return null;
        });
    }

    /**
     * The owner's companions out with them that chose a formation of their own take their places in it: each group
     * that chose the same one is spread by the formation command's own offsets over just that group. The crew's
     * formation is not changed and nobody says a word; companions without one of their own keep the crew's places.
     */
    void applyPlaces(Character owner) {
        Map<String, List<AgentRuntimeEntry>> groups = new LinkedHashMap<>();
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(owner.getId())) {
            Character bot = entry.bot();
            CompanionSettings own = bot == null ? null : boundSettings(bound.get(bot.getId()), owner.getId());
            if (own != null && own.formation() != null) {
                groups.computeIfAbsent(own.formation(), token -> new ArrayList<>()).add(entry);
            }
        }
        groups.forEach((token, entries) -> AgentFormationCommandService.handleFormationCommand(owner,
                CompanionManager.FORMATION + token, new AgentFormationCommandService.Hooks(
                        leaderId -> entries,
                        (leaderId, fallback) -> AgentFormationService.stateForLeader(
                                AgentFormationService.formationsByLeaderId(), leaderId, fallback),
                        (leaderId, formation) -> { }, // the crew's own formation stays as it is
                        AgentFormationService::applyOffsets,
                        (entry, message) -> { },
                        (leader, message) -> { },
                        AgentFormationRuntime.defaultFormationState(), AgentRuntimeConfig.cfg.FOLLOW_STAGGER,
                        AgentMovementPhysicsConfig.configuredFollowYCap())));
    }

    // ---- the seams (any thread, memory only) --------------------------------------------------

    @Override
    public AgentPotionThresholds.Thresholds thresholds(int agentCharacterId) {
        CompanionSettings own = outWithOwner(agentCharacterId, bound.get(agentCharacterId));
        return own == null || own.potionsAsConfigured() ? null
                : new AgentPotionThresholds.Thresholds(own.hpPercent() / 100f, own.mpPercent() / 100f);
    }

    @Override
    public AgentLootPolicy.Policy policy(int agentCharacterId) {
        Bound at = bound.get(agentCharacterId);
        CompanionSettings own = outWithOwner(agentCharacterId, at);
        if (own == null || own.loot() == AgentLootPolicy.Mode.ALL && !own.leaveBossDrops()) {
            return null;
        }
        int ownerId = at.ownerId();
        return new AgentLootPolicy.Policy(own.loot(), own.leaveBossDrops(), itemId -> questNeeds(ownerId, itemId));
    }

    /**
     * True when a quest the owner has started still needs more of the item to complete (its Quest.wz completion
     * requirement), counted in the owner's own bag. Memory only: the owner's quest snapshot and bag.
     */
    boolean questNeeds(int ownerId, int itemId) {
        Character owner = itemId <= 0 ? null : roster.onlineCharacter(ownerId);
        if (owner == null) {
            return false;
        }
        for (QuestStatus status : owner.getStartedQuests()) {
            Quest quest = status.getQuest();
            int needed = quest == null ? Integer.MAX_VALUE : quest.getCompleteItemAmountNeeded(itemId);
            if (needed > 0 && needed != Integer.MAX_VALUE && owner.getItemQuantity(itemId, false) < needed) {
                return true;
            }
        }
        return false;
    }

    /** The settings {@code at} binds the companion character to, while its roster owner leads it; else null. */
    private CompanionSettings outWithOwner(int agentCharacterId, Bound at) {
        return at == null || AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId) != at.ownerId() ? null
                : boundSettings(at, at.ownerId());
    }

    /** The settings {@code at} binds, when it is one of {@code ownerId}'s companions; never read from the store here. */
    private CompanionSettings boundSettings(Bound at, int ownerId) {
        return at == null || at.ownerId() != ownerId ? null : settings.get(key(at.ownerId(), at.personaId()));
    }

    private static String key(int ownerId, String personaId) {
        return ownerId + ":" + personaId;
    }
}
