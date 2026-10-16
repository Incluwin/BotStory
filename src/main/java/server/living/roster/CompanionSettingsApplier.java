package server.living.roster;

import client.Character;
import client.QuestStatus;
import server.agents.capabilities.build.AgentBookSpeech;
import server.agents.capabilities.combat.AgentBuffStateRuntime;
import server.agents.capabilities.combat.AgentCombatBuffStateRuntime;
import server.agents.capabilities.dialogue.AgentChatToggleHook;
import server.agents.capabilities.inventory.AgentBagFullSpeech;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.capabilities.movement.AgentFormationCommandService;
import server.agents.capabilities.movement.AgentFormationHook;
import server.agents.capabilities.movement.AgentFormationRuntime;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentMovementPhysicsConfig;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentPotionThresholds;
import server.agents.capabilities.supplies.AgentWalletReserve;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;
import server.quest.Quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Companion settings on the Agents (docs/BOT_MANAGEMENT_PLAN.md P3b, "CompanionSettingsApplier").
 *
 * <p>It answers the Agent layer's owner-free seams from memory, on whatever thread asks: the potion percentages
 * (AgentPotionThresholds) of a loaded companion character, wherever it is (out with its owner, on a live bench slice,
 * finishing a run for a friend), and the loot policy (AgentLootPolicy) of a companion out with its owner, that is one
 * whose runtime leader is its roster owner (a slice farms for the haul, and a friend's run keeps its own rules). A
 * Resident and every other Agent get no answer, so they drink and loot as configured. A companion whose potion
 * percentages its owner never set gets no potion answer either, and one that loots everything, boss drops included,
 * no loot answer; the defaults leave a boss's drops to the humans (the plan's NO_BOSS default).</p>
 *
 * <p>On companion-ops it keeps each companion's {@link CompanionSettings}, read from settings_json on first use and
 * written back on every change, and applies them to a loaded companion on every path that loads or seats it (a call
 * and its arrival, a swap, a login restore and so the boot's, channel follow and its fallback, a rejoin): it binds the
 * character to its owner's settings, sets its support toggles quietly on its runtime, rebuilds its potion alerts, puts
 * the crew's formation back when the Agent layer lost it (a relog, a restart) and puts each companion that chose a
 * formation of its own in its place. So the settings survive bench, relog and restart. What the owner orders in chat
 * is kept the same way: a support toggle said to a companion out with them, and a formation order to their crew (kept
 * per owner in living_crew; everyone it placed keeps to the crew's formation from then on).</p>
 */
public final class CompanionSettingsApplier implements AgentPotionThresholds.Rule, AgentLootPolicy.Rule, AgentWalletReserve.Rule,
        AgentChatToggleHook.Rule, AgentFormationHook.Rule, AgentBagFullSpeech.Rule, AgentBookSpeech.Rule {
    /** How long what an owner's quests need is kept before their started quests are read again (QUEST_ONLY). */
    private static final long QUEST_NEEDS_TTL_MS = 1_000L;

    /** Whose settings a loaded companion character runs on. */
    record Bound(int ownerId, String personaId) { }

    /** What an owner's started quests need to complete: item id -> the most any one of them needs, read at a time. */
    private record Needs(long readAtMs, Map<Integer, Integer> items) { }

    private final LivingWorld world;
    private final RosterService roster;
    /** owner:persona -> the companion's settings, read from settings_json on first use. */
    private final Map<String, CompanionSettings> settings = new ConcurrentHashMap<>();
    /** Companion character id -> whose settings it runs on; bound at every load or seat. */
    private final Map<Integer, Bound> bound = new ConcurrentHashMap<>();
    /** Owner id -> the formation they set for their whole crew (empty: none), read from living_crew on first use. */
    private final Map<Integer, Optional<AgentFormationService.FormationState>> crews = new ConcurrentHashMap<>();
    /** Owner id -> what their started quests need, kept for {@link #QUEST_NEEDS_TTL_MS}. */
    private final Map<Integer, Needs> needs = new ConcurrentHashMap<>();

    CompanionSettingsApplier(LivingWorld world, RosterService roster) {
        this.world = world;
        this.roster = roster;
    }

    /** Installs the potion, loot, chat toggle, formation and bag-full seams, book lines too (LivingWorld.start). */
    public void install() {
        AgentPotionThresholds.install(this);
        AgentLootPolicy.install(this);
        AgentWalletReserve.install(this);
        AgentChatToggleHook.install(this);
        AgentFormationHook.install(this);
        AgentBagFullSpeech.install(this);
        AgentBookSpeech.install(this);
    }

    /** Takes the seams out again (LivingWorld.stop): every Agent drinks and loots as configured. */
    public static void uninstall() {
        AgentPotionThresholds.install(null);
        AgentLootPolicy.install(null);
        AgentWalletReserve.install(null);
        AgentChatToggleHook.install(null);
        AgentFormationHook.install(null);
        AgentBagFullSpeech.install(null);
        AgentBookSpeech.install(null);
    }

    /**
     * The companion's settings: from memory, else read from its settings_json once (defaults when it has none). While
     * its settings_json cannot be read, the defaults, kept nowhere: the next ask reads it again.
     */
    public CompanionSettings settings(int ownerId, String personaId) {
        CompanionSettings known = stored(ownerId, personaId);
        return known != null ? known : CompanionSettings.defaults();
    }

    /**
     * Changes one companion's settings, on companion-ops: in memory (the seams answer from them at once) and in its
     * settings_json, logged as companion.settings; its card's screens go stale. False, with nothing changed, while its
     * settings cannot be read or written.
     */
    boolean change(int ownerId, String personaId, UnaryOperator<CompanionSettings> change) {
        CompanionSettings now = stored(ownerId, personaId);
        if (now == null) {
            return false;
        }
        CompanionSettings next = change.apply(now);
        if (!world.store().saveSettings(ownerId, personaId, next.toJson())) {
            return false;
        }
        settings.put(key(ownerId, personaId), next);
        CompanionViews.invalidate(world, ownerId, personaId);
        world.store().event(ownerId, personaId, "companion.settings", next.toJson());
        return true;
    }

    /** A companion let go (RosterService.letGo): its settings and whose character ran on them go with its row. */
    void forget(int ownerId, String personaId) {
        settings.remove(key(ownerId, personaId));
        bound.values().removeIf(at -> at.ownerId() == ownerId && at.personaId().equals(personaId));
    }

    /**
     * Every path that loads or seats a companion calls this once it is loaded for its owner: the character runs on
     * the owner's settings for it from now, its support toggles are set on its runtime (only when they differ from the
     * Agent's defaults: a fresh runtime has those already, and one an arrival or rejoin keeps has what chat set, which
     * the settings keep too), its potion alerts are rebuilt from its own percentages, the crew's formation is put back
     * when the Agent layer has none for the owner (after a relog or a restart), and the owner's companions who chose a
     * formation of their own take their places again (a load spreads the whole crew by the crew's formation). On
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
        if (runtime != null && !own.potionsAsConfigured()) {
            refreshPotions(runtime); // its mode start may have set the configured alerts before it was bound
        }
        restoreCrewFormation(owner);
        applyPlaces(owner);
    }

    /**
     * A companion's character loaded for its owner without a seat (a live bench slice, BenchDutyDispatcher): it drinks
     * by its own potion percentages there too. Nothing else: a slice leads itself and farms for the haul, so its loot
     * policy and support stay the Agent's. On companion-ops.
     */
    public void bind(Character bot, int ownerId, String personaId) {
        CompanionSettings own = stored(ownerId, personaId);
        bound.put(bot.getId(), new Bound(ownerId, personaId));
        if (own != null && !own.potionsAsConfigured()) {
            refreshPotions(AgentRuntimeRegistry.findByAgentCharacterId(bot.getId()));
        }
    }

    /**
     * A changed support toggle on the companion's runtime, quietly (its card's page is the answer), when it is loaded
     * and led by its owner (out with them, on its way, waiting outside a run): true when it was set. Otherwise it
     * takes effect at its next load. On companion-ops.
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

    /** Changed potion percentages: a loaded companion's potion alerts are rebuilt at once. On companion-ops. */
    void refreshPotions(Persona persona) {
        Character bot = roster.loaded(persona.id());
        refreshPotions(bot == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(bot.getId()));
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

    /** The potion alerts rebuilt on the Agent's own mailbox, through the potion seam (AgentPotionService). */
    private static void refreshPotions(AgentRuntimeEntry runtime) {
        if (runtime == null) {
            return;
        }
        AgentMailboxRuntime.dispatch(runtime, entry -> {
            Character agent = AgentRuntimeIdentityRuntime.bot(entry);
            if (agent != null) {
                AgentPotionService.setupAutopotForBot(agent);
            }
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

    // ---- the crew's formation -----------------------------------------------------------------

    /** The formation the owner set for their whole crew: from memory, else living_crew; null for none or unreadable. */
    AgentFormationService.FormationState crewFormation(int ownerId) {
        Optional<AgentFormationService.FormationState> known = crews.get(ownerId);
        if (known != null) {
            return known.orElse(null);
        }
        try {
            AgentFormationService.FormationState read = decode(world.store().crewFormation(ownerId));
            crews.putIfAbsent(ownerId, Optional.ofNullable(read));
            return read;
        } catch (LivingStore.ReadFailed unreadable) {
            return null; // logged by the store; read again next time
        }
    }

    /**
     * The owner set their crew's formation (the Crew window's Formation... page, or a formation order in chat): it is
     * kept for them, and unless only its snap range changed it placed everyone of theirs out with them, so each of
     * them keeps to the crew's formation from now on instead of a place of its own (and is spread by it again, in case
     * the order placed nobody). On companion-ops.
     */
    void keepCrewFormation(Character owner, AgentFormationService.FormationState formation) {
        AgentFormationService.FormationState before = crewFormation(owner.getId());
        if (before == null) {
            before = AgentFormationRuntime.defaultFormationState();
        }
        crews.put(owner.getId(), Optional.of(formation));
        world.store().saveCrewFormation(owner.getId(), encode(formation));
        if (formation.type() == before.type() && formation.px() == before.px()
                && formation.snapRange() != before.snapRange()) {
            return; // its snap range: nobody moved
        }
        List<AgentRuntimeEntry> crew = AgentRuntimeRegistry.entriesForLeader(owner.getId());
        boolean cleared = false;
        for (AgentRuntimeEntry entry : crew) {
            Character bot = entry.bot();
            Bound at = bot == null ? null : bound.get(bot.getId());
            CompanionSettings own = boundSettings(at, owner.getId());
            if (own != null && own.formation() != null) {
                cleared |= change(owner.getId(), at.personaId(), kept -> kept.withFormation(null));
            }
        }
        if (cleared) {
            AgentFormationService.applyOffsets(crew, formation);
        }
    }

    /** Puts the crew's formation back for the owner when the Agent layer has none (after a relog or a restart). */
    private void restoreCrewFormation(Character owner) {
        if (AgentFormationService.formationsByLeaderId().containsKey(owner.getId())) {
            return;
        }
        AgentFormationService.FormationState kept = crewFormation(owner.getId());
        if (kept != null) {
            AgentFormationRuntime.setFormationState(owner, kept.type(), kept.px(), kept.snapRange(),
                    AgentRuntimeRegistry.entriesForLeader(owner.getId()));
        }
    }

    /** "SPREAD 80 0": the formation as living_crew keeps it. */
    static String encode(AgentFormationService.FormationState formation) {
        return formation.type().name() + " " + formation.px() + " " + formation.snapRange();
    }

    /** The formation living_crew keeps, or null for none or a value it cannot read. */
    static AgentFormationService.FormationState decode(String kept) {
        String[] parts = kept == null ? new String[0] : kept.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new AgentFormationService.FormationState(
                    AgentFormationService.FormationType.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    // ---- what the owner orders in chat (any thread: the Agent's own) ------------------------------------

    /**
     * Chat turned a support toggle of a companion on or off: kept as its setting, on companion-ops, when its owner
     * leads it (their own order to it; a friend leading it through a run changes nothing of the owner's).
     */
    @Override
    public void toggled(int agentCharacterId, AgentChatToggleHook.Toggle toggle, boolean enabled) {
        Bound at = bound.get(agentCharacterId);
        if (at == null) {
            return;
        }
        CompanionSettings.Support support = CompanionSettings.Support.of(toggle);
        world.executor().submit("companion.settings.chat", () -> {
            if (!at.equals(bound.get(agentCharacterId))
                    || AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId) != at.ownerId()) {
                return;
            }
            CompanionSettings own = stored(at.ownerId(), at.personaId());
            if (own != null && own.support(support) != enabled) {
                change(at.ownerId(), at.personaId(), kept -> kept.withSupport(support, enabled));
            }
        });
    }

    /** A leader set their Agents' formation in chat: kept, on companion-ops, when the leader owns companions. */
    @Override
    public void formationSet(Character leader, AgentFormationService.FormationState formation) {
        if (leader == null || formation == null || !roster.hasCompanions(leader)) {
            return;
        }
        world.executor().submit("companion.crew.formation", () -> keepCrewFormation(leader, formation));
    }

    // ---- the seams (any thread, memory only) --------------------------------------------------

    /** Whose companion this loaded character is (bound at its load or seat), from memory; null when none. */
    Bound boundTo(int agentCharacterId) {
        return bound.get(agentCharacterId);
    }

    @Override
    public AgentPotionThresholds.Thresholds thresholds(int agentCharacterId) {
        Bound at = bound.get(agentCharacterId);
        CompanionSettings own = at == null ? null : boundSettings(at, at.ownerId());
        return own == null || own.potionsAsConfigured() ? null
                : new AgentPotionThresholds.Thresholds(own.hpPercent() / 100f, own.mpPercent() / 100f);
    }

    /**
     * A companion out with its owner keeps no journey reserve: its recall and its follow cost it nothing, so it may
     * spend its whole purse on supplies (the reserve left a purse of 1-2k buying nothing on every shop visit). A
     * companion on a live bench slice leads itself and keeps the reserve like any Agent.
     */
    @Override
    public boolean exempt(int agentCharacterId) {
        Bound at = bound.get(agentCharacterId);
        return at != null && AgentRuntimeRegistry.leaderIdForAgentCharacter(agentCharacterId) == at.ownerId();
    }

    @Override
    public AgentLootPolicy.Policy policy(int agentCharacterId) {
        Bound at = bound.get(agentCharacterId);
        CompanionSettings own = outWithOwner(agentCharacterId, at);
        if (own == null || own.loot() == AgentLootPolicy.Mode.ALL && !own.leaveBossDrops()) {
            return null;
        }
        int ownerId = at.ownerId();
        return new AgentLootPolicy.Policy(own.loot(), own.leaveBossDrops(),
                (agent, itemId) -> questNeeds(ownerId, agent, itemId));
    }

    /**
     * Bag-full lines (AgentBagFullSpeech: a gift trade refused for space, a drop left for a full bag): only a companion
     * out with its owner (its runtime leader is its roster owner) and in its owner's party says them. A bench slice,
     * a Resident, a citizen and every other Agent keep quiet. Memory only.
     */
    @Override
    public boolean speaksOfFullBag(Character agent) {
        Bound at = agent == null ? null : bound.get(agent.getId());
        if (at == null || AgentRuntimeRegistry.leaderIdForAgentCharacter(agent.getId()) != at.ownerId()) {
            return false;
        }
        var party = agent.getParty();
        return party != null && party.getMemberById(at.ownerId()) != null;
    }

    /** Book lines (AgentBookSpeech: a failed book, one that waits on an earlier book): the bag-full companions. */
    @Override
    public boolean speaksOfBooks(Character agent) {
        return speaksOfFullBag(agent);
    }

    /**
     * NO_BOSS inside a boss run (P7): true while the companion is committed to a boss run ("boss:"), so inside its
     * event instance it leaves a boss's drops to the humans when its settings say so. A party quest's run ("pq:")
     * keeps its own loot rules (KPQ's pass from King Slime). Memory only.
     */
    @Override
    public boolean leavesBossDropsInRun(int agentCharacterId) {
        Commitments commitments = world.commitments();
        String key = commitments == null ? null : commitments.keyOf(agentCharacterId);
        return key != null && key.startsWith(CompanionBossRuns.KEY_PREFIX);
    }

    /**
     * True when a quest the owner has started still needs more of the item to complete (its Quest.wz completion
     * requirement) than the owner and the companion hold between them. Memory only: what the owner's started quests
     * need (read again at most once a second) and both bags.
     */
    boolean questNeeds(int ownerId, Character companion, int itemId) {
        Character owner = itemId <= 0 ? null : roster.onlineCharacter(ownerId);
        if (owner == null) {
            return false;
        }
        int needed = questNeeds(owner).getOrDefault(itemId, 0);
        if (needed <= 0) {
            return false;
        }
        long held = (long) owner.getItemQuantity(itemId, false)
                + (companion == null ? 0 : companion.getItemQuantity(itemId, false));
        return held < needed;
    }

    /** What the owner's started quests need: item id -> the most any one of them needs, read at most once a second. */
    private Map<Integer, Integer> questNeeds(Character owner) {
        long now = System.currentTimeMillis();
        Needs known = needs.get(owner.getId());
        if (known != null && now - known.readAtMs() < QUEST_NEEDS_TTL_MS) {
            return known.items();
        }
        Map<Integer, Integer> items = new HashMap<>();
        for (QuestStatus status : owner.getStartedQuests()) {
            Quest quest = status.getQuest();
            if (quest != null) {
                quest.getCompleteItemRequirements().forEach((itemId, count) -> {
                    if (count > 0) {
                        items.merge(itemId, count, Math::max);
                    }
                });
            }
        }
        Map<Integer, Integer> read = Map.copyOf(items);
        needs.put(owner.getId(), new Needs(now, read));
        return read;
    }

    /** From memory, else read from settings_json and kept; null when it cannot be read now (nothing is kept then). */
    private CompanionSettings stored(int ownerId, String personaId) {
        String key = key(ownerId, personaId);
        CompanionSettings known = settings.get(key);
        if (known != null) {
            return known;
        }
        String json;
        try {
            json = world.store().settings(ownerId, personaId);
        } catch (LivingStore.ReadFailed unreadable) {
            return null; // logged by the store; read again next time
        }
        CompanionSettings read = CompanionSettings.parse(json);
        CompanionSettings raced = settings.putIfAbsent(key, read);
        return raced != null ? raced : read;
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
