package server.living.population.needs;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.LivingWorldConfig;
import config.PopulationConfig;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopFactory;
import server.agents.capabilities.expedition.boss.AgentBossCitizenRuns;
import server.integration.AgentPresence;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.ChatterEngine;
import server.living.population.Market;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.roster.BossEntrances;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The needs board (docs/BOT_CONCEPT_PLAN.md F1): what citizens ask for, from what they really lack, and what players
 * hand them for it. Open requests live here (and in living_need); a citizen's are derived again at each load and every
 * NEEDS_REFRESH_MINUTES (SUPPLY and SCROLL), opened by CitizenGear for a want (GEAR) and by its forming party (PARTY).
 * A player claims a request for a while (NEEDS_CLAIM_MINUTES, NEEDS_CLAIMS_PER_PLAYER at once) and hands the item over in
 * person, into the citizen's inventory, or at a board, into escrow while it is away; it pays from its own purse in the
 * same economy transaction. Periodic work rides the population's 60 s lane ({@link #tick}); nothing here calls a model.
 */
public final class NeedLedger {
    private static final Logger log = LoggerFactory.getLogger(NeedLedger.class);
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    /** The boss SUPPLY requests measure against: the expedition citizens provision for (CitizenBossDoor). */
    static final String SUPPLY_BOSS = AgentBossCitizenRuns.EASY_BALROG.key();
    private static final String BALROG_LOBBY = "balrog";
    /** Worn slot name (CitizenGear's) -> equipped position, for wearing a want. */
    private static final Map<String, Short> SLOT_POSITIONS = Map.of("cap", (short) -1, "top", (short) -5,
            "pants", (short) -6, "shoes", (short) -7, "glove", (short) -8, "cape", (short) -9, "shield", (short) -10,
            "weapon", (short) -11);

    /** Where an item and the mesos really move (the live one: {@link LiveNeedHands}); apart so tests need no client. */
    interface Hands {
        /** Units of the need's item the player carries that it takes (an equip: base or better). */
        int carried(Character player, Need need);

        /** The citizen's purse: its character's when loaded ({@code citizen}), else its stored row; -1 unknown. */
        long purse(int citizenId, Character citizen);

        /**
         * Moves {@code count} units from the player into the citizen's inventory (or its escrow when {@code citizen} is
         * null) and {@code pay} meso back, storing {@code after} in the same commit. Null when done, else why not.
         */
        String transfer(Character player, Character citizen, Need need, Need after, int count, long pay);

        /** Moves what waits in escrow into the loaded citizen's inventory and ends its ESCROW requests; what it took. */
        List<Integer> takeEscrow(Character citizen, List<Need> escrowed);

        /** The citizen puts what it got to use: applies a scroll (true: it passed), wears gear, readies its potions. */
        Boolean use(Character citizen, Need need);
    }

    /** The item data the ledger reads (WZ names, a scroll's rate, the scroll rule, an equip's base); apart so tests need no database. */
    interface Items {
        /** The item's name, or null. */
        String name(int itemId);

        /** A scroll's success rate in percent; 0 unknown. */
        int successRate(int scrollId);

        boolean canApplyScroll(int scrollId, int equipId);

        /** Whether a player may hand it over at all (not untradeable by its data, not a quest item). */
        boolean tradeable(int itemId);

        /** {@link NeedQuality}: {@code stats} (a shop listing's) at least the base of {@code itemId}. */
        boolean baseOrBetter(int itemId, Map<String, Object> stats);
    }

    /** The game's item data. */
    private static final class WzItems implements Items {
        @Override
        public String name(int itemId) {
            return ItemInformationProvider.getInstance().getName(itemId);
        }

        @Override
        public int successRate(int scrollId) {
            Map<String, Integer> stats = ItemInformationProvider.getInstance().getEquipStats(scrollId);
            Integer success = stats == null ? null : stats.get("success");
            return success == null ? 0 : success;
        }

        @Override
        public boolean canApplyScroll(int scrollId, int equipId) {
            return ItemInformationProvider.getInstance().canApplyScroll(scrollId, equipId);
        }

        @Override
        public boolean tradeable(int itemId) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return !ii.isUntradeableRestricted(itemId) && !ii.isQuestItem(itemId);
        }

        @Override
        public boolean baseOrBetter(int itemId, Map<String, Object> stats) {
            return NeedQuality.baseOrBetter(itemId, stats);
        }
    }

    /** What a claim came to. */
    public enum Claim { OK, HELD, LIMIT, GONE }

    private final Population population;
    private final NeedStore store;
    private final Random random;
    private Hands hands;
    private final Items items;
    private final NeedBoards boards;
    private final Map<Integer, Need> live = new ConcurrentHashMap<>();
    /** "citizen:KIND:item" -> when such a request last ended (the repost pause). */
    private final Map<String, Long> ended = new ConcurrentHashMap<>();
    private final Map<Integer, Long> derivedAt = new ConcurrentHashMap<>();
    /** Player -> when a citizen last whispered them a request. */
    private final Map<Integer, Long> whisperedAt = new ConcurrentHashMap<>();
    /** Citizen -> the gear it ever asked for (its GEAR rows, read once a session). */
    private final Map<Integer, Set<Integer>> gearAsked = new ConcurrentHashMap<>();
    private final Set<Integer> escrowHolders = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Object> locks = new ConcurrentHashMap<>();
    private volatile boolean started;

    public NeedLedger(Population population) {
        this(population, new NeedStore(), null, new WzItems());
    }

    NeedLedger(Population population, NeedStore store, Hands hands, Items items) {
        this.population = population;
        this.store = store;
        this.random = new Random(population.config().SEED ^ 0x9eed5L);
        this.hands = hands;
        this.items = items;
        this.boards = new NeedBoards(population, this);
    }

    public boolean on() {
        return population.config().NEEDS_ENABLED;
    }

    private PopulationConfig config() {
        return population.config();
    }

    private Hands hands() {
        Hands current = hands;
        if (current == null) {
            current = new LiveNeedHands(population, store, items);
            hands = current;
        }
        return current;
    }

    public NeedBoards boards() {
        return boards;
    }

    /** Loads the stored requests once, the first time the switch is on (never at construction: tests, switch off). */
    synchronized void start(long nowMs) {
        if (started) {
            return;
        }
        started = true;
        for (Need need : store.live(nowMs)) {
            live.put(need.id(), need);
        }
        for (Need need : store.endedSince(nowMs - config().NEEDS_REPOST_HOURS * HOUR_MS)) {
            ended.merge(key(need.citizenId(), need.kind(), need.itemId()), need.closedAtMs(), Math::max);
        }
        escrowHolders.addAll(store.escrowHolders());
        log.info("needs board: {} requests live, {} citizens with escrow", live.size(), escrowHolders.size());
    }

    private Object lock(int citizenId) {
        return locks.computeIfAbsent(citizenId, id -> new Object());
    }

    private static String key(int citizenId, NeedKind kind, int itemId) {
        return citizenId + ":" + kind + ":" + itemId;
    }

    // ---- views ------------------------------------------------------------------------------

    /** Open requests anyone may help with, oldest first. */
    public List<Need> open() {
        List<Need> out = new ArrayList<>();
        for (Need need : live.values()) {
            if (need.isOpen()) {
                out.add(need);
            }
        }
        out.sort(Comparator.comparingLong(Need::createdAtMs));
        return out;
    }

    /** This citizen's requests still in play (open, in escrow, owing thanks). */
    public List<Need> of(int citizenId) {
        List<Need> out = new ArrayList<>();
        for (Need need : live.values()) {
            if (need.citizenId() == citizenId) {
                out.add(need);
            }
        }
        out.sort(Comparator.comparingLong(Need::createdAtMs));
        return out;
    }

    public Need need(int id) {
        return live.get(id);
    }

    /** The open requests this player holds now. */
    public List<Need> claimedBy(int playerId, long nowMs) {
        List<Need> out = new ArrayList<>();
        for (Need need : open()) {
            if (need.holder(nowMs) == playerId) {
                out.add(need);
            }
        }
        return out;
    }

    private List<Need> openOf(int citizenId, NeedKind kind) {
        List<Need> out = new ArrayList<>();
        for (Need need : live.values()) {
            if (need.citizenId() == citizenId && need.isOpen() && (kind == null ? need.kind().item() : need.kind() == kind)) {
                out.add(need);
            }
        }
        return out;
    }

    /** Open item requests of the citizen (the NEEDS_PER_CITIZEN cap counts these; PARTY is apart). */
    int openItemNeeds(int citizenId) {
        return openOf(citizenId, null).size();
    }

    /** One of the citizen's open item requests to talk about, or null. */
    public Need posting(int citizenId) {
        return posting(citizenId, null);
    }

    /**
     * One of the citizen's open item requests a chatter intent can talk about, or null: an intent id {@code need.<kind>.*}
     * ("need.supply.fm") talks about that kind only, any other about any.
     */
    public Need posting(int citizenId, String intentId) {
        if (!on()) {
            return null;
        }
        NeedKind kind = kindOf(intentId);
        List<Need> mine = openOf(citizenId, kind);
        mine.removeIf(need -> need.remaining() <= 0 || !need.kind().item());
        return mine.isEmpty() ? null : mine.get(random.nextInt(mine.size()));
    }

    /** The kind an intent id {@code need.<kind>.*} names, or null. */
    static NeedKind kindOf(String intentId) {
        if (intentId == null || !intentId.startsWith("need.")) {
            return null;
        }
        String[] parts = intentId.split("\\.");
        return parts.length > 1 ? NeedKind.parse(parts[1].toUpperCase(Locale.ROOT)) : null;
    }

    /** Whether the citizen wants gear or a scroll it could find in a player's shop. */
    public boolean wantsFromMarket(int citizenId) {
        if (!on()) {
            return false;
        }
        for (Need need : openOf(citizenId, null)) {
            if (need.kind() == NeedKind.GEAR || need.kind() == NeedKind.SCROLL) {
                return true;
            }
        }
        return false;
    }

    // ---- words --------------------------------------------------------------------------------

    String itemName(int itemId) {
        String name = items.name(itemId);
        return name == null ? "item " + itemId : name.toLowerCase(Locale.ROOT);
    }

    /** An item as a player types it: "claw att 60%" for "Scroll for Claw for ATT" (60%), else its name in lower case. */
    String spoken(int itemId) {
        return itemId / 10_000 != 204 ? itemName(itemId) : spoken(itemName(itemId), items.successRate(itemId));
    }

    /** "scroll for claw for att" at 60 -> "claw att 60%"; any other name as it is. */
    static String spoken(String name, int success) {
        if (!name.startsWith("scroll for ")) {
            return name;
        }
        String[] parts = name.substring("scroll for ".length()).split(" for ", 2);
        if (parts.length < 2) {
            return name;
        }
        String stat = parts[1].replace("magic att.", "matk").replace("attack", "att").replaceAll("\\s*\\d+%$", "").trim();
        return parts[0] + " " + stat + (success > 0 ? " " + success + "%" : "");
    }

    /** What a request is for, as a player says it: "balrog", "my <the scrolled piece>", "an upgrade", the party's role. */
    String forWords(Need need) {
        return switch (need.kind()) {
            case SUPPLY -> need.detail().contains(BALROG_LOBBY) ? BALROG_LOBBY : need.detail().replace('_', ' ');
            case SCROLL -> {
                int piece = parseInt(need.detail());
                yield piece > 0 ? "my " + itemName(piece) : "my gear";
            }
            case GEAR -> "an upgrade";
            case PARTY -> {
                String[] parts = need.detail().split("\\|", 2);
                yield parts.length > 1 && !parts[1].isEmpty() ? parts[1] : parts[0];
            }
            case MENTOR -> "a mentor";
        };
    }

    /** "120 white potion", "claw att 60%", "1 more cleric". */
    String what(Need need) {
        if (need.kind() == NeedKind.MENTOR) {
            return "someone to show them around";
        }
        if (need.kind() == NeedKind.PARTY) {
            String role = forWords(need);
            return need.remaining() > 1 ? need.remaining() + " more" : "1 more " + role;
        }
        String name = spoken(need.itemId());
        return need.remaining() > 1 ? need.remaining() + " " + name : name;
    }

    /**
     * Fills a request line's slots ({@code {item} {what} {count} {price} {total} {for}}) from the citizen's request of
     * the kind the intent names; false when it has none to talk about. ChatterEngine calls it for a {@code need.*} intent
     * it picked, and for a "B>" line (any kind).
     */
    public boolean slots(CitizenSession session, ChatterEngine.Event event, String intentId) {
        Need need = posting(session.characterId, intentId);
        if (need == null) {
            return false;
        }
        slots(need, event);
        return true;
    }

    void slots(Need need, ChatterEngine.Event event) {
        event.item(spoken(need.itemId()))
                .slot("what", what(need))
                .slot("count", String.valueOf(need.remaining()))
                .slot("price", Market.formatPrice(need.offer()))
                .slot("total", Market.formatPrice(need.pay(need.remaining())))
                .slot("for", forWords(need));
    }

    // ---- derivation ---------------------------------------------------------------------------

    /** At a citizen's load (after its catch-up): what waits in escrow goes into its inventory, then its requests follow it. */
    public void onLoad(CitizenSession session, Character agent, long nowMs) {
        if (!on() || session == null || agent == null) {
            return;
        }
        start(nowMs);
        gearAsked.remove(session.characterId); // read again this session
        try {
            takeEscrow(session, agent, nowMs);
            derive(session, agent, nowMs);
        } catch (RuntimeException failure) {
            log.warn("needs for citizen {} at load failed", session.name(), failure);
        }
    }

    /** SUPPLY and SCROLL from what the citizen carries and wears now; requests no longer true end. */
    void derive(CitizenSession session, Character agent, long nowMs) {
        derivedAt.put(session.characterId, nowMs);
        synchronized (lock(session.characterId)) {
            supply(session, agent, nowMs);
            scroll(session, agent, nowMs);
            mentor(session, agent, nowMs);
        }
    }

    /**
     * F4.5 (living_world.APPRENTICE_ENABLED): a Beginner of up to APPRENTICE_ADOPT_MAX_LEVEL now and then asks for a
     * mentor (APPRENTICE_NEED_CHANCE a derivation, at most APPRENTICE_NEEDS_MAX open world-wide), saying so on its map;
     * a player answers by adopting it (Apprentices). One that has grown past it, or took a job, stops asking.
     */
    private void mentor(CitizenSession session, Character agent, long nowMs) {
        LivingWorldConfig living = population.world() == null ? null : population.world().config();
        List<Need> open = openOf(session.characterId, NeedKind.MENTOR);
        boolean fits = living != null && living.APPRENTICE_ENABLED && agent.isBeginnerJob()
                && agent.getLevel() <= living.APPRENTICE_ADOPT_MAX_LEVEL;
        if (!fits) {
            for (Need need : open) {
                close(need, Need.State.GONE, nowMs);
            }
            return;
        }
        if (!open.isEmpty() || openMentorNeeds() >= living.APPRENTICE_NEEDS_MAX
                || random.nextDouble() >= living.APPRENTICE_NEED_CHANCE) {
            return;
        }
        Need need = post(session.characterId, session, agent.getMapId(),
                new NeedDerivation.Proposal(NeedKind.MENTOR, 0, 1, 0L, "mentor"), deadline(NeedKind.MENTOR, nowMs), nowMs);
        if (need != null) {
            String line = population.chatter().compose(ChatterEngine.Event.of("need.mentor", session.mapId, session.channel)
                    .self(session));
            if (line != null) {
                population.chatter().say(session, line);
            }
        }
    }

    private int openMentorNeeds() {
        int open = 0;
        for (Need need : live.values()) {
            open += need.isOpen() && need.kind() == NeedKind.MENTOR ? 1 : 0;
        }
        return open;
    }

    /** The citizen's open "looking for a mentor" request, or null (Apprentices: no standing gate when it asked). */
    public Need mentorWanted(int citizenId) {
        List<Need> open = openOf(citizenId, NeedKind.MENTOR);
        return open.isEmpty() ? null : open.get(0);
    }

    /** It was adopted by {@code playerId}: its request is met. */
    public void mentorFound(int citizenId, int playerId, long nowMs) {
        synchronized (lock(citizenId)) {
            for (Need need : openOf(citizenId, NeedKind.MENTOR)) {
                Need met = new Need(need.id(), need.citizenId(), need.kind(), 0, need.quantity(), need.quantity(), 0L,
                        need.detail(), need.mapId(), need.state(), 0, 0L, playerId, true, need.createdAtMs(),
                        need.deadlineMs(), 0L);
                replace(met);
                close(met, Need.State.MET, nowMs);
            }
        }
    }

    private boolean runsBalrog(Citizen citizen, int level) {
        if (!config().citizenBosses().contains(SUPPLY_BOSS)) {
            return false;
        }
        for (PopulationContent.Lobby lobby : population.fields().lobbiesFor(citizen, level)) {
            if (BALROG_LOBBY.equals(lobby.id())) {
                return true;
            }
        }
        return false;
    }

    private void supply(CitizenSession session, Character agent, long nowMs) {
        List<Need> current = openOf(session.characterId, NeedKind.SUPPLY);
        List<NeedDerivation.Proposal> proposals = List.of();
        if (runsBalrog(session.citizen, agent.getLevel())) {
            Shop shop = ShopFactory.getInstance().getShopForNPC(BossEntrances.approachStoreNpcId(SUPPLY_BOSS));
            if (shop == null) {
                return; // no store to measure against: leave what is asked as it is
            }
            proposals = NeedDerivation.supply(agent, SUPPLY_BOSS, shop.getItems(), agent.getMeso(),
                    config().NEEDS_SUPPLY_PREMIUM);
        }
        reconcile(session, agent.getMapId(), current, proposals, nowMs);
    }

    /**
     * Open SUPPLY requests follow the proposals: one for the same potion takes the new count (what came in stays
     * counted), one with nothing left to ask ends (it carries enough: bought elsewhere), and a new potion is asked for.
     */
    void reconcile(CitizenSession session, int mapId, List<Need> current, List<NeedDerivation.Proposal> proposals, long nowMs) {
        List<NeedDerivation.Proposal> left = new ArrayList<>(proposals);
        for (Need need : current) {
            NeedDerivation.Proposal same = null;
            for (NeedDerivation.Proposal proposal : left) {
                if (proposal.itemId() == need.itemId()) {
                    same = proposal;
                }
            }
            if (same == null) {
                close(need, Need.State.BOUGHT, nowMs);
                continue;
            }
            left.remove(same);
            int quantity = need.delivered() + same.quantity();
            if (quantity != need.quantity()) {
                replace(need.withQuantity(quantity, need.offer()));
            }
        }
        for (NeedDerivation.Proposal proposal : left) {
            post(session.characterId, session, mapId, proposal, deadline(NeedKind.SUPPLY, nowMs), nowMs);
        }
    }

    private void scroll(CitizenSession session, Character agent, long nowMs) {
        for (Need need : openOf(session.characterId, NeedKind.SCROLL)) {
            if (!scrollStillWanted(agent, need)) {
                close(need, Need.State.BOUGHT, nowMs);
            }
        }
        if (!openOf(session.characterId, NeedKind.SCROLL).isEmpty() || random.nextDouble() >= config().NEEDS_SCROLL_CHANCE) {
            return;
        }
        List<Integer> basket = new ArrayList<>();
        for (PopulationContent.Good good : population.content().basket()) {
            basket.add(good.itemId());
        }
        NeedDerivation.Proposal proposal = NeedDerivation.scroll(NeedDerivation.worn(agent), basket, items::canApplyScroll,
                population.market()::price, agent.getMeso());
        if (proposal != null) {
            post(session.characterId, session, agent.getMapId(), proposal, deadline(NeedKind.SCROLL, nowMs), nowMs);
        }
    }

    /** The piece is still worn with a slot to scroll, and the citizen holds no such scroll yet. */
    private static boolean scrollStillWanted(Character agent, Need need) {
        if (agent.getInventory(InventoryType.USE).countById(need.itemId()) > 0) {
            return false;
        }
        int piece = parseInt(need.detail());
        for (Equip equip : NeedDerivation.worn(agent)) {
            if (equip.getItemId() == piece && equip.getUpgradeSlots() > 0) {
                return true;
            }
        }
        return false;
    }

    private long deadline(NeedKind kind, long nowMs) {
        PopulationConfig config = config();
        if (kind == NeedKind.GEAR) {
            return nowMs + config.NEEDS_GEAR_DEADLINE_HOURS * HOUR_MS;
        }
        int span = config.NEEDS_EXPIRE_MAX_HOURS - config.NEEDS_EXPIRE_MIN_HOURS;
        return nowMs + (config.NEEDS_EXPIRE_MIN_HOURS * HOUR_MS) + (span <= 0 ? 0L : (long) (random.nextDouble() * span * HOUR_MS));
    }

    /** Opens a request when the citizen has room for another and has not just given up on the same one; null otherwise. */
    Need post(int citizenId, CitizenSession session, int mapId, NeedDerivation.Proposal proposal, long deadlineMs,
              long nowMs) {
        if (proposal.kind().item() && openItemNeeds(citizenId) >= config().NEEDS_PER_CITIZEN) {
            return null;
        }
        // the repost pause is for things: a party forming again later asks again at once
        Long endedAt = proposal.kind().item() ? ended.get(key(citizenId, proposal.kind(), proposal.itemId())) : null;
        if (endedAt != null && nowMs - endedAt < config().NEEDS_REPOST_HOURS * HOUR_MS) {
            return null;
        }
        Need need = Need.open(citizenId, proposal.kind(), proposal.itemId(), proposal.quantity(), proposal.offer(),
                proposal.detail(), mapId, nowMs, deadlineMs);
        int id = store.insert(need);
        if (id <= 0) {
            return null;
        }
        need = need.withId(id);
        live.put(id, need);
        if (session != null && need.kind().item()) {
            session.journal.add("needs", "asked for " + what(need) + " (" + Market.formatPrice(need.offer()) + " ea)");
        }
        log.info("citizen {} needs {} {} x{} at {} ea ({})", citizenId, need.kind(), itemName(need.itemId()),
                need.quantity(), need.offer(), need.detail());
        return need;
    }

    private void replace(Need need) {
        live.put(need.id(), need);
        store.update(need);
    }

    private void close(Need need, Need.State end, long nowMs) {
        Need closed = need.closed(end, nowMs);
        if (closed.thanked() || end != Need.State.MET) {
            live.remove(need.id());
        } else {
            live.put(need.id(), closed);
        }
        store.update(closed);
        ended.put(key(need.citizenId(), need.kind(), need.itemId()), nowMs);
        log.info("citizen {} need {} {} ended: {}", need.citizenId(), need.kind(), itemName(need.itemId()), end);
    }

    // ---- gear wants (decision D4) -------------------------------------------------------------

    /**
     * From CitizenGear at a load: true to hold this free upgrade back as a want (one still open, or opened now for
     * NEEDS_GEAR_WANT_SHARE of picks the citizen never asked for); false to hand it over as before - its deadline passed
     * ("bought it elsewhere"), it asked before, its requests are full, or its purse cannot pay the index price. A want for
     * the same slot's older pick ends (the band moved on).
     */
    public boolean gearWant(Character agent, String slot, int pick, long nowMs) {
        if (!on() || agent == null) {
            return false;
        }
        start(nowMs);
        int citizenId = agent.getId();
        CitizenSession session = population.presence().session(citizenId);
        synchronized (lock(citizenId)) {
            for (Need need : of(citizenId)) {
                if (need.kind() != NeedKind.GEAR) {
                    continue;
                }
                if (need.itemId() == pick) {
                    if (need.state() == Need.State.ESCROW) {
                        return true; // handed in at a board: its escrow brings it after this catch-up
                    }
                    if (need.isOpen() && need.deadlineMs() > nowMs) {
                        return true;
                    }
                    if (need.isOpen()) {
                        close(need, Need.State.BOUGHT, nowMs);
                    }
                    return false;
                }
                if (need.isOpen() && slot.equals(need.detail())) {
                    close(need, Need.State.GONE, nowMs);
                }
            }
            Set<Integer> asked = gearAsked.computeIfAbsent(citizenId, id -> {
                Set<Integer> read = store.gearAsked(id);
                if (read == null) {
                    return null; // the read failed: no want this load, asked again at the next
                }
                Set<Integer> kept = ConcurrentHashMap.newKeySet();
                kept.addAll(read);
                return kept;
            });
            if (asked == null || asked.contains(pick)
                    || !NeedDerivation.wanted(citizenId, pick, config().NEEDS_GEAR_WANT_SHARE)) {
                return false;
            }
            long offer = population.market().price(pick);
            if (offer <= 0L || agent.getMeso() < offer) {
                return false;
            }
            Need need = post(citizenId, session, agent.getMapId(), new NeedDerivation.Proposal(NeedKind.GEAR, pick, 1, offer, slot),
                    deadline(NeedKind.GEAR, nowMs), nowMs);
            if (need == null) {
                return false;
            }
            asked.add(pick);
            return true;
        }
    }

    // ---- party seats ----------------------------------------------------------------------------

    /**
     * A citizen's forming party at {@code mapId} is short {@code more} seats (CitizenPartyQuests, once it starts calling
     * citizens over): one PARTY request per leader, its count and role kept current.
     */
    public void partyShort(CitizenSession leader, String label, int mapId, int more, String role, long nowMs) {
        if (!on() || leader == null || more <= 0) {
            return;
        }
        start(nowMs);
        String detail = label + "|" + (role == null ? "" : role);
        synchronized (lock(leader.characterId)) {
            List<Need> current = openOf(leader.characterId, NeedKind.PARTY);
            if (current.isEmpty()) {
                post(leader.characterId, leader, mapId, new NeedDerivation.Proposal(NeedKind.PARTY, 0, more, 0L, detail),
                        nowMs + config().PQ_FORM_PATIENCE_MS, nowMs);
                return;
            }
            Need need = current.get(0);
            if (need.quantity() != more || !need.detail().equals(detail)) {
                replace(new Need(need.id(), need.citizenId(), need.kind(), 0, more, 0, 0L, detail, mapId, need.state(), 0,
                        0L, 0, true, need.createdAtMs(), need.deadlineMs(), 0L));
            }
        }
    }

    /** The role a forming party lacks (a boss party without a cleric asks for one), or "" for "more". */
    public static String role(List<Integer> memberJobIds, boolean boss) {
        return NeedDerivation.role(memberJobIds, boss);
    }

    /** The leader's party went in, filled, or gave up: its PARTY request ends. */
    public void partyOver(int leaderId, long nowMs) {
        if (!on()) {
            return;
        }
        for (Need need : openOf(leaderId, NeedKind.PARTY)) {
            close(need, Need.State.GONE, nowMs);
        }
    }

    /** The citizen bought its boss potions at the store: its SUPPLY requests end. */
    public void suppliedForBoss(int citizenId, long nowMs) {
        if (!on()) {
            return;
        }
        synchronized (lock(citizenId)) {
            for (Need need : openOf(citizenId, NeedKind.SUPPLY)) {
                close(need, Need.State.BOUGHT, nowMs);
            }
        }
    }

    // ---- claims -----------------------------------------------------------------------------------

    /** The player holds the request for NEEDS_CLAIM_MINUTES, at most NEEDS_CLAIMS_PER_PLAYER at once. */
    public Claim claim(int playerId, int needId, long nowMs) {
        Need need = live.get(needId);
        if (need == null || !need.isOpen() || !need.kind().item()) {
            return Claim.GONE;
        }
        synchronized (lock(need.citizenId())) {
            need = live.get(needId);
            if (need == null || !need.isOpen()) {
                return Claim.GONE;
            }
            int holder = need.holder(nowMs);
            if (holder != 0 && holder != playerId) {
                return Claim.HELD;
            }
            if (holder != playerId && claimedBy(playerId, nowMs).size() >= config().NEEDS_CLAIMS_PER_PLAYER) {
                return Claim.LIMIT;
            }
            replace(need.withClaim(playerId, nowMs + config().NEEDS_CLAIM_MINUTES * MINUTE_MS));
            log.info("need {} of citizen {} claimed by player {}", needId, need.citizenId(), playerId);
            return Claim.OK;
        }
    }

    /** The player lets their hold go; false when they held nothing there. */
    public boolean drop(int playerId, int needId, long nowMs) {
        Need need = live.get(needId);
        if (need == null || need.holder(nowMs) != playerId) {
            return false;
        }
        synchronized (lock(need.citizenId())) {
            replace(need.withClaim(0, 0L));
        }
        return true;
    }

    // ---- hand-over ----------------------------------------------------------------------------------

    /** Units of the request's item the player carries that it takes. */
    public int carried(Character player, Need need) {
        return hands().carried(player, need);
    }

    /**
     * The player hands {@code count} units in: in person (the citizen loaded beside them) or at a board (a citizen away
     * gets them from escrow at its next load). The item goes into its inventory and it pays the offer from its purse in
     * one transaction; it remembers who helped (FULFILLED, once a day a way) and puts the item to use. Null when done,
     * else what to tell the player.
     */
    public String handOver(Character player, int needId, int count, boolean board, long nowMs) {
        Need need = live.get(needId);
        if (!on() || need == null) {
            return "That request is gone.";
        }
        String name = population.citizen(need.citizenId()) == null ? "They" : population.citizen(need.citizenId()).name();
        CitizenSession session = population.presence().session(need.citizenId());
        Character citizen = session != null && !session.virtual ? session.agent() : null;
        if (!board && citizen == null) {
            return name + " is not here.";
        }
        Need after;
        int given;
        synchronized (lock(need.citizenId())) {
            need = live.get(needId);
            if (need == null || !need.takesFrom(player.getId(), nowMs)) {
                return need != null && need.isOpen() ? "Someone else is bringing that." : "That request is gone.";
            }
            given = Math.min(count, need.remaining());
            if (given <= 0 || hands().carried(player, need) < given) {
                return need.kind() == NeedKind.GEAR ? "You have no " + itemName(need.itemId()) + " as good as a new one."
                        : "You don't have " + (given > 1 ? given + " of those." : "that.");
            }
            long pay = need.pay(given);
            if (hands().purse(need.citizenId(), citizen) < pay) {
                return name + " can't pay that right now.";
            }
            after = need.withDelivery(player.getId(), given, board, citizen == null, nowMs);
            String refused = hands().transfer(player, citizen, need, after, given, pay);
            if (refused != null) {
                return refused;
            }
            if (after.state() == Need.State.MET && after.thanked()) {
                live.remove(needId);
                ended.put(key(after.citizenId(), after.kind(), after.itemId()), nowMs);
            } else {
                live.put(needId, after);
            }
            if (citizen == null) {
                escrowHolders.add(need.citizenId());
            }
        }
        handedOver(player, session, citizen, after, given, board, nowMs);
        return null;
    }

    /** After a hand-over: the memory, the journal, the log, and in person (or loaded) its use and a word on map chat. */
    private void handedOver(Character player, CitizenSession session, Character citizen, Need need, int count,
                            boolean board, long nowMs) {
        String item = spoken(need.itemId());
        if (population.memories() != null) {
            population.memories().record(need.citizenId(), player.getId(),
                    board ? PersonMemory.Kind.FULFILLED_BOARD : PersonMemory.Kind.FULFILLED,
                    session == null ? need.mapId() : session.mapId, item, count);
        }
        log.info("{} handed {} x{} to citizen {} ({}) for {} meso", player.getName(), item, count, need.citizenId(),
                board ? citizen == null ? "board, escrow" : "board" : "in person", need.pay(count));
        if (session == null || citizen == null) {
            return;
        }
        session.journal.add("needs", "got " + (count > 1 ? count + " " + item : item) + " from " + player.getName());
        Boolean passed = need.state() == Need.State.MET || need.kind() == NeedKind.SUPPLY ? hands().use(citizen, need) : null;
        if (!board) {
            ChatterEngine.Event event = ChatterEngine.Event.of(passed == null ? "need.got" : passed ? "need.scroll.pass"
                    : "need.scroll.fail", session.mapId, session.channel).self(session).subject(player);
            slots(need, event);
            population.chatter().speakTo(event);
        }
    }

    /** Moves what waits in escrow into the loaded citizen's inventory; its ESCROW requests are met and put to use. */
    private void takeEscrow(CitizenSession session, Character agent, long nowMs) {
        int citizenId = session.characterId;
        if (!escrowHolders.contains(citizenId)) {
            return;
        }
        List<Need> escrowed = new ArrayList<>();
        synchronized (lock(citizenId)) {
            for (Need need : of(citizenId)) {
                if (need.state() == Need.State.ESCROW) {
                    escrowed.add(need);
                }
            }
            List<Integer> taken = hands().takeEscrow(agent, escrowed);
            if (taken == null) {
                return; // nothing moved: it waits for the next load
            }
            escrowHolders.remove(citizenId);
            for (Need need : escrowed) {
                Need met = need.closed(Need.State.MET, nowMs);
                live.put(met.id(), met); // still owes its thanks
                ended.put(key(citizenId, need.kind(), need.itemId()), nowMs);
            }
            log.info("citizen {} took {} items from escrow", session.name(), taken.size());
        }
        for (Need need : escrowed) {
            hands().use(agent, need);
        }
        session.journal.add("needs", "picked up what people left at the board");
    }

    // ---- market ----------------------------------------------------------------------------------------

    /**
     * The most the citizen pays a unit for this listing in a player's shop (an open, unclaimed GEAR or SCROLL want; an
     * equip base or better by the listing's {@code stats}), or 0.
     */
    public long marketOffer(int citizenId, int itemId, Map<String, Object> stats, long nowMs) {
        if (!on()) {
            return 0L;
        }
        for (Need need : openOf(citizenId, null)) {
            if ((need.kind() == NeedKind.GEAR || need.kind() == NeedKind.SCROLL) && need.itemId() == itemId
                    && need.holder(nowMs) == 0 && need.remaining() > 0) {
                return need.kind() == NeedKind.GEAR && !items.baseOrBetter(itemId, stats) ? 0L : need.offer();
            }
        }
        return 0L;
    }

    /** It bought what it wanted from {@code sellerId}'s shop: the request is met and it puts the item to use. */
    public void boughtAtMarket(CitizenSession session, Character agent, int itemId, int sellerId, long nowMs) {
        if (!on()) {
            return;
        }
        Need met = null;
        synchronized (lock(session.characterId)) {
            for (Need need : openOf(session.characterId, null)) {
                if (need.itemId() == itemId && (need.kind() == NeedKind.GEAR || need.kind() == NeedKind.SCROLL)) {
                    met = need.withDelivery(sellerId, need.remaining(), false, false, nowMs);
                    live.remove(need.id());
                    store.update(met);
                    ended.put(key(need.citizenId(), need.kind(), itemId), nowMs);
                    break;
                }
            }
        }
        if (met != null) {
            session.journal.add("needs", "found the " + itemName(itemId) + " it wanted in a shop");
            Boolean passed = hands().use(agent, met);
            if (passed != null) {
                ChatterEngine.Event event = ChatterEngine.Event.of(passed ? "need.scroll.pass" : "need.scroll.fail",
                        session.mapId, session.channel).self(session);
                slots(met, event);
                population.chatter().speakTo(event);
            }
        }
    }

    // ---- the minute ------------------------------------------------------------------------------------

    /**
     * Every minute on the population's social lane: requests past their deadline or of citizens gone (hired, retired)
     * end; loaded citizens' requests are derived again every NEEDS_REFRESH_MINUTES; owed thanks are whispered once both
     * are online; a citizen whispers its request to a player it likes on its map; the boards stand.
     */
    public void tick() {
        if (!on()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        try {
            start(nowMs);
            expire(nowMs);
            for (CitizenSession session : population.presence().sessions()) {
                Character agent = session.agent();
                if (session.virtual || agent == null) {
                    continue;
                }
                Long last = derivedAt.get(session.characterId);
                if (last == null || nowMs - last >= config().NEEDS_REFRESH_MINUTES * MINUTE_MS) {
                    derive(session, agent, nowMs);
                }
                thank(session, nowMs);
                whisper(session, agent, nowMs);
            }
            boards.ensure();
        } catch (RuntimeException failure) {
            log.warn("needs board tick failed", failure);
        }
    }

    void expire(long nowMs) {
        for (Need need : new ArrayList<>(live.values())) {
            Citizen citizen = population.citizen(need.citizenId());
            boolean retired = citizen == null || citizen.state() != Citizen.State.ACTIVE;
            boolean gone = retired || citizen.hired();
            if (retired && !need.isOpen()) { // it never loads again: its escrow stays with its character, no thanks come
                if (need.state() == Need.State.ESCROW) {
                    close(need, Need.State.GONE, nowMs);
                    escrowHolders.remove(need.citizenId());
                } else {
                    live.remove(need.id());
                }
            } else if (need.isOpen() && gone) {
                close(need, Need.State.GONE, nowMs);
            } else if (need.isOpen() && need.deadlineMs() > 0L && need.deadlineMs() <= nowMs) {
                close(need, need.kind() == NeedKind.GEAR ? Need.State.BOUGHT
                        : need.kind() == NeedKind.PARTY ? Need.State.GONE : Need.State.EXPIRED, nowMs);
            } else if (need.isOpen() && need.claimedBy() != 0 && need.holder(nowMs) == 0) {
                replace(need.withClaim(0, 0L)); // a hold that ran out lets go
            }
        }
    }

    /** Thanks owed for board hand-ins, whispered once it has what was left for it and the player is online. */
    private void thank(CitizenSession session, long nowMs) {
        if (escrowHolders.contains(session.characterId)) {
            return; // not picked up yet
        }
        for (Need need : of(session.characterId)) {
            if (need.thanked() || need.fulfilledBy() <= 0) {
                continue;
            }
            Character player = online(need.fulfilledBy());
            if (player == null) {
                continue;
            }
            ChatterEngine.Event event = ChatterEngine.Event.of("need.thanks", session.mapId, session.channel).self(session)
                    .subject(player);
            slots(need, event);
            String line = population.chatter().compose(event);
            if (line != null) {
                population.chatter().whisper(session, player, line);
            }
            Need thanked = need.thankedNow();
            if (thanked.isOpen()) {
                replace(thanked);
            } else {
                live.remove(need.id());
                store.update(thanked);
            }
            return; // one a minute
        }
    }

    /** One whisper of a request to a player it likes (warmth WARM or more) on its map, one per player per gap. */
    private void whisper(CitizenSession session, Character agent, long nowMs) {
        Need need = posting(session.characterId);
        MapleMap map = agent.getMap();
        if (need == null || map == null || population.memories() == null) {
            return;
        }
        long gap = config().NEEDS_WHISPER_GAP_MINUTES * MINUTE_MS;
        for (Character human : map.getAllPlayers()) {
            if (AgentPresence.isAgent(human) || human.isHidden() || need.holder(nowMs) != 0) {
                continue;
            }
            Long last = whisperedAt.get(human.getId());
            if (last != null && nowMs - last < gap
                    || population.memories().warmth(session.characterId, human.getId()) < PersonMemory.WARM) {
                continue;
            }
            ChatterEngine.Event event = ChatterEngine.Event.of("need.whisper", session.mapId, session.channel)
                    .self(session).subject(human);
            slots(need, event);
            String line = population.chatter().compose(event);
            if (line != null) {
                whisperedAt.put(human.getId(), nowMs);
                population.chatter().whisper(session, human, line);
                log.info("citizen {} whispered {} its need for {}", session.name(), human.getName(), itemName(need.itemId()));
            }
            return;
        }
    }

    private Character online(int playerId) {
        World world = population.gameWorld();
        return world == null ? null : world.getPlayerStorage().getCharacterById(playerId);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /** The equipped position of a CitizenGear slot name; 0 for an unknown one. */
    static short position(String slot) {
        Short position = SLOT_POSITIONS.get(slot);
        return position == null ? 0 : position;
    }

    static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException | NullPointerException ignored) {
            return 0;
        }
    }

    /** For !needs: every request of a citizen, one line each. */
    public List<String> describe(int citizenId, long nowMs) {
        List<String> out = new ArrayList<>();
        for (Need need : of(citizenId)) {
            int holder = need.holder(nowMs);
            out.add("#" + need.id() + " " + need.kind() + " " + what(need) + " for " + forWords(need) + " @ "
                    + Market.formatPrice(need.offer()) + " ea, " + need.state()
                    + (need.delivered() > 0 ? ", got " + need.delivered() : "")
                    + (holder != 0 ? ", held by " + holder : "")
                    + (need.deadlineMs() > 0 ? ", ends in " + Math.max(0, (need.deadlineMs() - nowMs) / MINUTE_MS) + " min" : ""));
        }
        return out;
    }

    /** For !needs and ops: requests live, open, in escrow. */
    public String report() {
        int open = 0;
        int escrow = 0;
        for (Need need : live.values()) {
            open += need.isOpen() ? 1 : 0;
            escrow += need.state() == Need.State.ESCROW ? 1 : 0;
        }
        return "needs: on=" + on() + " live=" + live.size() + " open=" + open + " escrow=" + escrow
                + " escrowHolders=" + escrowHolders.size();
    }

    /** For tests: the citizens whose escrow waits. */
    Set<Integer> escrowHolders() {
        return new HashSet<>(escrowHolders);
    }
}
