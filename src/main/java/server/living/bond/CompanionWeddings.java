package server.living.bond;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.server.channel.handlers.RingActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A player marrying their own companion by the stock flow (docs/BOT_LORE_COUPLES_PLAN.md, decision 4): the companion
 * answers the engagement ring itself (a bot has no Accept window), yes at friendship Lv {@link Friendship#CLOSE_FRIEND}
 * or more; it keeps the wedding garment its fiance hands it and wears it in the wedding's maps; and at the altar it
 * confirms its vows to the officiant (High Priest John at the cathedral, Pelvis Bebop at the chapel) once its fiance has.
 */
public final class CompanionWeddings {
    private static final Logger log = LoggerFactory.getLogger(CompanionWeddings.class);
    /** isSuitedForWedding in 9201002.js and 9201011.js: four garments from each base id. */
    static final int MALE_GARMENT_BASE = 1050131;
    static final int FEMALE_GARMENT_BASE = 1051150;
    /** The garments are overalls: they go on in the top's slot. */
    private static final short OVERALL_SLOT = -5;
    /** The chapel's and the cathedral's wedding maps (WeddingChapel.js, WeddingCathedral.js minMapId..maxMapId). */
    static final int WEDDING_MAP_MIN = 680000100;
    static final int WEDDING_MAP_MAX = 680000401;
    /** A moment to think it over before it answers, and to step up before its vows. */
    private static final long ANSWER_DELAY_MS = 4_000L;
    private static final long VOWS_DELAY_MS = 3_000L;

    private static final List<String> ACCEPT = List.of("...yes. {name}, yes.", "i was hoping you'd ask. yes.",
            "you're serious? ...then so am i. yes.");
    private static final List<String> DECLINE = List.of("{name}... i like you. but not like that. not yet.",
            "that's a big ask. we're not there yet.", "ask me again when you know me better.");
    private static final List<String> STRANGER = List.of("i don't even know you. no.", "...is this a joke? no.");
    private static final List<String> GARMENT = List.of("i'll wear it on the day.", "it's beautiful. i'll keep it safe.");
    private static final List<String> VOWS = List.of("i do.", "i do. i really do.");

    private final LivingWorld world;

    public CompanionWeddings(LivingWorld world) {
        this.world = world;
    }

    /** True for a wedding garment cut for {@code gender} (0 male, 1 female). */
    static boolean garmentFor(int itemId, int gender) {
        int base = gender == 0 ? MALE_GARMENT_BASE : FEMALE_GARMENT_BASE;
        return itemId >= base && itemId < base + 4;
    }

    /** True when its answer is yes: the proposer's own companion, a close friend of the proposer's. */
    static boolean accepts(boolean ownCompanion, int friendshipLevel) {
        return ownCompanion && friendshipLevel >= Friendship.CLOSE_FRIEND;
    }

    /** {@code source} proposed to {@code target} with an engagement ring (RingActionHandler.sendEngageProposal). */
    public void proposed(Character source, Character target) {
        if (!AgentPresence.isAgent(target) || AgentPresence.isAgent(source)) {
            return;
        }
        Persona persona = personaOf(target);
        boolean own = persona != null && world.roster().cachedOwnerOf(persona.id()) == source.getId();
        int level = own ? world.friendship().level(source.getId(), persona.id()) : 0;
        boolean yes = accepts(own, level);
        String line = own ? pick(persona, yes ? "proposal.accept" : "proposal.decline", yes ? ACCEPT : DECLINE)
                : pick(persona, "proposal.stranger", STRANGER);
        String said = line.replace("{name}", source.getName());
        log.info("{} proposed to {} ({}): {} (own companion {}, friendship Lv {})", source.getName(), target.getName(),
                persona == null ? "not a persona" : persona.id(), yes ? "accepted" : "declined", own, level);
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> onAgent(target, live -> {
                    AgentDialogueTransportRuntime.sayMapNow(live, said);
                    RingActionHandler.answerProposal(source, live, source.getId(), yes);
                }), ANSWER_DELAY_MS);
    }

    /**
     * The player handing {@code item} to a Resident (Friendship.gift): a wedding garment for its body goes into the bag
     * of the companion engaged to them (marked untradeable, so no sale or drop takes it). False for any other gift.
     */
    public boolean keepsGarment(Character giver, Persona persona, Item item) {
        Character companion = live(world.residents().characterIdOf(persona.id()));
        if (companion == null || companion.getPartnerId() != giver.getId() || companion.isMarried()
                || !garmentFor(item.getItemId(), companion.getGender())) {
            return false;
        }
        Item held = giver.getInventory(item.getInventoryType()).getItem(item.getPosition());
        if (held == null || held.getItemId() != item.getItemId()) {
            return false;
        }
        Item garment = held.copy();
        garment.setFlag((short) (garment.getFlag() | ItemConstants.UNTRADEABLE));
        InventoryManipulator.removeFromSlot(giver.getClient(), item.getInventoryType(), item.getPosition(), (short) 1, false);
        String said = pick(persona, "gift.garment", GARMENT);
        onAgent(companion, live -> {
            InventoryManipulator.addFromDrop(live.getClient(), garment, false);
            AgentDialogueTransportRuntime.sayMapNow(live, said);
        });
        return true;
    }

    /**
     * An engaged companion coming into a wedding's maps puts its garment on there: the altar checks the partner wears
     * one before it takes the first vows (getWeddingPreparationStatus in 9201002.js).
     */
    public void enteredMap(Character chr, int mapId) {
        if (mapId < WEDDING_MAP_MIN || mapId > WEDDING_MAP_MAX || !AgentPresence.isAgent(chr)
                || chr.getPartnerId() <= 0 || chr.isMarried()) {
            return;
        }
        onAgent(chr, CompanionWeddings::wearGarment);
    }

    /**
     * {@code player} confirmed their vows to {@code officiantNpcId} at the altar and their partner has not: a companion
     * partner puts its garment on (should it have come off) and confirms its own to the same officiant, which weds them.
     */
    public void vowsConfirmed(Character player, int officiantNpcId) {
        if (AgentPresence.isAgent(player) || player.getMap() == null) {
            return;
        }
        Character partner = player.getMap().getCharacterById(player.getPartnerId());
        if (partner == null || !AgentPresence.isAgent(partner)) {
            return;
        }
        Persona persona = personaOf(partner);
        String said = pick(persona, "wedding.vows", VOWS);
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> onAgent(partner, live -> {
            wearGarment(live);
            AgentDialogueTransportRuntime.sayMapNow(live, said);
            if (!AgentPrimitiveCapabilityGatewayRuntime.gateway().runNpcScript(live, officiantNpcId)) {
                log.warn("{} could not confirm its vows to {}", live.getName(), player.getName());
            }
        }), VOWS_DELAY_MS);
    }

    /** Puts a carried garment for its body on (the altar checks it is worn); nothing when one is worn already. */
    static void wearGarment(Character agent) {
        Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(OVERALL_SLOT);
        if (worn != null && garmentFor(worn.getItemId(), agent.getGender())) {
            return;
        }
        for (Item item : agent.getInventory(InventoryType.EQUIP).list()) {
            if (garmentFor(item.getItemId(), agent.getGender())) {
                InventoryManipulator.equip(agent.getClient(), item.getPosition(), OVERALL_SLOT);
                return;
            }
        }
    }

    private Persona personaOf(Character agent) {
        Persona persona = world.residents().personaOfCharacterId(agent.getId());
        return persona == null || LivingContent.isHire(persona.id()) ? null : persona;
    }

    private static Character live(int characterId) {
        AgentRuntimeEntry entry = characterId <= 0 ? null : AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return entry == null ? null : entry.bot();
    }

    /** On the Agent's own mailbox when it has one (its next tick), else at once. */
    private static void onAgent(Character agent, java.util.function.Consumer<Character> action) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
        if (entry == null) {
            action.accept(agent);
            return;
        }
        AgentMailboxRuntime.dispatch(entry, runtime -> {
            Character live = runtime.bot();
            if (live != null && live.getId() == agent.getId()) {
                action.accept(live);
            }
            return null;
        });
    }

    private static String pick(Persona persona, String intent, List<String> fallback) {
        List<String> lines = persona == null ? List.of() : persona.lines(intent);
        List<String> from = lines.isEmpty() ? fallback : lines;
        return from.get(ThreadLocalRandom.current().nextInt(from.size()));
    }
}
