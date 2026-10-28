package server.living.quest;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.integration.AgentPresence;
import server.knowledge.ContentChat;
import server.living.LivingWorld;
import server.living.config.Content.GearReward;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Requirements;
import server.living.config.Content.Reward;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Players' progress on Persona quests. Deterministic: every step advances on a server event
 * (kill, pickup, map, talk, rumor) and rewards go through the normal character APIs.
 * Rule (plan 5.3): a Persona's own quests only accept its contribution while it is co-present
 * with the owner; anything it did from the bench or another map is ignored.
 */
public final class QuestLedger {
    private static final Logger log = LoggerFactory.getLogger(QuestLedger.class);
    private static final long PROVENANCE_WINDOW_MS = 24L * 3_600_000L;
    private static final long OFFER_TTL_MS = 10L * 60_000L;

    private record Offer(String questId, long atMs) { }

    private final LivingWorld world;
    private final Map<String, Offer> offers = new ConcurrentHashMap<>();

    public QuestLedger(LivingWorld world) {
        this.world = world;
    }

    // ---- availability --------------------------------------------------------------------

    /** Quests the persona offers the player now; a hired citizen ({@code hire:} id) offers none. */
    public List<Quest> available(Character chr, Persona persona) {
        List<Quest> out = new ArrayList<>();
        if (LivingContent.isHire(persona.id())) {
            return out;
        }
        Set<String> worldFlags = world.store().worldFlags();
        for (String questId : persona.questLines()) {
            Quest quest = world.content().quest(questId);
            if (quest != null && eligible(chr, persona, quest, worldFlags)) {
                out.add(quest);
            }
        }
        return out;
    }

    private boolean eligible(Character chr, Persona persona, Quest quest, Set<String> worldFlags) {
        if (!world.arcs().questUnlocked(persona, quest.id())) {
            return false;
        }
        QuestState state = world.store().questState(chr.getId(), quest.id());
        if (state != null) {
            if (state.status() == QuestStatus.ACTIVE) {
                return false;
            }
            if (!quest.repeatable()) {
                return false;
            }
        }
        Requirements requires = quest.requires();
        if (chr.getLevel() < requires.minLevel() || chr.getLevel() > requires.maxLevel()) {
            return false;
        }
        Bond bond = world.bonds().peek(chr.getId(), persona.id());
        if (!(bond == null ? server.living.config.Content.BondStage.NONE : bond.stage()).atLeast(requires.minBondStage())
                && requires.minBondStage() != server.living.config.Content.BondStage.NONE) {
            return false;
        }
        if (requires.minFriendship() > 0 && world.friendship().level(chr.getId(), persona.id()) < requires.minFriendship()) {
            return false;
        }
        for (GearReward gear : quest.rewards().gear()) {
            if (gearFor(chr, gear) == 0) {
                return false; // nothing in the pool for this player's job yet (a beginner): offered once there is
            }
        }
        for (String flag : requires.flags()) {
            if (!world.bonds().hasFlag(chr.getId(), persona.id(), flag, worldFlags)) {
                return false;
            }
        }
        for (String flag : requires.notFlags()) {
            if (world.bonds().hasFlag(chr.getId(), persona.id(), flag, worldFlags)) {
                return false;
            }
        }
        for (String completed : requires.questsCompleted()) {
            QuestState other = world.store().questState(chr.getId(), completed);
            if (other == null || other.status() != QuestStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public List<QuestState> active(Character chr) {
        return world.store().questStates(chr.getId(), QuestStatus.ACTIVE);
    }

    public List<QuestState> activeFrom(Character chr, Persona persona) {
        List<QuestState> out = new ArrayList<>();
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            if (quest != null && quest.persona().equals(persona.id())) {
                out.add(state);
            }
        }
        return out;
    }

    // ---- talking to the giver ------------------------------------------------------------

    /** The player asked for work: state the first available quest and remember the offer. */
    public boolean offer(Character chr, Persona persona) {
        List<Quest> quests = available(chr, persona);
        if (quests.isEmpty()) {
            return false;
        }
        offer(chr, persona, quests.get(0));
        return true;
    }

    /** Pitches one quest the way an NPC does: the ask, then the client's Accept/Decline window. */
    public void offer(Character chr, Persona persona, Quest quest) {
        offers.put(key(chr, persona), new Offer(quest.id(), System.currentTimeMillis()));
        List<String> lines = world.arcs().lines(persona, "questOffer." + quest.id());
        if (lines.isEmpty() && !quest.steps().isEmpty()) {
            lines = quest.steps().get(0).say();
        }
        String pitch = lines.isEmpty() ? "I could use a hand with something." : pick(lines, chr);
        StringBuilder details = new StringBuilder("#e" + quest.title() + "#n (" + quest.length().name().toLowerCase() + ")\r\n");
        int shown = 0;
        for (Step step : quest.steps()) {
            if (step.type() == StepType.TALK) {
                continue;
            }
            details.append("\r\n#b- ").append(describe(step, new QuestState(chr.getId(), quest.id(), 0, 0, QuestStatus.ACTIVE, 0L, 0L, 0)))
                    .append("#k");
            if (++shown >= 5) {
                break;
            }
        }
        List<String> gear = new ArrayList<>();
        for (GearReward piece : quest.rewards().gear()) {
            int itemId = gearFor(chr, piece);
            if (itemId != 0) {
                gear.add(gearLine(persona, piece, forgePlan(chr, quest, piece, itemId)));
            }
        }
        details.append("\r\n\r\n").append(rewardSummary(quest.rewards(), gear));
        LivingTalk.open(chr, ResidentDialogue.portraitOf(persona))
                .say(pitch)
                .acceptDecline(details.toString(), () -> accept(chr, persona), () -> {
                    offers.remove(key(chr, persona));
                    List<String> declined = world.arcs().lines(persona, "declined");
                    world.residents().reply(persona, chr, declined.isEmpty() ? "suit yourself." : pick(declined, chr));
                })
                .show();
        chr.dropMessage(5, "[" + persona.name() + "] offers: " + quest.title() + ". Say 'accept' to take it.");
    }

    /** The reward block in the client's own quest-window style (Quest scripts use the same icons). */
    private String rewardSummary(Reward reward, List<String> gear) {
        StringBuilder out = new StringBuilder("#fUI/UIWindow.img/QuestIcon/4/0#\r\n");
        for (String piece : gear) {
            out.append("\r\n").append(piece);
        }
        for (ItemStack stack : reward.items()) {
            out.append("\r\n#v").append(stack.itemId()).append("# ").append(stack.count()).append(" #t").append(stack.itemId()).append('#');
        }
        if (reward.meso() > 0) {
            out.append("\r\n#fUI/UIWindow.img/QuestIcon/7/0# ").append(reward.meso()).append(" meso");
        }
        if (reward.exp() > 0) {
            out.append("\r\n#fUI/UIWindow.img/QuestIcon/8/0# ").append(reward.exp()).append(" exp");
        }
        if (reward.fame() != 0) {
            out.append("\r\n#fUI/UIWindow.img/QuestIcon/6/0# ").append(reward.fame()).append(" fame");
        }
        if (gear.isEmpty() && reward.items().isEmpty() && reward.meso() <= 0 && reward.exp() <= 0 && reward.fame() == 0) {
            out.append("\r\ntheir gratitude");
        }
        return out.toString();
    }

    public Quest pendingOffer(Character chr, Persona persona) {
        Offer offer = offers.get(key(chr, persona));
        if (offer == null || System.currentTimeMillis() - offer.atMs() > OFFER_TTL_MS) {
            return null;
        }
        return world.content().quest(offer.questId());
    }

    /** The player accepted the last offer (or the only available quest). */
    public boolean accept(Character chr, Persona persona) {
        Quest quest = pendingOffer(chr, persona);
        if (quest == null) {
            List<Quest> quests = available(chr, persona);
            if (quests.size() != 1) {
                return false;
            }
            quest = quests.get(0);
        }
        offers.remove(key(chr, persona));
        long now = System.currentTimeMillis();
        QuestState previous = world.store().questState(chr.getId(), quest.id());
        QuestState state = new QuestState(chr.getId(), quest.id(), 0, 0, QuestStatus.ACTIVE, now, 0L,
                previous == null ? 0 : previous.repeats());
        world.store().saveQuestState(state);
        world.markers().forget(chr);
        world.questLog().started(chr, quest);
        world.store().event(chr.getId(), persona.id(), "quest.start", quest.id());
        List<String> lines = world.arcs().lines(persona, "questAccept." + quest.id());
        if (!lines.isEmpty()) {
            world.residents().reply(persona, chr, pick(lines, chr));
        }
        chr.dropMessage(5, "Quest started: " + quest.title() + ".");
        // a TALK first step is the conversation that just happened
        Step first = quest.step(0);
        if (first != null && first.type() == StepType.TALK) {
            advance(chr, persona, quest, state);
        } else {
            describeStep(chr, quest, state);
        }
        return true;
    }

    /** The player says they are done / hands something over to this persona. */
    public boolean turnIn(Character chr, Persona persona) {
        boolean any = false;
        for (QuestState state : activeFrom(chr, persona)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest.step(state.stepIndex());
            if (step == null) {
                continue;
            }
            if (state.stepIndex() == quest.steps().size() - 1 && !roomForGear(chr, quest)) {
                List<String> lines = world.arcs().lines(persona, "questRoom." + quest.id());
                world.residents().reply(persona, chr, lines.isEmpty() ? "make some room in your equip bag first." : pick(lines, chr));
                any = true;
                continue;
            }
            switch (step.type()) {
                case TALK -> {
                    advance(chr, persona, quest, state);
                    any = true;
                }
                case DELIVER -> {
                    if (step.to().equals(persona.id()) && deliver(chr, persona, quest, state, step)) {
                        any = true;
                    }
                }
                case REACH_LEVEL -> {
                    if (chr.getLevel() >= step.level()) {
                        advance(chr, persona, quest, state);
                        any = true;
                    }
                }
                case WAIT -> {
                    if (System.currentTimeMillis() - state.startedAtMs() >= step.hours() * 3_600_000L) {
                        advance(chr, persona, quest, state);
                        any = true;
                    }
                }
                case PAY -> {
                    if (chr.getMeso() >= step.meso()) {
                        try (var ledgerScope = MesoFlows.as(MesoAccount.QUEST)) { chr.gainMeso(-step.meso(), true); }
                        advance(chr, persona, quest, state);
                        any = true;
                    } else {
                        world.residents().reply(persona, chr, "that's " + step.meso() + " meso. you don't have it.");
                        any = true;
                    }
                }
                case GIVE_ITEM -> {
                    if (removeFromPlayer(chr, step.itemId(), step.count())) {
                        advance(chr, persona, quest, state);
                        any = true;
                    }
                }
                default -> { }
            }
        }
        // talking to a persona also satisfies TALK_TO steps of other personas' quests
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step != null && step.type() == StepType.TALK_TO && persona.id().equals(step.persona())) {
                if (!step.say().isEmpty()) {
                    world.residents().reply(persona, chr, pick(step.say(), chr));
                }
                advance(chr, world.content().persona(quest.persona()), quest, state);
                any = true;
            }
        }
        return any;
    }

    /** True when {@link #turnIn} would move a quest forward right now (no side effects). */
    public boolean readyToHandIn(Character chr, Persona persona) {
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step == null) {
                continue;
            }
            if (step.type() == StepType.TALK_TO && persona.id().equals(step.persona())) {
                return true;
            }
            if (!quest.persona().equals(persona.id())) {
                continue;
            }
            boolean ready = switch (step.type()) {
                case TALK -> true;
                case DELIVER -> step.to().equals(persona.id())
                        && (step.take() == null || countEligible(chr, quest, step.take().itemId()) >= step.take().count());
                case REACH_LEVEL -> chr.getLevel() >= step.level();
                case WAIT -> System.currentTimeMillis() - state.startedAtMs() >= step.hours() * 3_600_000L;
                case PAY -> chr.getMeso() >= step.meso();
                case GIVE_ITEM -> chr.getInventory(ItemConstants.getInventoryType(step.itemId())).countById(step.itemId()) >= step.count();
                default -> false;
            };
            if (ready) {
                return true;
            }
        }
        return false;
    }

    private boolean deliver(Character chr, Persona persona, Quest quest, QuestState state, Step step) {
        ItemStack take = step.take();
        if (take == null) {
            advance(chr, persona, quest, state);
            return true;
        }
        int have = countEligible(chr, quest, take.itemId());
        if (have < take.count()) {
            List<String> lines = world.arcs().lines(persona, "questWaiting." + quest.id());
            world.residents().reply(persona, chr, lines.isEmpty()
                    ? "you don't have what i asked for yet (" + have + "/" + take.count() + ")" : pick(lines, chr));
            return true;
        }
        if (!removeFromPlayer(chr, take.itemId(), take.count())) {
            return false;
        }
        if (!step.say().isEmpty()) {
            world.residents().reply(persona, chr, pick(step.say(), chr));
        }
        advance(chr, persona, quest, state);
        return true;
    }

    // ---- server events -------------------------------------------------------------------

    public void onMobKilled(int mobId, Set<Integer> attackerIds, MapleMap map) {
        for (int attackerId : attackerIds) {
            Character attacker = map.getCharacterById(attackerId);
            if (attacker == null) {
                continue;
            }
            if (AgentPresence.isAgent(attacker)) {
                Persona companion = world.residents().personaOf(attacker);
                int ownerId = companion == null ? 0 : world.roster().ownerOf(companion.id());
                Character owner = ownerId == 0 ? null : map.getCharacterById(ownerId);
                boolean coPresent = owner != null && sameParty(owner, attacker);
                if (companion != null) {
                    creditKill(owner == null ? onlineOwner(ownerId) : owner, mobId, map.getId(), companion.id(), coPresent);
                }
            } else {
                creditKill(attacker, mobId, map.getId(), null, true);
            }
        }
        rollCustomDrops(mobId, attackerIds, map);
    }

    private void creditKill(Character player, int mobId, int mapId, String byPersona, boolean coPresent) {
        if (player == null) {
            return;
        }
        for (QuestState state : active(player)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step == null || !step.mobIds().contains(mobId)) {
                continue;
            }
            if (step.type() != StepType.KILL_MOBS && step.type() != StepType.HUNT_WITH && step.type() != StepType.KILL_BOSS) {
                continue;
            }
            if (!step.mapIds().isEmpty() && !step.mapIds().contains(mapId)) {
                continue;
            }
            if (byPersona != null && byPersona.equals(quest.persona()) && !coPresent) {
                continue; // the giver worked its own quest from afar
            }
            String required = step.type() == StepType.HUNT_WITH ? step.persona() : step.with();
            if (required != null && !world.roster().coPresent(player, required)) {
                continue;
            }
            Persona giver = world.content().persona(quest.persona());
            progress(player, giver, quest, state, 1);
        }
    }

    public void onItemGained(Character chr, int itemId) {
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step == null || step.type() != StepType.COLLECT_ITEM || step.itemId() != itemId) {
                continue;
            }
            int have = Math.min(step.count(), countEligible(chr, quest, itemId));
            if (have != state.progress()) {
                QuestState updated = new QuestState(chr.getId(), quest.id(), state.stepIndex(), have, state.status(),
                        state.startedAtMs(), state.completedAtMs(), state.repeats());
                world.store().saveQuestState(updated);
                chr.dropMessage(5, quest.title() + ": " + (step.label() != null ? step.label() : itemName(itemId)) + " " + have + "/" + step.count());
                if (have >= step.count()) {
                    advance(chr, world.content().persona(quest.persona()), quest, updated);
                }
            }
        }
    }

    public void onMapEntered(Character chr, int mapId) {
        if (AgentPresence.isAgent(chr)) {
            return;
        }
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step == null) {
                continue;
            }
            if (step.type() == StepType.VISIT_MAP && step.mapIds().contains(mapId)) {
                advance(chr, world.content().persona(quest.persona()), quest, state);
            } else if (step.type() == StepType.REACH_LEVEL && chr.getLevel() >= step.level()) {
                advance(chr, world.content().persona(quest.persona()), quest, state);
            }
        }
    }

    /** True when one of the player's active quests is on a HEAR_RUMOR step for this rumor. */
    public boolean waitingOnRumor(Character chr, String rumorId) {
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step != null && step.type() == StepType.HEAR_RUMOR && rumorId.equals(step.rumorId())) {
                return true;
            }
        }
        return false;
    }

    public void onRumorHeard(Character chr, String rumorId) {
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step != null && step.type() == StepType.HEAR_RUMOR && rumorId.equals(step.rumorId())) {
                advance(chr, world.content().persona(quest.persona()), quest, state);
            }
        }
    }

    /**
     * Party quest instance {@code partyQuest} of event script {@code script} (null when unknown) was cleared. A
     * PARTY_QUEST_CLEAR step with no {@code to} takes any clear; {@code to} names the instance or the script, so "CWKPQ"
     * takes every channel's instance (CWKPQ1, CWKPQ2, ...).
     */
    public void onPartyQuestCleared(Character chr, String partyQuest, String script) {
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step != null && step.type() == StepType.PARTY_QUEST_CLEAR
                    && (step.to() == null || step.to().equalsIgnoreCase(partyQuest) || step.to().equalsIgnoreCase(script))) {
                advance(chr, world.content().persona(quest.persona()), quest, state);
            }
        }
    }

    private void rollCustomDrops(int mobId, Set<Integer> attackerIds, MapleMap map) {
        for (int attackerId : attackerIds) {
            Character chr = map.getCharacterById(attackerId);
            if (chr == null || AgentPresence.isAgent(chr)) {
                continue;
            }
            for (QuestState state : active(chr)) {
                Quest quest = world.content().quest(state.questId());
                Step step = quest == null ? null : quest.step(state.stepIndex());
                if (step == null || step.type() != StepType.COLLECT_ITEM || step.dropFrom() == null
                        || !step.dropFrom().mobIds().contains(mobId)) {
                    continue;
                }
                if (countEligible(chr, quest, step.itemId()) >= step.count()) {
                    continue;
                }
                if (ThreadLocalRandom.current().nextDouble() < step.dropFrom().chance()
                        && InventoryManipulator.checkSpace(chr.getClient(), step.itemId(), 1, "")) {
                    InventoryManipulator.addById(chr.getClient(), step.itemId(), (short) 1);
                    chr.dropMessage(5, "You found " + (step.label() != null ? step.label() : itemName(step.itemId())) + ".");
                    onItemGained(chr, step.itemId());
                }
            }
        }
    }

    /**
     * The player forfeited the quest in the client's quest window ({@code QUEST_LOG_MIRROR}): progress is lost and
     * collected items stay, as for a stock quest. A first take is dropped from the ledger, so its giver offers it again;
     * a repeat goes back to COMPLETED (its count and cooldown stay) and the log shows it completed again. An escort the
     * quest started (an ESCORT step up to the current one, that persona still AWAY in the player's roster) heads home.
     */
    public boolean abandon(Character chr, String questId) {
        QuestState state = world.store().questState(chr.getId(), questId);
        Quest quest = world.content().quest(questId);
        if (state == null || quest == null || state.status() != QuestStatus.ACTIVE) {
            return false;
        }
        Step step = quest.step(state.stepIndex());
        if (state.repeats() > 0) {
            world.store().saveQuestState(new QuestState(chr.getId(), questId, state.stepIndex(), 0, QuestStatus.COMPLETED,
                    state.startedAtMs(), state.startedAtMs(), state.repeats()));
            world.questLog().completed(chr, quest);
        } else {
            world.store().deleteQuestState(chr.getId(), questId);
        }
        world.markers().forget(chr);
        world.store().event(chr.getId(), quest.persona(), "quest.abandon", questId + " at " + (step == null ? "?" : step.id()));
        for (int i = 0; i <= state.stepIndex() && i < quest.steps().size(); i++) {
            Step earlier = quest.step(i);
            if (earlier.type() != StepType.ESCORT || earlier.persona() == null) {
                continue;
            }
            Persona escort = world.content().persona(earlier.persona());
            LivingStore.RosterEntry row = escort == null ? null : world.store().rosterEntry(chr.getId(), escort.id());
            if (row != null && row.state() == LivingStore.RosterState.AWAY) {
                world.executor().submit("roster.escort-abandoned",
                        () -> world.roster().release(chr.getId(), escort, "quest-abandoned"));
            }
        }
        Persona giver = world.content().persona(quest.persona());
        chr.dropMessage(5, "You gave up " + quest.title() + "." + (giver == null ? "" : " " + giver.name() + " may ask again."));
        return true;
    }

    // ---- progression ---------------------------------------------------------------------

    private void progress(Character chr, Persona giver, Quest quest, QuestState state, int delta) {
        int progress = state.progress() + delta;
        Step step = quest.step(state.stepIndex());
        int target = Math.max(1, step.count());
        QuestState updated = new QuestState(chr.getId(), quest.id(), state.stepIndex(), Math.min(progress, target),
                state.status(), state.startedAtMs(), state.completedAtMs(), state.repeats());
        world.store().saveQuestState(updated);
        world.questLog().progressed(chr, quest, updated);
        chr.dropMessage(5, quest.title() + ": " + step.id().replace('-', ' ') + " " + updated.progress() + "/" + target);
        if (updated.progress() >= target) {
            advance(chr, giver, quest, updated);
        }
    }

    private void advance(Character chr, Persona giver, Quest quest, QuestState state) {
        int next = state.stepIndex() + 1;
        if (next >= quest.steps().size()) {
            complete(chr, giver, quest, state);
            return;
        }
        QuestState updated = new QuestState(chr.getId(), quest.id(), next, 0, QuestStatus.ACTIVE,
                state.startedAtMs(), 0L, state.repeats());
        Step step = quest.step(next);
        if (step.type() == StepType.COLLECT_ITEM) {
            updated = new QuestState(chr.getId(), quest.id(), next, Math.min(step.count(),
                    countEligible(chr, quest, step.itemId())), QuestStatus.ACTIVE, state.startedAtMs(), 0L, state.repeats());
        }
        world.store().saveQuestState(updated);
        world.questLog().progressed(chr, quest, updated);
        world.markers().forget(chr);
        world.store().event(chr.getId(), quest.persona(), "quest.step", quest.id() + " -> " + step.id());
        describeStep(chr, quest, updated);
        if (step.type() == StepType.COLLECT_ITEM && updated.progress() >= step.count()) {
            advance(chr, giver, quest, updated);
        } else if (step.type() == StepType.REACH_LEVEL && chr.getLevel() >= step.level()) {
            advance(chr, giver, quest, updated);
        } else if (step.type() == StepType.ESCORT && step.persona() != null) {
            Persona escort = world.content().persona(step.persona());
            world.executor().submit("roster.escort", () -> world.roster().beginEscort(chr, escort, quest, step));
        }
    }

    private void complete(Character chr, Persona giver, Quest quest, QuestState state) {
        long now = System.currentTimeMillis();
        QuestState done = new QuestState(chr.getId(), quest.id(), state.stepIndex(), state.progress(),
                QuestStatus.COMPLETED, state.startedAtMs(), now, state.repeats() + 1);
        world.store().saveQuestState(done);
        world.markers().forget(chr);
        Reward reward = quest.rewards();
        if (reward.meso() > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.QUEST)) { chr.gainMeso(reward.meso(), true); }
        }
        if (reward.exp() > 0) {
            chr.gainExp(reward.exp(), true, true);
        }
        List<String> gear = new ArrayList<>();
        for (GearReward piece : reward.gear()) {
            int itemId = gearFor(chr, piece);
            if (itemId == 0) {
                continue;
            }
            FriendForge.Plan plan = forgePlan(chr, quest, piece, itemId);
            if (InventoryManipulator.addFromDrop(chr.getClient(), FriendForge.forge(plan), true)) {
                gear.add(gearLine(giver, piece, plan));
                StringBuilder detail = new StringBuilder(itemId + " slots " + plan.slots());
                for (FriendForge.Use use : plan.uses()) {
                    detail.append(", scroll ").append(use.scroll().id()).append(' ').append(use.takes()).append('/').append(use.count());
                }
                world.store().event(chr.getId(), quest.persona(), "friend.forge", detail.toString());
            } else {
                chr.dropMessage(5, "Your inventory is full; " + itemName(itemId) + " could not be given.");
            }
        }
        for (ItemStack stack : reward.items()) {
            if (InventoryManipulator.checkSpace(chr.getClient(), stack.itemId(), stack.count(), "")) {
                if (!RewardItems.give(chr, stack.itemId(), stack.count())) {
                    chr.dropMessage(5, itemName(stack.itemId()) + " x" + stack.count() + " could not be given.");
                }
            } else {
                chr.dropMessage(5, "Your inventory is full; " + itemName(stack.itemId()) + " x" + stack.count()
                        + " could not be given.");
            }
        }
        if (reward.fame() != 0) {
            chr.gainFame(reward.fame());
        }
        for (String flag : reward.flags()) {
            world.bonds().setFlag(chr.getId(), quest.persona(), flag);
            world.bonds().setPlayerFlag(chr.getId(), flag);
        }
        for (String flag : reward.worldFlags()) {
            world.store().setWorldFlag(flag, chr.getId());
        }
        if (giver != null && reward.bondPoints() != 0) {
            world.bonds().addPoints(chr, giver, reward.bondPoints(), "quest " + quest.id());
        }
        // the closing line of the chapter the quest was completed in: a chapter this completion opens (its world flag, or
        // onComplete) overrides it for the players who come after, not for the one who opened it
        List<String> closingLines = giver == null ? List.of() : world.arcs().lines(giver, "questComplete." + quest.id());
        if (quest.onComplete() != null && giver != null) {
            world.arcs().advance(giver, quest.onComplete().to(), "quest " + quest.id() + " by " + chr.getName(), false);
        }
        world.arcs().evaluateGates();
        world.store().event(chr.getId(), quest.persona(), "quest.complete", quest.id());
        world.questLog().completed(chr, quest);
        chr.dropMessage(5, "Quest complete: " + quest.title() + ".");
        if (giver != null) {
            String closing = closingLines.isEmpty() ? "that settles it." : pick(closingLines, chr);
            LivingTalk.open(chr, ResidentDialogue.portraitOf(giver))
                    .say(closing)
                    .ok("#e" + quest.title() + "#n complete.\r\n\r\n" + rewardSummary(reward, gear))
                    .show();
        }
    }

    // ---- friendship gear ------------------------------------------------------------------

    /** The giver's own piece, or the pool's piece for this player (FriendForge.pick), 0 when none fits their job. */
    private static int gearFor(Character chr, GearReward gear) {
        if (gear.owned()) {
            return gear.pool().get(0);
        }
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        return FriendForge.pick(gear.pool(), chr.getJob().getId(), chr.getGender(), weapon == null ? 0 : weapon.getItemId(),
                FriendForge.wz());
    }

    /**
     * The piece to its recipe, every scroll taking (a weapon gets its own class's scrolls, armour the player's); or,
     * with no recipe, scrolled at the friendship the player has with the giver now.
     */
    private FriendForge.Plan forgePlan(Character chr, Quest quest, GearReward gear, int itemId) {
        if (!gear.scrolls().isEmpty()) {
            return FriendForge.recipe(itemId, gear.scrolls(), FriendForge.classOf(itemId, chr.getJob().getId()), FriendForge.wz());
        }
        return FriendForge.plan(itemId, chr.getJob().getId(), world.friendship().level(chr.getId(), quest.persona()),
                world.config().friendshipScrollTakes60(), world.config().friendshipScrollTakes10(), FriendForge.wz());
    }

    /**
     * "#v1082149# #t1082149#, scrolled by Fenna: 5 of 7 slots took (+10 att)"; for the giver's own piece
     * "Garrick's own, 2x 10% + 5x 60%, every one took (+20 att, +11 STR, +2 def)"; for a piece made to a recipe for
     * the player "made by Wynne, 2x 10% + 3x 60%, every one took (+12 att)".
     */
    private static String gearLine(Persona giver, GearReward gear, FriendForge.Plan plan) {
        String line = "#v" + plan.itemId() + "# #t" + plan.itemId() + "#";
        if (plan.uses().isEmpty() || giver == null) {
            return line;
        }
        Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        List<String> recipe = new ArrayList<>();
        int took = 0;
        int tried = 0;
        for (FriendForge.Use use : plan.uses()) {
            recipe.add(use.count() + "x " + use.scroll().success() + "%");
            took += use.takes();
            tried += use.count();
            for (Map.Entry<String, Integer> stat : use.scroll().stats().entrySet()) {
                totals.merge(stat.getKey(), stat.getValue() * use.takes(), Integer::sum);
            }
        }
        StringBuilder gains = new StringBuilder();
        for (Map.Entry<String, Integer> stat : totals.entrySet()) {
            if (stat.getValue() > 0) {
                gains.append(gains.isEmpty() ? "" : ", ").append('+').append(stat.getValue()).append(' ').append(statName(stat.getKey()));
            }
        }
        String took1 = took == tried ? "every one took" : took + " of " + tried + " took";
        String scrolled = gear.owned() ? giver.name() + "'s own, " + String.join(" + ", recipe) + ", " + took1
                : !gear.scrolls().isEmpty() ? "made by " + giver.name() + ", " + String.join(" + ", recipe) + ", " + took1
                : "scrolled by " + giver.name() + ": " + took + " of " + plan.slots() + " slots took";
        return line + "\r\n   " + scrolled + (gains.isEmpty() ? "" : " (" + gains + ")");
    }

    private static String statName(String stat) {
        return switch (stat) {
            case "PAD" -> "att";
            case "MAD" -> "magic att";
            case "PDD" -> "def";
            case "MDD" -> "magic def";
            case "ACC" -> "accuracy";
            case "EVA" -> "avoid";
            case "MHP" -> "HP";
            case "MMP" -> "MP";
            case "Speed", "Jump" -> stat.toLowerCase(java.util.Locale.ROOT);
            default -> stat;
        };
    }

    private static boolean roomForGear(Character chr, Quest quest) {
        int pieces = quest.rewards().gear().size();
        return pieces == 0 || chr.getInventory(InventoryType.EQUIP).getNumFreeSlot() >= pieces;
    }

    /** The player's quest log as one NPC window. */
    public void showLog(Character chr) {
        StringBuilder text = new StringBuilder("#eYour resident quests#n\r\n");
        List<QuestState> states = active(chr);
        if (states.isEmpty()) {
            text.append("\r\nNone active. Ask a townsperson for work.");
        }
        int portrait = 9010000;
        for (QuestState state : states) {
            Quest quest = world.content().quest(state.questId());
            if (quest == null) {
                continue;
            }
            Persona giver = world.content().persona(quest.persona());
            if (giver != null && portrait == 9010000) {
                portrait = ResidentDialogue.portraitOf(giver);
            }
            text.append("\r\n#b").append(quest.title()).append("#k (").append(giver == null ? quest.persona() : giver.name())
                    .append(", ").append(giver == null ? "" : giver.homeTown().replace('-', ' ')).append(")\r\n   ")
                    .append(describe(quest.step(state.stepIndex()), state));
        }
        LivingTalk.open(chr, portrait).ok(text.toString()).show();
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Items in the player's inventory minus what the quest giver itself handed over recently. */
    private int countEligible(Character chr, Quest quest, int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        int have = chr.getInventory(type).countById(itemId);
        int tainted = world.store().transferredSince(chr.getId(), quest.persona(), itemId,
                System.currentTimeMillis() - PROVENANCE_WINDOW_MS);
        return Math.max(0, have - tainted);
    }

    private static boolean removeFromPlayer(Character chr, int itemId, int count) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (chr.getInventory(type).countById(itemId) < count) {
            return false;
        }
        InventoryManipulator.removeById(chr.getClient(), type, itemId, count, false, false);
        return true;
    }

    public void describeStep(Character chr, Quest quest, QuestState state) {
        Step step = quest.step(state.stepIndex());
        if (step == null) {
            return;
        }
        chr.dropMessage(5, quest.title() + " - " + describe(step, state));
    }

    public String describe(Step step, QuestState state) {
        switch (step.type()) {
            case TALK: return "talk to " + giverName(step);
            case KILL_MOBS: case KILL_BOSS: return "defeat " + mobNames(step.mobIds()) + mobPlace(step.mobIds()) + " "
                    + state.progress() + "/" + step.count() + (step.with() != null ? " with " + personaName(step.with()) : "");
            case HUNT_WITH: return "hunt " + mobNames(step.mobIds()) + mobPlace(step.mobIds()) + " with " + personaName(step.persona()) + " "
                    + state.progress() + "/" + step.count();
            case COLLECT_ITEM: return "collect " + (step.label() != null ? step.label() : itemName(step.itemId())) + " "
                    + state.progress() + "/" + step.count();
            case DELIVER: return "bring " + (step.take() == null ? "word" : itemName(step.take().itemId()) + " x" + step.take().count())
                    + " to " + personaName(step.to());
            case VISIT_MAP: return "go to " + mapName(step.mapIds().get(0));
            case TALK_TO: return "talk to " + personaName(step.persona());
            case HEAR_RUMOR: return "learn what the townsfolk say";
            case ESCORT: return "escort " + personaName(step.persona());
            case PAY: return "pay " + step.meso() + " meso";
            case GIVE_ITEM: return "hand over " + itemName(step.itemId()) + " x" + step.count();
            case REACH_LEVEL: return "reach level " + step.level();
            case WAIT: return "come back in " + step.hours() + " hour(s)";
            case PARTY_QUEST_CLEAR: return "clear a party quest" + (step.to() == null ? "" : " (" + step.to() + ")");
            default: return step.id();
        }
    }

    private String giverName(Step step) {
        return "the quest giver";
    }

    private String personaName(String personaId) {
        if (personaId == null) {
            return "?";
        }
        if (personaId.startsWith("npc:")) {
            String npc = null;
            try {
                npc = places() ? ContentChat.npcPlace(Integer.parseInt(personaId.substring(4))) : null; // "Athena Pierce in Henesys"
            } catch (NumberFormatException ignored) {
                // a malformed id keeps the raw form
            }
            return npc == null ? "NPC " + personaId.substring(4) : npc;
        }
        Persona persona = world.content().persona(personaId);
        return persona == null ? personaId : persona.name();
    }

    private static String itemName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name;
    }

    private static String mobNames(List<Integer> mobIds) {
        if (mobIds.isEmpty()) {
            return "monsters";
        }
        String name = server.life.LifeFactory.getMonster(mobIds.get(0)) == null ? null
                : server.life.LifeFactory.getMonster(mobIds.get(0)).getName();
        String first = name == null ? "monsters" : name;
        return mobIds.size() > 1 ? first + " and others" : first;
    }

    /** " (Tree Dungeon, near Ellinia)": where the first mob is met, from server knowledge; "" when unknown. */
    private static String mobPlace(List<Integer> mobIds) {
        String place = mobIds.isEmpty() || !places() ? null : ContentChat.mobPlace(mobIds.get(0));
        return place == null ? "" : " (" + place + ")";
    }

    /** "Pig Beach, near Lith Harbor"; "map <id>" until server knowledge is loaded. */
    private static String mapName(int mapId) {
        String place = places() ? ContentChat.mapPlace(mapId) : null;
        return place == null ? "map " + mapId : place;
    }

    /** Quest text names places only while knowledge is on for residents (config.yaml knowledge.RESIDENTS). */
    private static boolean places() {
        return ContentChat.shared().on(ContentChat.Host.RESIDENTS);
    }

    private static boolean sameParty(Character owner, Character agent) {
        Party party = owner.getParty();
        return party != null && agent.getParty() != null && party.getId() == agent.getParty().getId();
    }

    private Character onlineOwner(int ownerId) {
        return ownerId == 0 ? null : world.roster().onlineCharacter(ownerId);
    }

    private static String key(Character chr, Persona persona) {
        return chr.getId() + ":" + persona.id();
    }

    static String pick(List<String> lines, Character chr) {
        return lines.get(Math.floorMod(chr.getId() + (int) (System.currentTimeMillis() / 7000L), lines.size()));
    }

    /** True when one of the persona's own active quests asks this player to collect the item. */
    public boolean isOwnQuestTarget(Character chr, Persona persona, int itemId) {
        for (QuestState state : activeFrom(chr, persona)) {
            Quest quest = world.content().quest(state.questId());
            for (Step step : quest.steps()) {
                if ((step.type() == StepType.COLLECT_ITEM && step.itemId() == itemId)
                        || (step.type() == StepType.DELIVER && step.take() != null && step.take().itemId() == itemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when any active quest of this player needs the item (kept out of bench selling). */
    public boolean isQuestItemFor(int characterId, int itemId) {
        for (QuestState state : world.store().questStates(characterId, QuestStatus.ACTIVE)) {
            Quest quest = world.content().quest(state.questId());
            if (quest == null) {
                continue;
            }
            for (Step step : quest.steps()) {
                if ((step.type() == StepType.COLLECT_ITEM && step.itemId() == itemId)
                        || (step.type() == StepType.DELIVER && step.take() != null && step.take().itemId() == itemId)
                        || (step.type() == StepType.GIVE_ITEM && step.itemId() == itemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lines for the player's quest log command. */
    public List<String> summary(Character chr) {
        List<String> lines = new ArrayList<>();
        for (QuestState state : active(chr)) {
            Quest quest = world.content().quest(state.questId());
            if (quest == null) {
                continue;
            }
            Persona giver = world.content().persona(quest.persona());
            lines.add(quest.title() + " (" + (giver == null ? quest.persona() : giver.name()) + "): "
                    + describe(quest.step(state.stepIndex()), state));
        }
        if (lines.isEmpty()) {
            lines.add("No resident quests active. Ask a townsperson for work.");
        }
        return lines;
    }

    public LivingStore store() {
        return world.store();
    }
}
