package server.living.roster.ui;

import client.Character;
import client.QuestStatus;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.life.MonsterDropEntry;
import server.life.MonsterInformationProvider;
import server.life.SpawnPoint;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.Content.StepType;
import server.living.population.Population;
import server.living.population.PopulationContent.Field;
import server.living.quest.QuestLedger;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.resident.ResidentSpots;
import server.living.roster.BenchLedger;
import server.living.roster.Commitments;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionView;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.roster.RosterVoice;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static server.living.roster.ui.CompanionMenus.BOARD_NPC;
import static server.living.roster.ui.CompanionMenus.NOTES_SPEAKER;
import static server.living.roster.ui.CompanionMenus.THINGS_CHANGED;
import static server.living.roster.ui.CompanionMenus.benchRow;
import static server.living.roster.ui.CompanionMenus.duty;
import static server.living.roster.ui.CompanionMenus.header;
import static server.living.roster.ui.CompanionMenus.levelJob;
import static server.living.roster.ui.CompanionMenus.onTheWay;
import static server.living.roster.ui.CompanionMenus.partyRow;
import static server.living.roster.ui.CompanionMenus.portrait;

/**
 * A companion's own screens (docs/BOT_MANAGEMENT_PLAN.md P1.5, P3 "Companion card", flows 1, 2 and 10): the card of one
 * in the owner's party (out with them, on its way, or waiting at a run's entrance), the bench card of one that is not
 * (benched, away on an errand, or parked), and the pages they open: the swap pickers, what it does at camp (with the
 * bench priors' projected rates; FARM_ITEM picks an item and a count), where it camps, its haul, its wages, the gear,
 * the trade and parting ways. The companion speaks on them under the portrait policy; the release confirm is the
 * player's notes. Each page is the answer to a click on the one before it, and a page opened from the card has Back.
 * Every option is stamped with the owner's view version: a click after their rows changed opens the card again with
 * "things changed" instead of acting. While the companion is tied to a run, bench, swap, errands and its camp read
 * "(after the run)" and cannot be picked. Reached only through {@link CompanionMenus#card}, which has checked that a
 * window may open; every action runs on companion-ops through {@link CompanionMenus#command}.
 */
final class CompanionCardScreens {
    /** How many fields the camp picker offers besides "Right here" (plan flow 2, "the FieldCatalog top 5"). */
    private static final int CAMP_CHOICES = 5;
    /** How many of the camp's drops the FARM_ITEM picker lists, the likeliest first. */
    private static final int DROP_CHOICES = 8;
    /** The FARM_ITEM count box: where it starts for an item no quest counts, and its top (0 = as many as it can). */
    private static final int FARM_COUNT_DEFAULT = 100;
    private static final int FARM_COUNT_MAX = 9_999;
    /** The wages box, in hours: where it starts, and the most paid ahead at once (a week). */
    private static final int WAGE_HOURS_DEFAULT = 2;
    private static final int WAGE_HOURS_MAX = 168;
    /** A disabled option's note while the companion is tied to a run (bench, swap, errands and camp wait for it). */
    private static final String AFTER_THE_RUN = " (after the run)";
    /** The duties done in a field: never set up in a town (RosterService.delegate refuses them there). */
    private static final Set<Assignment> FIELD_DUTIES = EnumSet.of(Assignment.TRAIN, Assignment.FARM_MESO,
            Assignment.FARM_ITEM, Assignment.GATHER);

    private final CompanionMenus menus;
    private final LivingWorld world;
    /** The game data the pickers read; a test swaps in its own. */
    Catalog catalog;

    CompanionCardScreens(CompanionMenus menus) {
        this.menus = menus;
        this.world = menus.world;
        this.catalog = new GameCatalog(world);
    }

    /**
     * What the pickers and the card read from the game's data, so no page guesses: the fields a level trains at, the
     * drops of a camp's monsters, the items the owner's quests still need, and the potions a companion carries.
     */
    interface Catalog {
        /** Up to {@link #CAMP_CHOICES} fields fitting {@code level}, the region of {@code nearMapId} first: map id to mob level. */
        Map<Integer, Integer> fields(int level, int nearMapId);

        /** True when the map is a field to hunt in, not a town. */
        boolean isField(int mapId);

        /** The ETC items the map's monsters drop, the likeliest first: item id to its best chance per million. */
        Map<Integer, Integer> drops(int mapId);

        /** The ETC items the owner's active quests (stock and Resident) still need: item id to how many. */
        Map<Integer, Integer> questNeeds(Character owner);

        /** "38 Red Potions": the HP potion the companion carries most of, "no potions", or empty when unknown. */
        String potions(Character bot);
    }

    // ---- the cards -------------------------------------------------------------------------

    /** The card of a companion in the owner's party, the bench card of a benched, away or parked one. */
    void show(Character owner, Persona persona, RosterEntry entry) {
        show(owner, persona, entry, "");
    }

    private void show(Character owner, Persona persona, RosterEntry entry, String note) {
        switch (entry.state()) {
            case BENCHED, AWAY, PARKED -> benchCard(owner, persona, entry, note);
            case RELEASED -> notInRoster(owner, persona);
            default -> card(owner, persona, entry, note);
        }
    }

    /**
     * ACTIVE, TRAVELLING or HOLD. Orders, the gear and the trade need it loaded and out with the owner; on its way it
     * shows the time left, and waiting at a run's entrance it can be asked to come.
     */
    private void card(Character owner, Persona persona, RosterEntry entry, String note) {
        CompanionView view = menus.view(owner, entry);
        Character bot = view.loaded() ? world.roster().companion(entry) : null;
        boolean active = entry.state() == RosterState.ACTIVE;
        boolean out = active && bot != null;
        boolean committed = view.committed();
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        if (out) {
            options.add(option(owner, stale, "Stick with me", () -> order(owner, persona, "menu.follow",
                    manager -> manager.followMe(owner, persona))));
            options.add(option(owner, stale, "Wait here", () -> order(owner, persona, "menu.wait",
                    manager -> manager.waitHere(owner, persona))));
            options.add(option(owner, stale, "Hunt here while I'm busy", () -> order(owner, persona, "menu.hunt",
                    manager -> manager.huntHere(owner, persona))));
        }
        if (entry.state() == RosterState.HOLD) {
            options.add(option(owner, stale, "Come join me", () -> join(owner, persona, entry)));
        }
        // the last one out is benched or sent off only by someone holding the Roster, which calls anyone back; else it
        // is rotated
        boolean mayLeave = othersOut(owner, persona) || menus.holdsRosterBook(owner);
        if (mayLeave) {
            options.add(unlessCommitted(committed, owner, stale, "Take a break at camp", () -> menus.confirmBench(owner,
                    persona, () -> menus.command(owner, "menu.bench",
                            () -> RosterVoice.line(persona, world.roster().bench(owner, persona))))));
        }
        if (active && !menus.rows(owner, false).isEmpty()) {
            options.add(unlessCommitted(committed, owner, stale, "Let someone take your place...",
                    () -> swapPicker(owner, persona, true, "who takes my place?")));
        }
        if (mayLeave || !active) { // one out with you leaves to do it now
            options.add(unlessCommitted(committed, owner, stale, "What you do at camp...", () -> dutyPicker(owner, persona)));
        }
        options.add(unlessCommitted(committed, owner, stale, "Camp somewhere else...", () -> campPicker(owner, persona, null)));
        options.add(option(owner, stale, "Your haul...", () -> haul(owner, persona)));
        options.add(option(owner, stale, "Your wages...", () -> wages(owner, persona)));
        if (out) {
            options.add(option(owner, stale, "Let me see your gear", () -> showGear(owner, persona)));
            options.add(option(owner, stale, "Let's trade", () -> order(owner, persona, "menu.trade",
                    manager -> manager.openTrade(owner, persona))));
        }
        options.add(option(owner, stale, "The whole crew...", () -> menus.crews().show(owner, persona)));
        options.add(option(owner, stale, "We should part ways", () -> partWays(owner, persona)));
        options.add(new Option("Never mind", null));
        String status = switch (entry.state()) {
            case TRAVELLING -> view.eta().isEmpty() ? "on the way\r\n" : "on the way (" + view.eta() + ")\r\n";
            case HOLD -> "waiting at the entrance\r\n";
            default -> "";
        };
        String text = note(note) + cardHeader(view, bot) + status + (committed ? "in a run with you\r\n" : "");
        LivingTalk.open(owner, portrait(persona)).menu(text, options).show();
    }

    /** BENCHED, AWAY (on an errand, its seat kept) or PARKED; one already called shows its way and is not called twice. */
    private void benchCard(Character owner, Persona persona, RosterEntry entry, String note) {
        CompanionView view = menus.view(owner, entry);
        boolean committed = view.committed();
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        if (view.travelMs() <= 0) {
            options.add(option(owner, stale, "Come join me", () -> join(owner, persona, entry)));
            if (!menus.rows(owner, true).isEmpty()) {
                options.add(unlessCommitted(committed, owner, stale, "Swap in for...",
                        () -> swapPicker(owner, persona, false, "who should i take over from?")));
            }
        }
        options.add(unlessCommitted(committed, owner, stale, "What you do at camp...", () -> dutyPicker(owner, persona)));
        options.add(unlessCommitted(committed, owner, stale, "Camp somewhere else...", () -> campPicker(owner, persona, null)));
        options.add(option(owner, stale, "Your haul...", () -> haul(owner, persona)));
        options.add(option(owner, stale, "Your wages...", () -> wages(owner, persona)));
        options.add(option(owner, stale, "We should part ways", () -> partWays(owner, persona)));
        options.add(new Option("Never mind", null));
        String status = view.travelMs() > 0 ? onTheWay(view) : duty(view);
        String more = (view.progress().isEmpty() ? "" : view.progress() + "\r\n")
                + (view.haul().isEmpty() ? "" : "haul: " + view.haul() + "\r\n");
        LivingTalk.open(owner, portrait(persona)).menu(note(note) + header(view) + status + "\r\n" + more, options).show();
    }

    /** "#eHadrik#n, Lv27 Hunter - HP 812/900 - 38 Red Potions", then a line break; the plain header while it is not loaded. */
    private String cardHeader(CompanionView view, Character bot) {
        if (bot == null || !view.loaded()) {
            return header(view);
        }
        String potions = catalog.potions(bot);
        return "#e" + view.name() + "#n" + levelJob(view) + " - HP " + view.hp() + "/" + view.maxHp()
                + (potions.isEmpty() ? "" : " - " + potions) + "\r\n";
    }

    /**
     * With no seat for it the companion asks whom to take over from; it is never sent for a seat that is not there.
     * In a shared party the open seats may be a friend's share (lending them is P9) or held empty by the leader's
     * expel (the leader cap), and it says which; with the leader cap and nobody out to take over from, it stays.
     */
    private void join(Character owner, Persona persona, RosterEntry entry) {
        if (!world.roster().canSeat(owner, entry)) {
            boolean full = world.slots().occupied(owner) >= RosterService.PARTY_CAP;
            boolean leaderCapped = !full && world.slots().leaderCapped(owner);
            if (leaderCapped && menus.rows(owner, true).isEmpty()) {
                LivingTalk.open(owner, portrait(persona)).ok(RosterService.LEADER_CAP_LINE).show();
                return;
            }
            swapPicker(owner, persona, false, full ? "your party's full. who should i take over from?"
                    : leaderCapped ? "the leader sent one of us out. who should i take over from?"
                    : "the other seats are your friend's. who should i take over from?");
            return;
        }
        menus.command(owner, "menu.call", () -> RosterVoice.line(persona, world.roster().call(owner, persona)));
    }

    /**
     * {@code outgoing}: {@code fixed} is out with you and a bench companion takes its place; otherwise {@code fixed}
     * comes in for one that is out. Both are {@link RosterService#swap}(out, in). {@code prompt} is {@code fixed}'s line.
     */
    private void swapPicker(Character owner, Persona fixed, boolean outgoing, String prompt) {
        Runnable stale = () -> reopen(owner, fixed, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner, !outgoing)) {
            Persona other = world.content().persona(entry.personaId());
            if (other == null) {
                continue;
            }
            CompanionView view = menus.view(owner, entry);
            Persona out = outgoing ? fixed : other;
            Persona in = outgoing ? other : fixed;
            options.add(option(owner, stale, outgoing ? benchRow(view) : partyRow(view), () -> menus.confirmBench(owner, out,
                    () -> menus.command(owner, "menu.swap", () -> RosterVoice.line(fixed, world.roster().swap(owner, out, in))))));
        }
        if (options.isEmpty()) {
            menus.outcome(owner, "Nobody to swap with right now.");
            return;
        }
        LivingTalk.open(owner, portrait(fixed)).menu(prompt + "\r\n", options).back(() -> reopen(owner, fixed, "")).show();
    }

    /** The stock character info, as double-clicking a stranger shows it, of the companion as it is out now. */
    private void showGear(Character owner, Persona persona) {
        RosterResult shown = menus.manager().showGear(owner, persona);
        if (!shown.ok()) {
            menus.outcome(owner, persona.name() + " is not out with you right now.");
        }
    }

    // ---- what it does at camp (plan flow 2) -------------------------------------------------

    /**
     * Each duty with its projected hour at the bench priors. One out with the owner leaves to do it now and keeps its
     * seat (AWAY); any other has it set. A field duty with a town for a camp asks where first.
     */
    private void dutyPicker(Character owner, Persona persona) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        int level = view.level() > 0 ? view.level() : owner.getLevel();
        BenchLedger bench = world.bench();
        List<Option> options = new ArrayList<>();
        for (Assignment duty : Assignment.values()) {
            BenchLedger.Rates rates = bench == null ? null : bench.rates(persona, view.bond(), level, owner.getLevel(), duty);
            options.add(option(owner, stale, dutyLabel(duty, rates), () -> chooseDuty(owner, persona, duty)));
        }
        String prompt = (entry.state() == RosterState.ACTIVE ? "what should i go do? i'll keep my seat.\r\n"
                : "what should i do at camp?\r\n")
                + "now: " + view.duty() + (view.camp().isEmpty() ? "" : " @ " + view.camp()) + "\r\nupkeep: " + (int) Math.round(BenchLedger.upkeepPerHour(level))
                + " meso an hour, none while i rest\r\n";
        LivingTalk.open(owner, portrait(persona)).menu(prompt, options).back(() -> reopen(owner, persona, "")).show();
    }

    /** "Train (~32.4% EXP/h)", "Farm meso (~4.1k meso/h after upkeep)", "Rest (no upkeep)". */
    static String dutyLabel(Assignment duty, BenchLedger.Rates rates) {
        return switch (duty) {
            case TRAIN -> "Train" + (rates == null ? "" : rates.expPercent() > 0.0d
                    ? String.format(Locale.ROOT, " (~%.1f%% EXP/h)", rates.expPercent()) : " (keeping pace with you)");
            case FARM_MESO -> "Farm meso" + (rates == null ? "" : " (~" + kilo(rates.meso() - rates.upkeep()) + " meso/h after upkeep)");
            case FARM_ITEM -> "Farm an item...";
            case GATHER -> "Gather for your quests";
            case SELL -> "Sell the haul";
            case STALL -> "Run a stall";
            case REST -> "Rest (no upkeep)";
        };
    }

    /** "4.1k", "850". */
    static String kilo(long meso) {
        return Math.abs(meso) >= 1_000L ? String.format(Locale.ROOT, "%.1fk", meso / 1_000.0d) : Long.toString(meso);
    }

    private void chooseDuty(Character owner, Persona persona, Assignment duty) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        if (FIELD_DUTIES.contains(duty) && !catalog.isField(campOf(entry, persona))) {
            campPicker(owner, persona, duty); // its camp is a town: where to do it, first
            return;
        }
        dutyAt(owner, persona, duty, 0);
    }

    /** The duty at {@code camp} (0: where it camps now); FARM_ITEM first asks what and how many. */
    private void dutyAt(Character owner, Persona persona, Assignment duty, int camp) {
        if (duty == Assignment.FARM_ITEM) {
            itemPicker(owner, persona, camp);
            return;
        }
        assign(owner, persona, duty, 0, 0, camp);
    }

    /**
     * FARM_ITEM: the ETC drops of the camp's monsters (icon and name, the likeliest first, with the items an hour
     * brings), then what the owner's quests still need. A pick asks how many in the stock number box.
     */
    private void itemPicker(Character owner, Persona persona, int camp) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        int at = camp != 0 ? camp : campOf(entry, persona);
        Map<Integer, Integer> drops = catalog.drops(at);
        Map<Integer, Integer> needs = catalog.questNeeds(owner);
        BenchLedger bench = world.bench();
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        for (Map.Entry<Integer, Integer> drop : drops.entrySet()) {
            int itemId = drop.getKey();
            Integer need = needs.get(itemId);
            String rate = bench == null ? "" : String.format(Locale.ROOT, " (~%.1f/h)", bench.itemsPerHour(drop.getValue()));
            options.add(option(owner, stale, itemLabel(itemId) + rate + (need == null ? "" : " - you need " + need),
                    () -> farmCount(owner, persona, itemId, need != null ? need : entry.assignmentItem() == itemId
                            && entry.assignmentCount() > 0 ? entry.assignmentCount() : FARM_COUNT_DEFAULT, camp)));
        }
        boolean heading = false;
        for (Map.Entry<Integer, Integer> need : needs.entrySet()) {
            if (drops.containsKey(need.getKey())) {
                continue;
            }
            if (!heading) {
                options.add(Option.heading("#eFor your quests:#n"));
                heading = true;
            }
            int itemId = need.getKey();
            int count = need.getValue();
            options.add(option(owner, stale, itemLabel(itemId) + " - you need " + count,
                    () -> farmCount(owner, persona, itemId, count, camp)));
        }
        String where = menus.placeNames.apply(at);
        String prompt = options.isEmpty() ? "nothing worth farming at " + where + " that i know of.\r\n"
                : "what should i farm at " + where + "?\r\n";
        LivingTalk.open(owner, portrait(persona)).menu(prompt, options).back(() -> dutyPicker(owner, persona)).show();
    }

    /** "#i4000021# #t4000021#": the item's icon and name. */
    private static String itemLabel(int itemId) {
        return "#i" + itemId + "# #t" + itemId + "#";
    }

    /** How many of the item, in the stock number box (0: as many as it can); its cancel path goes back to the items. */
    private void farmCount(Character owner, Persona persona, int itemId, int def, int camp) {
        LivingTalk.open(owner, portrait(persona))
                .number("how many #t" + itemId + "#? (0: as many as i can)", def, 0, FARM_COUNT_MAX,
                        count -> assign(owner, persona, Assignment.FARM_ITEM, itemId, count, camp))
                .back(() -> itemPicker(owner, persona, camp))
                .show();
    }

    /**
     * Sets the duty on companion-ops (moving the camp first when one was picked): one out with the owner leaves now
     * and keeps its seat (CompanionManager.assign delegates), any other has it set. Mid-run it waits: "after the run".
     */
    private void assign(Character owner, Persona persona, Assignment duty, int itemId, int count, int camp) {
        menus.command(owner, "menu.duty", () -> {
            if (committedNow(persona)) {
                return RosterVoice.line(persona, new RosterResult.InEventInstance());
            }
            CompanionManager manager = menus.manager();
            RosterEntry before = world.roster().entry(owner.getId(), persona.id());
            if (camp != 0) {
                RosterResult moved = manager.setCamp(owner, persona, camp);
                if (!moved.ok()) {
                    return RosterVoice.line(persona, moved);
                }
            }
            RosterResult done = manager.assign(owner, persona, duty, itemId, count);
            RosterEntry now = world.roster().entry(owner.getId(), persona.id());
            if (!(done instanceof RosterResult.Ok) || now == null) {
                return RosterVoice.line(persona, done);
            }
            String what = world.roster().describeAssignment(now) + " @ " + menus.placeNames.apply(campOf(now, persona));
            boolean leaves = before != null && before.state() == RosterState.ACTIVE && now.state() == RosterState.AWAY;
            return leaves ? persona.name() + " leaves to " + what + " and keeps the seat."
                    : persona.name() + " will " + what + ".";
        });
    }

    // ---- where it camps ---------------------------------------------------------------------

    /**
     * The fields fitting its level (plan flow 2, the FieldCatalog top 5), those in the owner's region first, and
     * "Right here". {@code then} null changes only the camp; otherwise the camp is for that duty, which follows, and
     * only fields are offered.
     */
    private void campPicker(Character owner, Persona persona, Assignment then) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        int level = view.level() > 0 ? view.level() : owner.getLevel();
        int current = campOf(entry, persona);
        int here = owner.getMapId();
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        for (Map.Entry<Integer, Integer> field : catalog.fields(level, here).entrySet()) {
            int mapId = field.getKey();
            options.add(option(owner, stale, menus.placeNames.apply(mapId) + " (Lv" + field.getValue() + " monsters)"
                    + (mapId == current ? " - my camp now" : ""), () -> pickCamp(owner, persona, then, mapId)));
        }
        if (here > 0 && (then == null || catalog.isField(here))) {
            options.add(option(owner, stale, "Right here (" + menus.placeNames.apply(here) + ")",
                    () -> pickCamp(owner, persona, then, here)));
        }
        String prompt = then == null ? "where should i camp? now: " + menus.placeNames.apply(current) + "\r\n"
                : options.isEmpty() ? "i need a field for that. take me to one and ask me here again.\r\n"
                : "i can't do that in town. where should i go?\r\n";
        LivingTalk.open(owner, portrait(persona)).menu(prompt, options)
                .back(() -> {
                    if (then == null) {
                        reopen(owner, persona, "");
                    } else {
                        dutyPicker(owner, persona);
                    }
                })
                .show();
    }

    private void pickCamp(Character owner, Persona persona, Assignment then, int mapId) {
        if (then != null) {
            dutyAt(owner, persona, then, mapId);
            return;
        }
        menus.command(owner, "menu.camp", () -> {
            if (committedNow(persona)) {
                return RosterVoice.line(persona, new RosterResult.InEventInstance());
            }
            RosterResult moved = menus.manager().setCamp(owner, persona, mapId);
            return moved.ok() ? persona.name() + " will camp at " + menus.placeNames.apply(mapId) + "."
                    : RosterVoice.line(persona, moved);
        });
    }

    // ---- haul, wages, parting ways ------------------------------------------------------------

    /** What it carries, and what to do with it: hand it over, send it by Duey, bank the meso, or wear the best of it. */
    private void haul(Character owner, Persona persona) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        List<HaulItem> items = world.store().haul(owner.getId(), persona.id());
        StringBuilder text = new StringBuilder("my haul:");
        if (view.haulMeso() > 0) {
            text.append(String.format(Locale.ROOT, "\r\n%,d meso", view.haulMeso()));
        } else if (view.haulMeso() < 0) {
            text.append(String.format(Locale.ROOT, "\r\ni owe %,d meso of upkeep", -view.haulMeso()));
        }
        boolean gear = false;
        for (HaulItem item : items) {
            text.append("\r\n#t").append(item.itemId()).append("# x").append(item.quantity());
            gear |= ItemConstants.isEquipment(item.itemId());
        }
        if (items.isEmpty() && view.haulMeso() == 0) {
            text.append(" nothing yet.");
        }
        text.append("\r\n");
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        if (!items.isEmpty() || view.haulMeso() > 0) {
            options.add(option(owner, stale, "Hand it over", () -> menus.command(owner, "menu.haul.hand_over",
                    () -> world.roster().handOver(owner, persona))));
            options.add(option(owner, stale, "Send it by Duey", () -> menus.command(owner, "menu.haul.duey",
                    () -> world.roster().sendIt(owner, persona))));
        }
        if (view.haulMeso() > 0) {
            options.add(option(owner, stale, "Bank the meso", () -> menus.command(owner, "menu.haul.bank",
                    () -> world.roster().bankIt(owner, persona))));
        }
        if (gear && entry.state() == RosterState.ACTIVE && view.loaded()) {
            options.add(option(owner, stale, "Wear your best from the haul", () -> menus.command(owner, "menu.haul.wear_best",
                    () -> RosterVoice.line(persona, menus.manager().wearBestFromHaul(owner, persona)))));
        }
        LivingTalk.open(owner, portrait(persona)).menu(text.toString(), options).back(() -> reopen(owner, persona, "")).show();
    }

    /** Whether it is paid up, its upkeep, and paying hours ahead in the stock number box (decision 19 (b)). */
    private void wages(Character owner, Persona persona) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        long upkeep = Math.max(1L, Math.round(BenchLedger.upkeepPerHour(view.level() > 0 ? view.level() : 1)));
        long affordable = Math.min(WAGE_HOURS_MAX, owner.getMeso() / upkeep);
        Runnable stale = () -> reopen(owner, persona, THINGS_CHANGED);
        List<Option> options = new ArrayList<>();
        if (affordable >= 1) {
            options.add(option(owner, stale, "Pay your wages...", () -> LivingTalk.open(owner, portrait(persona))
                    .number(String.format(Locale.ROOT, "how many hours? %,d meso an hour; you have %,d.", upkeep, owner.getMeso()),
                            (int) Math.min(WAGE_HOURS_DEFAULT, affordable), 1, (int) affordable,
                            hours -> menus.command(owner, "menu.wages",
                                    () -> world.roster().payWages(owner, persona, hours * upkeep)))
                    .back(() -> wages(owner, persona))
                    .show()));
        } else {
            options.add(Option.heading(String.format(Locale.ROOT, "(an hour is %,d meso)", upkeep)));
        }
        String text = "i'm " + view.wages() + String.format(Locale.ROOT, ".\r\nmy upkeep is %,d meso an hour; paid ahead,"
                + " it doesn't come out of the haul.\r\n", upkeep);
        LivingTalk.open(owner, portrait(persona)).menu(text, options).back(() -> reopen(owner, persona, "")).show();
    }

    /**
     * "We should part ways" (plan flow 10): a Yes/No in the player's notes with the bond and the haul; Yes hands the
     * haul over (Duey takes what does not fit), then the companion says goodbye and walks out, or goes home after the run.
     */
    private void partWays(Character owner, Persona persona) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        String haul = view.haul().isEmpty() ? "nothing" : view.haul() + ", handed over first (Duey takes what doesn't fit)";
        String text = "Part ways with " + persona.name() + " for good?\r\n\r\nBond: " + bondName(view.bond()) + "\r\nHaul: "
                + haul + (view.committed() ? "\r\n" + persona.name() + " finishes the run first." : "");
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER)
                .yesNo(text, menus.ifUnchanged(owner, () -> menus.command(owner, "menu.release",
                        () -> RosterVoice.line(persona, menus.manager().release(owner, persona))),
                        () -> reopen(owner, persona, THINGS_CHANGED)), null)
                .show();
    }

    /** "Trusted"; "none yet" with no bond kept. */
    static String bondName(BondStage stage) {
        if (stage == null || stage == BondStage.NONE) {
            return "none yet";
        }
        String name = stage.name().toLowerCase(Locale.ROOT);
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** An option stamped with the owner's view version: picked after their rows changed, {@code stale} runs instead. */
    private Option option(Character owner, Runnable stale, String label, Runnable action) {
        return new Option(label, menus.ifUnchanged(owner, action, stale));
    }

    /** The option, or while the companion is tied to a run its label with "(after the run)" as a line that cannot be picked. */
    private Option unlessCommitted(boolean committed, Character owner, Runnable stale, String label, Runnable action) {
        return committed ? Option.heading(label.replace("...", "") + AFTER_THE_RUN) : option(owner, stale, label, action);
    }

    /**
     * An order the companion answers in its own words (its whisper or bubble), or a trade whose window is the answer:
     * the notes open only when it could not be done.
     */
    private void order(Character owner, Persona persona, String name, Function<CompanionManager, RosterResult> verb) {
        menus.command(owner, name, () -> {
            RosterResult result = verb.apply(menus.manager());
            return result.ok() ? "" : RosterVoice.line(persona, result);
        });
    }

    /** The card again, read afresh (a line such as "things changed" on top), as the answer to the click. */
    private void reopen(Character owner, Persona persona, String note) {
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        show(owner, persona, entry, note);
    }

    private void notInRoster(Character owner, Persona persona) {
        menus.outcome(owner, persona.name() + " is not in your roster.");
    }

    private static String note(String note) {
        return note.isEmpty() ? "" : note + "\r\n";
    }

    /** The owner's row of the persona, from the view cache; null when it is not (or no longer) theirs. */
    private RosterEntry row(Character owner, Persona persona) {
        for (RosterEntry entry : menus.rows(owner)) {
            if (entry.personaId().equals(persona.id()) && entry.state() != RosterState.RELEASED) {
                return entry;
            }
        }
        return null;
    }

    /** True when someone of the owner's besides {@code persona} is out with them. */
    private boolean othersOut(Character owner, Persona persona) {
        for (RosterEntry entry : menus.rows(owner, true)) {
            if (!entry.personaId().equals(persona.id())) {
                return true;
            }
        }
        return false;
    }

    /** Where it camps: its camp, else its home town (a new Resident's camp until one is picked). */
    private static int campOf(RosterEntry entry, Persona persona) {
        return entry.campMapId() != 0 || persona.homeTown() == null ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
    }

    /** Tied to a run now (checked again on companion-ops: the card may be older than the commitment). */
    private boolean committedNow(Persona persona) {
        Commitments commitments = world.commitments();
        int characterId = world.residents().characterIdOf(persona.id());
        return commitments != null && characterId != 0 && commitments.isCommitted(characterId);
    }

    // ---- the game's data ----------------------------------------------------------------------

    /** The catalog over the game's own data: the population's fields, the map's spawns, the drop table, the quests. */
    static final class GameCatalog implements Catalog {
        private final LivingWorld world;

        GameCatalog(LivingWorld world) {
            this.world = world;
        }

        @Override
        public Map<Integer, Integer> fields(int level, int nearMapId) {
            Population population = Population.get();
            Map<Integer, Integer> out = new LinkedHashMap<>();
            if (population != null) {
                for (Field field : population.fields().fieldsFor(level, nearMapId, CAMP_CHOICES)) {
                    out.put(field.mapId(), field.mobLevel());
                }
            }
            return out;
        }

        @Override
        public boolean isField(int mapId) {
            Population population = Population.get();
            return mapId > 0 && (population == null || !population.fields().isTown(mapId));
        }

        @Override
        public Map<Integer, Integer> drops(int mapId) {
            MapleMap map = world.residents().map(mapId);
            if (map == null) {
                return Map.of();
            }
            Set<Integer> mobs = new LinkedHashSet<>();
            for (SpawnPoint spawn : map.getMonsterSpawn()) {
                mobs.add(spawn.getMonsterId());
            }
            Map<Integer, Integer> best = new HashMap<>();
            MonsterInformationProvider drops = MonsterInformationProvider.getInstance();
            for (int mob : mobs) {
                for (MonsterDropEntry drop : drops.retrieveDrop(mob)) {
                    if (drop.itemId > 0 && drop.chance > 0 && drop.questid <= 0
                            && ItemConstants.getInventoryType(drop.itemId) == InventoryType.ETC) {
                        best.merge(drop.itemId, drop.chance, Math::max);
                    }
                }
            }
            Map<Integer, Integer> out = new LinkedHashMap<>();
            best.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()).limit(DROP_CHOICES)
                    .forEach(drop -> out.put(drop.getKey(), drop.getValue()));
            return out;
        }

        @Override
        public Map<Integer, Integer> questNeeds(Character owner) {
            Map<Integer, Integer> needs = new LinkedHashMap<>();
            for (QuestStatus status : owner.getStartedQuests()) {
                server.quest.Quest quest = status.getQuest();
                if (quest == null) {
                    continue;
                }
                for (Map.Entry<Integer, Integer> need : quest.getCompleteItemRequirements().entrySet()) {
                    int left = need.getValue() - owner.getItemQuantity(need.getKey(), false);
                    if (left > 0 && ItemConstants.getInventoryType(need.getKey()) == InventoryType.ETC) {
                        needs.merge(need.getKey(), left, Math::max);
                    }
                }
            }
            QuestLedger quests = world.quests();
            for (QuestState state : quests == null ? List.<QuestState>of() : quests.active(owner)) {
                Content.Quest quest = world.content().quest(state.questId());
                Content.Step step = quest == null ? null : quest.step(state.stepIndex());
                int left = step == null ? 0 : step.count() - state.progress();
                if (step != null && step.type() == StepType.COLLECT_ITEM && step.itemId() > 0 && left > 0) {
                    needs.merge(step.itemId(), left, Math::max);
                }
            }
            return needs;
        }

        @Override
        public String potions(Character bot) {
            Inventory use = bot.getInventory(InventoryType.USE);
            if (use == null) {
                return "";
            }
            Map<Integer, Integer> counts = new HashMap<>();
            for (Item item : use.list()) {
                StatEffect effect = AgentUseItemClassificationPolicy.itemEffect(item.getItemId());
                if (item.getQuantity() > 0 && AgentUseItemClassificationPolicy.isRecoveryPotion(effect)
                        && (effect.getHp() > 0 || effect.getHpRate() > 0)) {
                    counts.merge(item.getItemId(), (int) item.getQuantity(), Integer::sum);
                }
            }
            return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(most -> potionCount(most.getValue(), CompanionView.itemName(most.getKey()))).orElse("no potions");
        }
    }

    /** "38 Red Potions", "1 Elixir". */
    static String potionCount(int count, String name) {
        return count + " " + name + (count == 1 || name.endsWith("s") ? "" : "s");
    }
}
