package server.living.roster.ui;

import client.Character;
import client.QuestStatus;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.looting.AgentLootPolicy;
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
import server.living.roster.CompanionSettings;
import server.living.roster.CompanionSettings.Support;
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
import java.util.concurrent.ConcurrentHashMap;
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
 * bench priors' projected rates; FARM_ITEM picks an item and a count), where it camps, its fighting style and its
 * supplies and loot (P3b), its haul, its wages, the gear, the trade and parting ways. The companion speaks on them
 * under the portrait policy; the release confirm is the player's notes. Each page is the answer to a click on the one
 * before it, and a page opened from the card has Back;
 * a card opened from a Crew window row has Back to that window. Every option that acts is stamped with the owner's
 * view version, taken before the screen read its rows: a click after their rows changed opens the card again with
 * "things changed" instead of acting (an option that only opens a page reads it afresh). While the companion is tied
 * to a run, bench, swap, errands and its camp read "(after the run)" and cannot be picked. Reached only through
 * {@link CompanionMenus#card}, which has checked that a window may open; every action runs on companion-ops through
 * {@link CompanionMenus#command}, and its line comes back on top of the card.
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
    /** A setting's note on its pick while it is the companion's setting now. */
    private static final String NOW = " - now";
    /** A disabled option's note while the companion is tied to a run (bench, swap, errands and camp wait for it). */
    private static final String AFTER_THE_RUN = " (after the run)";
    /** The duties done in a field: never set up in a town (RosterService.delegate refuses them there). */
    private static final Set<Assignment> FIELD_DUTIES = EnumSet.of(Assignment.TRAIN, Assignment.FARM_MESO,
            Assignment.FARM_ITEM, Assignment.GATHER);

    private final CompanionMenus menus;
    private final LivingWorld world;
    /**
     * Per owner, the Crew window the card on screen was opened from, for its Back: the persona id whose voice the window
     * had, or "" for the player's notes. None for a card opened by a double-click.
     */
    private final Map<Integer, String> crewParents = new ConcurrentHashMap<>();
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

        /** Every HP and MP potion the companion carries, the most first ("38 Red Potions"); empty for none. */
        List<String> allPotions(Character bot);
    }

    // ---- the cards -------------------------------------------------------------------------

    /**
     * The card of a companion in the owner's party, the bench card of a benched, away or parked one, read afresh.
     * {@code fromCrew}: opened from a row of the Crew window {@code crewVoice} spoke (null: the player's notes), which
     * its Back re-opens; otherwise (a double-click) it has none.
     */
    void show(Character owner, Persona persona, boolean fromCrew, Persona crewVoice) {
        if (fromCrew) {
            crewParents.put(owner.getId(), crewVoice == null ? "" : crewVoice.id());
        } else {
            crewParents.remove(owner.getId());
        }
        reopen(owner, persona, "");
    }

    /**
     * A card action's line, as the answer to the click: the card again, read afresh, with the line on top; the notes'
     * last page once the companion is no longer in the roster (parted ways).
     */
    void answer(Character owner, Persona persona, String line) {
        if (row(owner, persona) == null) {
            menus.outcome(owner, line);
            return;
        }
        reopen(owner, persona, line);
    }

    /**
     * ACTIVE, TRAVELLING or HOLD. Orders need it loaded and out with the owner, the gear and the trade on the owner's
     * map too; on its way it shows the time left, and waiting at a run's entrance it can be asked to come.
     */
    private void card(Character owner, Persona persona, RosterEntry entry, String note, Stamp stamp) {
        CompanionView view = menus.view(owner, entry);
        Character bot = view.loaded() ? world.roster().companion(entry) : null;
        boolean active = entry.state() == RosterState.ACTIVE;
        boolean out = active && bot != null;
        boolean here = out && bot.getMap() != null && bot.getMap() == owner.getMap(); // the stock windows need it in view
        boolean committed = view.committed();
        List<Option> options = new ArrayList<>();
        if (out) { // an order reaches it as it is when given (the manager checks), so it needs no stamp
            options.add(new Option("Stick with me", () -> order(owner, persona, "menu.follow",
                    manager -> manager.followMe(owner, persona))));
            options.add(new Option("Wait here", () -> order(owner, persona, "menu.wait",
                    manager -> manager.waitHere(owner, persona))));
            options.add(new Option("Hunt here while I'm busy", () -> order(owner, persona, "menu.hunt",
                    manager -> manager.huntHere(owner, persona))));
        }
        if (entry.state() == RosterState.HOLD) {
            options.add(stamp.option("Come join me", () -> join(owner, persona, entry)));
        }
        // the last one out is benched or sent off only by someone holding the Roster, which calls anyone back; else it
        // is rotated
        boolean mayLeave = othersOut(owner, persona) || menus.holdsRosterBook(owner);
        if (mayLeave) {
            options.add(unlessCommitted(committed, stamp.option("Take a break at camp", () -> menus.confirmBench(owner,
                    persona, () -> menus.command(owner, persona, "menu.bench",
                            () -> RosterVoice.line(persona, world.roster().bench(owner, persona)))))));
        }
        if (active && !swapIns(owner).isEmpty()) {
            options.add(unlessCommitted(committed, new Option("Let someone take your place...",
                    () -> swapPicker(owner, persona, true, "who takes my place?"))));
        }
        if (mayLeave || !active) { // one out with you leaves to do it now
            options.add(unlessCommitted(committed, new Option("What you do at camp...", () -> dutyPicker(owner, persona))));
        }
        options.add(unlessCommitted(committed, new Option("Camp somewhere else...", () -> campPicker(owner, persona, null))));
        options.add(new Option("Fighting style...", () -> fightingStyle(owner, persona, "")));
        options.add(new Option("Supplies & loot...", () -> supplies(owner, persona, "")));
        options.add(new Option("The whole crew...", () -> menus.crews().show(owner, persona)));
        options.add(new Option("Your haul...", () -> haul(owner, persona)));
        options.add(new Option("Your wages...", () -> wages(owner, persona)));
        if (here) {
            options.add(new Option("Let me see your gear", () -> showGear(owner, persona)));
            options.add(new Option("Let's trade", () -> {
                long openBy = System.currentTimeMillis() + CompanionMenus.LATE_OUTCOME_MS; // never long after the click
                order(owner, persona, "menu.trade", manager -> manager.openTrade(owner, persona, openBy));
            }));
        }
        options.add(new Option("We should part ways", () -> partWays(owner, persona)));
        String status = switch (entry.state()) {
            case TRAVELLING -> view.eta().isEmpty() ? "on the way\r\n" : "on the way (" + view.eta() + ")\r\n";
            case HOLD -> "waiting at the entrance\r\n";
            default -> "";
        };
        String text = note(note) + cardHeader(view, bot) + status + (committed ? "in a run with you\r\n" : "");
        LivingTalk.open(owner, portrait(persona)).menu(text, options).back(crewBack(owner)).show();
    }

    /** BENCHED, AWAY (on an errand, its seat kept) or PARKED; one already called shows its way and is not called twice. */
    private void benchCard(Character owner, Persona persona, RosterEntry entry, String note, Stamp stamp) {
        CompanionView view = menus.view(owner, entry);
        boolean committed = view.committed();
        List<Option> options = new ArrayList<>();
        if (view.travelMs() <= 0) {
            options.add(stamp.option("Come join me", () -> join(owner, persona, entry)));
            if (!menus.rows(owner, true).isEmpty()) {
                options.add(unlessCommitted(committed, new Option("Swap in for...",
                        () -> swapPicker(owner, persona, false, "who should i take over from?"))));
            }
        }
        options.add(unlessCommitted(committed, new Option("What you do at camp...", () -> dutyPicker(owner, persona))));
        options.add(unlessCommitted(committed, new Option("Camp somewhere else...", () -> campPicker(owner, persona, null))));
        options.add(new Option("Fighting style...", () -> fightingStyle(owner, persona, "")));
        options.add(new Option("Supplies & loot...", () -> supplies(owner, persona, "")));
        options.add(new Option("Your haul...", () -> haul(owner, persona)));
        options.add(new Option("Your wages...", () -> wages(owner, persona)));
        options.add(new Option("We should part ways", () -> partWays(owner, persona)));
        String status = view.travelMs() > 0 ? onTheWay(view) : duty(view);
        String more = (view.progress().isEmpty() ? "" : view.progress() + "\r\n")
                + (view.haul().isEmpty() ? "" : "haul: " + view.haul() + "\r\n");
        LivingTalk.open(owner, portrait(persona)).menu(note(note) + header(view) + status + "\r\n" + more, options)
                .back(crewBack(owner)).show();
    }

    /** The Crew window the card on screen was opened from, as its Back; null for a card opened by a double-click. */
    private Runnable crewBack(Character owner) {
        String voice = crewParents.get(owner.getId());
        if (voice == null) {
            return null;
        }
        Persona from = voice.isEmpty() ? null : world.content().persona(voice);
        return () -> menus.crews().show(owner, from);
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
        menus.command(owner, persona, "menu.call", () -> RosterVoice.line(persona, world.roster().call(owner, persona)));
    }

    /**
     * {@code outgoing}: {@code fixed} is out with you and a bench companion takes its place; otherwise {@code fixed}
     * comes in for one that is out. Both are {@link RosterService#swap}(out, in). {@code prompt} is {@code fixed}'s line.
     */
    private void swapPicker(Character owner, Persona fixed, boolean outgoing, String prompt) {
        Stamp stamp = crewStamp(owner, fixed);
        if (row(owner, fixed) == null) {
            notInRoster(owner, fixed);
            return;
        }
        List<Option> options = new ArrayList<>();
        for (RosterEntry entry : outgoing ? swapIns(owner) : menus.rows(owner, true)) {
            Persona other = world.content().persona(entry.personaId());
            if (other == null) {
                continue;
            }
            CompanionView view = menus.view(owner, entry);
            Persona out = outgoing ? fixed : other;
            Persona in = outgoing ? other : fixed;
            options.add(stamp.option(outgoing ? benchRow(view) : partyRow(view), () -> menus.confirmBench(owner, out,
                    () -> menus.command(owner, fixed, "menu.swap",
                            () -> RosterVoice.line(fixed, world.roster().swap(owner, out, in))))));
        }
        if (options.isEmpty()) {
            menus.outcome(owner, "Nobody to swap with right now.");
            return;
        }
        LivingTalk.open(owner, portrait(fixed)).menu(prompt + "\r\n", options).back(() -> reopen(owner, fixed, "")).show();
    }

    /**
     * The stock character info, as double-clicking a stranger shows it, of the companion as it is out now; only while it
     * stands on the owner's map (the client draws it from the character it sees), else the card says why.
     */
    private void showGear(Character owner, Persona persona) {
        RosterResult shown = menus.manager().showGear(owner, persona);
        if (!shown.ok()) {
            answer(owner, persona, RosterVoice.line(persona, shown));
        }
    }

    /** The owner's companions that can take a seat at once: benched or away, and not already on their way. */
    private List<RosterEntry> swapIns(Character owner) {
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry entry : menus.rows(owner)) {
            if ((entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY)
                    && menus.view(owner, entry).travelMs() <= 0) {
                out.add(entry);
            }
        }
        return out;
    }

    // ---- what it does at camp (plan flow 2) -------------------------------------------------

    /**
     * Each duty with its projected hour at the bench priors. One out with the owner leaves to do it now and keeps its
     * seat (AWAY); any other has it set. A field duty with a town for a camp asks where first.
     */
    private void dutyPicker(Character owner, Persona persona) {
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        int level = view.level() > 0 ? view.level() : owner.getLevel();
        BenchLedger bench = world.bench();
        List<Option> options = new ArrayList<>();
        for (Assignment duty : Assignment.values()) {
            BenchLedger.Rates rates = bench == null ? null : bench.rates(persona, view.bond(), level, owner.getLevel(), duty);
            options.add(stamp.option(dutyLabel(duty, rates), () -> chooseDuty(owner, persona, duty)));
        }
        String prompt = (entry.state() == RosterState.ACTIVE ? "what should i go do? i'll keep my seat.\r\n"
                : "what should i do at camp?\r\n")
                + "now: " + view.duty() + (view.camp().isEmpty() ? "" : " @ " + view.camp())
                + String.format(Locale.ROOT, "\r\nupkeep: %,d meso an hour, none while i rest\r\n",
                        Math.round(BenchLedger.upkeepPerHour(level)));
        LivingTalk.open(owner, portrait(persona)).menu(prompt, options).back(() -> reopen(owner, persona, "")).show();
    }

    /** "Train (~32% EXP/h)", "Train (~1.2% EXP/h)", "Farm meso (~4.1k meso/h after upkeep)", "Rest (no upkeep)". */
    static String dutyLabel(Assignment duty, BenchLedger.Rates rates) {
        return switch (duty) {
            case TRAIN -> "Train" + (rates == null ? "" : rates.expPercent() > 0.0d
                    ? " (~" + CompanionMenus.percent(rates.expPercent()) + "% EXP/h)" : " (keeping pace with you)");
            case FARM_MESO -> "Farm meso" + (rates == null ? ""
                    : " (~" + CompanionMenus.meso(rates.meso() - rates.upkeep()) + " meso/h after upkeep)");
            case FARM_ITEM -> "Farm an item...";
            case GATHER -> "Gather for your quests";
            case SELL -> "Sell the haul";
            case STALL -> "Run a stall";
            case REST -> "Rest (no upkeep)";
        };
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
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        int at = camp != 0 ? camp : campOf(entry, persona);
        Map<Integer, Integer> drops = catalog.drops(at);
        Map<Integer, Integer> needs = catalog.questNeeds(owner);
        BenchLedger bench = world.bench();
        List<Option> options = new ArrayList<>();
        for (Map.Entry<Integer, Integer> drop : drops.entrySet()) {
            int itemId = drop.getKey();
            Integer need = needs.get(itemId);
            String rate = bench == null ? "" : " (" + itemRate(bench.itemsPerHour(drop.getValue())) + ")";
            options.add(stamp.option(itemLabel(itemId) + rate + (need == null ? "" : " - you need " + need),
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
            options.add(stamp.option(itemLabel(itemId) + " - you need " + count,
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

    /** What an hour of farming brings: "rare" below 0.1, "~2.6 an hour" below 10, "~40 an hour". */
    static String itemRate(double perHour) {
        if (perHour < 0.1d) {
            return "rare";
        }
        return perHour < 10.0d ? String.format(Locale.ROOT, "~%.1f an hour", perHour) : "~" + Math.round(perHour) + " an hour";
    }

    /** How many of the item, in the stock number box (0: as many as it can); End Chat ends it, the items page has Back. */
    private void farmCount(Character owner, Persona persona, int itemId, int def, int camp) {
        LivingTalk.open(owner, portrait(persona))
                .number("how many #t" + itemId + "#? (0: as many as i can)", def, 0, FARM_COUNT_MAX,
                        count -> assign(owner, persona, Assignment.FARM_ITEM, itemId, count, camp))
                .show();
    }

    /**
     * Sets the duty on companion-ops (moving the camp first when one was picked): one out with the owner leaves now
     * and keeps its seat (CompanionManager.assign delegates), any other has it set. Mid-run it waits: "after the run".
     */
    private void assign(Character owner, Persona persona, Assignment duty, int itemId, int count, int camp) {
        menus.command(owner, persona, "menu.duty", () -> {
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
            String what = world.roster().describeAssignment(now) + " at " + menus.placeNames.apply(campOf(now, persona));
            boolean leaves = before != null && before.state() == RosterState.ACTIVE && now.state() == RosterState.AWAY;
            return persona.name() + (leaves ? ": ok, off to " + what + ". i'll keep my seat." : ": ok, i'll " + what + ".");
        });
    }

    // ---- where it camps ---------------------------------------------------------------------

    /**
     * The fields fitting its level (plan flow 2, the FieldCatalog top 5), those in the owner's region first, and
     * "Right here". {@code then} null changes only the camp; otherwise the camp is for that duty, which follows, and
     * only fields are offered.
     */
    private void campPicker(Character owner, Persona persona, Assignment then) {
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        int level = view.level() > 0 ? view.level() : owner.getLevel();
        int current = campOf(entry, persona);
        int here = owner.getMapId();
        List<Option> options = new ArrayList<>();
        for (Map.Entry<Integer, Integer> field : catalog.fields(level, here).entrySet()) {
            int mapId = field.getKey();
            options.add(stamp.option(menus.placeNames.apply(mapId) + " (Lv" + field.getValue() + " monsters)"
                    + (mapId == current ? " - my camp now" : ""), () -> pickCamp(owner, persona, then, mapId)));
        }
        if (here > 0 && (then == null || catalog.isField(here))) {
            options.add(stamp.option("Right here (" + menus.placeNames.apply(here) + ")",
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
        menus.command(owner, persona, "menu.camp", () -> {
            if (committedNow(persona)) {
                return RosterVoice.line(persona, new RosterResult.InEventInstance());
            }
            RosterResult moved = menus.manager().setCamp(owner, persona, mapId);
            return moved.ok() ? persona.name() + ": ok, i'll camp at " + menus.placeNames.apply(mapId) + "."
                    : RosterVoice.line(persona, moved);
        });
    }

    // ---- fighting style, supplies & loot (plan P3b) ---------------------------------------------

    /**
     * "Fighting style...": where it stands in its owner's formation (a formation of its own, or with the others) and the
     * support it can give (heals only a healer, party buffs a job that has one), each with its setting now. A pick is
     * saved on companion-ops and answered in its own words on top of this page again, which has Back to the card.
     * Nothing here changes a seat, so nothing waits for a run.
     */
    private void fightingStyle(Character owner, Persona persona, String note) {
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionSettings own = own(owner, persona);
        int jobId = menus.view(owner, entry).jobId();
        List<Option> options = new ArrayList<>();
        options.add(Option.heading("#eWhere I stand:#n"));
        options.add(stamp.option("With the others" + now(own.formation() == null),
                () -> setFormation(owner, persona, null)));
        for (CompanionManager.Formation formation : CompanionManager.FORMATIONS) {
            options.add(stamp.option(formation.label() + now(formation.token().equals(own.formation())),
                    () -> setFormation(owner, persona, formation)));
        }
        options.add(Option.heading("#eSupport:#n"));
        for (Support toggle : Support.values()) {
            if (!toggle.offeredTo(jobId)) {
                continue; // no heals in a fighter's book, no party buff in a hunter's: nothing to promise
            }
            boolean on = own.support(toggle);
            options.add(stamp.option(supportLabel(toggle) + ": " + onOff(on), () -> setSupport(owner, persona, toggle, !on)));
        }
        LivingTalk.open(owner, portrait(persona)).menu(note(note) + "how should i fight?\r\n", options)
                .back(() -> reopen(owner, persona, "")).show();
    }

    /** Its own formation, or with the others for {@code formation} null. */
    private void setFormation(Character owner, Persona persona, CompanionManager.Formation formation) {
        menus.command(owner, "menu.style.formation", () -> said(persona,
                menus.manager().setFormation(owner, persona, formation == null ? null : formation.token()),
                formation == null ? "ok, back with the others." : formation.reply()),
                line -> fightingStyle(owner, persona, line));
    }

    private void setSupport(Character owner, Persona persona, Support toggle, boolean enabled) {
        menus.command(owner, "menu.style.support", () -> said(persona,
                menus.manager().setSupport(owner, persona, toggle, enabled), supportReply(toggle, enabled)),
                line -> fightingStyle(owner, persona, line));
    }

    /**
     * "Supplies & loot...": what it carries, the HP and MP percentages it drinks a potion below (each in the stock
     * number box, starting at its setting now), what it picks up, whether it leaves a boss's drops to the owner outside
     * boss runs (inside one the run's own loot rules decide), and some coin for its supplies (a number box up to what
     * the owner has, 0 to change their mind). A change is saved on companion-ops and answered on top of this page
     * again, which has Back to the card. Nothing here changes a seat, so nothing waits for a run.
     */
    private void supplies(Character owner, Persona persona, String note) {
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionSettings own = own(owner, persona);
        List<Option> options = new ArrayList<>();
        options.add(stamp.option("Drink an HP potion below " + own.hpPercent() + "%...",
                () -> potionBox(owner, persona, true, own.hpPercent())));
        options.add(stamp.option("Drink an MP potion below " + own.mpPercent() + "%...",
                () -> potionBox(owner, persona, false, own.mpPercent())));
        options.add(Option.heading("#eWhat I pick up:#n"));
        for (AgentLootPolicy.Mode mode : AgentLootPolicy.Mode.values()) {
            options.add(stamp.option(lootLabel(mode) + now(mode == own.loot()), () -> setLoot(owner, persona, mode)));
        }
        boolean leave = own.leaveBossDrops();
        options.add(stamp.option("Leave boss drops to you outside boss runs: " + onOff(leave),
                () -> setLeaveBossDrops(owner, persona, !leave)));
        if (owner.getMeso() > 0) {
            options.add(stamp.option("Here's some coin for supplies...", () -> coinBox(owner, persona)));
        } else {
            options.add(Option.heading("(you have no meso to give)"));
        }
        LivingTalk.open(owner, portrait(persona)).menu(note(note) + carries(owner, persona, entry), options)
                .back(() -> reopen(owner, persona, "")).show();
    }

    /**
     * "i carry 12,300 meso, 38 Red Potions and 20 Blue Potions." while it is loaded; otherwise what its purse holds
     * (one read, as the haul page reads the haul), its potions counted once it is out.
     */
    private String carries(Character owner, Persona persona, RosterEntry entry) {
        Character bot = menus.view(owner, entry).loaded() ? world.roster().companion(entry) : null;
        if (bot != null) {
            List<String> things = new ArrayList<>();
            things.add(String.format(Locale.ROOT, "%,d meso", bot.getMeso()));
            List<String> potions = catalog.allPotions(bot);
            things.addAll(potions.isEmpty() ? List.of("no potions") : potions);
            return "i carry " + String.join(", ", things.subList(0, things.size() - 1)) + " and "
                    + things.get(things.size() - 1) + ".\r\n";
        }
        int characterId = world.residents().characterIdOf(persona.id());
        long meso = characterId == 0 ? -1L : world.store().characterMeso(characterId);
        return meso < 0 ? "i'll count my meso and potions when i'm out with you.\r\n"
                : String.format(Locale.ROOT, "i have %,d meso. i'll count my potions when i'm out with you.\r\n", meso);
    }

    /** The HP ({@code hp}) or MP percentage it drinks a potion below, in the stock number box from {@code current}. */
    private void potionBox(Character owner, Persona persona, boolean hp, int current) {
        String stat = hp ? "HP" : "MP";
        LivingTalk.open(owner, portrait(persona))
                .number("drink an " + stat + " potion when my " + stat + " falls below what %? ("
                                + CompanionSettings.MIN_POTION_PERCENT + "-" + CompanionSettings.MAX_POTION_PERCENT + ")",
                        current, CompanionSettings.MIN_POTION_PERCENT, CompanionSettings.MAX_POTION_PERCENT,
                        percent -> menus.command(owner, "menu.supplies.potions", () -> {
                            // the other percentage as it is now, on companion-ops: a change to it meanwhile is kept
                            CompanionSettings now = menus.manager().settings(owner, persona);
                            RosterResult done = now == null ? new RosterResult.NotOwned()
                                    : menus.manager().setPotionThresholds(owner, persona, hp ? percent : now.hpPercent(),
                                            hp ? now.mpPercent() : percent);
                            return said(persona, done, "ok, i'll drink one below " + percent + "% " + stat + ".");
                        }, line -> supplies(owner, persona, line)))
                .show();
    }

    /**
     * How much meso to give, in the stock number box, up to what the owner has; it thanks them in its own words. The
     * box starts at 0, which gives nothing and opens the page again: Enter on the default never empties a purse.
     */
    private void coinBox(Character owner, Persona persona) {
        int purse = owner.getMeso();
        if (purse <= 0) {
            supplies(owner, persona, ""); // spent meanwhile: the page again, without the coin
            return;
        }
        LivingTalk.open(owner, portrait(persona))
                .number(String.format(Locale.ROOT, "how much? you have %,d meso. (0: never mind)", purse), 0, 0, purse,
                        meso -> {
                            if (meso <= 0) {
                                supplies(owner, persona, "");
                                return;
                            }
                            menus.command(owner, "menu.supplies.coin",
                                    () -> RosterVoice.line(persona, menus.manager().giveCoin(owner, persona, meso)),
                                    line -> supplies(owner, persona, line));
                        })
                .show();
    }

    private void setLoot(Character owner, Persona persona, AgentLootPolicy.Mode mode) {
        menus.command(owner, "menu.supplies.loot", () -> said(persona,
                menus.manager().setLootPolicy(owner, persona, mode), lootReply(mode)),
                line -> supplies(owner, persona, line));
    }

    private void setLeaveBossDrops(Character owner, Persona persona, boolean leave) {
        menus.command(owner, "menu.supplies.boss_drops", () -> said(persona,
                menus.manager().setLeaveBossDrops(owner, persona, leave),
                leave ? "ok, boss drops are yours." : "ok, i'll pick up boss drops too."),
                line -> supplies(owner, persona, line));
    }

    /**
     * The companion's settings as the applier keeps them (its row was checked in the cache already): no roster row is
     * read again for a render.
     */
    private CompanionSettings own(Character owner, Persona persona) {
        return world.roster().settings().settings(owner.getId(), persona.id());
    }

    /** A settings change's line: the companion's own words when it went through, else why not. */
    private static String said(Persona persona, RosterResult done, String words) {
        return done.ok() ? persona.name() + ": " + words : RosterVoice.line(persona, done);
    }

    private static String lootLabel(AgentLootPolicy.Mode mode) {
        return switch (mode) {
            case ALL -> "Everything";
            case OWN_KILLS -> "Only what I hit";
            case QUEST_ONLY -> "Meso and your quest items";
            case NONE -> "Nothing";
        };
    }

    private static String lootReply(AgentLootPolicy.Mode mode) {
        return switch (mode) {
            case ALL -> "ok, i'll pick up everything.";
            case OWN_KILLS -> "ok, only what i hit.";
            case QUEST_ONLY -> "ok, only meso and what your quests need.";
            case NONE -> "ok, i'll leave it all to you.";
        };
    }

    private static String supportLabel(Support toggle) {
        return switch (toggle) {
            case BUFFS -> "Party buffs";
            case HEALS -> "Party heals";
            case BUFF_POTIONS -> "Buff potions";
        };
    }

    private static String supportReply(Support toggle, boolean enabled) {
        return switch (toggle) {
            case BUFFS -> enabled ? "ok, i'll keep the party buffed." : "ok, no more buffs from me.";
            case HEALS -> enabled ? "ok, i'll heal the party." : "ok, i'll leave the healing to you.";
            case BUFF_POTIONS -> enabled ? "ok, i'll drink my buff potions." : "ok, i'll save my buff potions.";
        };
    }

    private static String now(boolean current) {
        return current ? NOW : "";
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    // ---- haul, wages, parting ways ------------------------------------------------------------

    /** What it carries, and what to do with it: hand it over, send it by Duey, bank the meso, or wear the best of it. */
    private void haul(Character owner, Persona persona) {
        Stamp stamp = stamp(owner, persona);
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
        List<Option> options = new ArrayList<>();
        if (!items.isEmpty() || view.haulMeso() > 0) {
            options.add(stamp.option("Hand it over", () -> menus.command(owner, persona, "menu.haul.hand_over",
                    () -> world.roster().handOver(owner, persona))));
            options.add(stamp.option("Send it by Duey", () -> menus.command(owner, persona, "menu.haul.duey",
                    () -> world.roster().sendIt(owner, persona))));
        }
        if (view.haulMeso() > 0) {
            options.add(stamp.option("Bank the meso", () -> menus.command(owner, persona, "menu.haul.bank",
                    () -> world.roster().bankIt(owner, persona))));
        }
        if (gear && entry.state() == RosterState.ACTIVE && view.loaded()) {
            options.add(stamp.option("Wear your best from the haul", () -> menus.command(owner, persona,
                    "menu.haul.wear_best", () -> RosterVoice.line(persona, menus.manager().wearBestFromHaul(owner, persona)))));
        }
        LivingTalk.open(owner, portrait(persona)).menu(text.toString(), options).back(() -> reopen(owner, persona, "")).show();
    }

    /** Whether it is paid up, its upkeep, and paying hours ahead in the stock number box (decision 19 (b)). */
    private void wages(Character owner, Persona persona) {
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        long upkeep = Math.max(1L, Math.round(BenchLedger.upkeepPerHour(view.level() > 0 ? view.level() : 1)));
        long affordable = Math.min(WAGE_HOURS_MAX, owner.getMeso() / upkeep);
        List<Option> options = new ArrayList<>();
        if (affordable >= 1) {
            options.add(stamp.option("Pay your wages...", () -> LivingTalk.open(owner, portrait(persona))
                    .number(String.format(Locale.ROOT, "how many hours? %,d meso an hour; you have %,d.", upkeep, owner.getMeso()),
                            (int) Math.min(WAGE_HOURS_DEFAULT, affordable), 1, (int) affordable,
                            hours -> menus.command(owner, persona, "menu.wages",
                                    () -> world.roster().payWages(owner, persona, hours * upkeep)))
                    .show()));
        } else {
            options.add(Option.heading(String.format(Locale.ROOT, "(you need %,d meso to pay an hour)", upkeep)));
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
        long rendered = menus.version(owner);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        CompanionView view = menus.view(owner, entry);
        String haul = view.haul().isEmpty() ? "nothing" : view.haul() + ", handed over first (Duey takes what doesn't fit)";
        long purse = purse(persona, entry, view);
        String text = "Part ways with " + persona.name() + " for good?\r\n\r\nBond: " + bondName(view.bond()) + "\r\nHaul: "
                + haul + (purse > 0L ? String.format(Locale.ROOT, "\r\nPurse: %,d meso (stays with %s)", purse,
                persona.name()) : "") + (view.committed() ? "\r\n" + persona.name() + " finishes the run first." : "");
        LivingTalk.open(owner, BOARD_NPC).speaker(NOTES_SPEAKER)
                .yesNo(text, menus.ifUnchanged(owner, rendered, () -> menus.command(owner, persona, "menu.release",
                        () -> RosterVoice.line(persona, menus.manager().release(owner, persona))),
                        () -> reopen(owner, persona, THINGS_CHANGED)), null)
                .show();
    }

    /**
     * The meso in the companion's own purse (what it was given for supplies, and its pocket money): its loaded
     * character's, else its characters row's (one read, as the haul page reads the haul); -1 when unknown.
     */
    private long purse(Persona persona, RosterEntry entry, CompanionView view) {
        Character bot = view.loaded() ? world.roster().companion(entry) : null;
        if (bot != null) {
            return bot.getMeso();
        }
        int characterId = world.residents().characterIdOf(persona.id());
        return characterId == 0 ? -1L : world.store().characterMeso(characterId);
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

    /**
     * A screen's stamp for a page about {@code persona} alone: its row's version taken before the screen reads it. An
     * option it stamps, picked after that row changed, opens the card again with "things changed" instead of acting;
     * another companion's arrival or duty meanwhile leaves it as it was.
     */
    private Stamp stamp(Character owner, Persona persona) {
        return new Stamp(owner, persona, menus.version(owner, persona), () -> reopen(owner, persona, THINGS_CHANGED));
    }

    /** A screen that lists other companions (the swap picker): the owner-wide version, as the Crew window uses. */
    private Stamp crewStamp(Character owner, Persona persona) {
        return new Stamp(owner, null, menus.version(owner), () -> reopen(owner, persona, THINGS_CHANGED));
    }

    private final class Stamp {
        private final Character owner;
        private final Persona row;
        private final long rendered;
        private final Runnable stale;

        Stamp(Character owner, Persona row, long rendered, Runnable stale) {
            this.owner = owner;
            this.row = row;
            this.rendered = rendered;
            this.stale = stale;
        }

        Option option(String label, Runnable action) {
            return new Option(label, row == null ? menus.ifUnchanged(owner, rendered, action, stale)
                    : menus.ifUnchanged(owner, row, rendered, action, stale));
        }
    }

    /** The option, or while the companion is tied to a run its label with "(after the run)" as a line that cannot be picked. */
    private static Option unlessCommitted(boolean committed, Option option) {
        return committed ? Option.heading(option.label().replace("...", "") + AFTER_THE_RUN) : option;
    }

    /**
     * An order the companion answers in its own words (its whisper or bubble), or a trade whose window is the answer:
     * the notes open only when it could not be done.
     */
    private void order(Character owner, Persona persona, String name, Function<CompanionManager, RosterResult> verb) {
        menus.command(owner, persona, name, () -> {
            RosterResult result = verb.apply(menus.manager());
            return result.ok() ? "" : RosterVoice.line(persona, result);
        });
    }

    /** The card again, read afresh (a line such as "things changed" on top), as the answer to the click. */
    private void reopen(Character owner, Persona persona, String note) {
        Stamp stamp = stamp(owner, persona);
        RosterEntry entry = row(owner, persona);
        if (entry == null) {
            notInRoster(owner, persona);
            return;
        }
        switch (entry.state()) {
            case BENCHED, AWAY, PARKED -> benchCard(owner, persona, entry, note, stamp);
            default -> card(owner, persona, entry, note, stamp);
        }
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
                    if (drop.itemId > 0 && drop.chance > 0 && drop.questid <= 0 && farmable(drop.itemId)) {
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
                    if (left > 0 && farmable(need.getKey())) {
                        needs.merge(need.getKey(), left, Math::max);
                    }
                }
            }
            QuestLedger quests = world.quests();
            for (QuestState state : quests == null ? List.<QuestState>of() : quests.active(owner)) {
                Content.Quest quest = world.content().quest(state.questId());
                Content.Step step = quest == null ? null : quest.step(state.stepIndex());
                int left = step == null ? 0 : step.count() - state.progress();
                if (step != null && step.type() == StepType.COLLECT_ITEM && left > 0 && farmable(step.itemId())) {
                    needs.merge(step.itemId(), left, Math::max);
                }
            }
            return needs;
        }

        /**
         * An ETC item the game's item data names: only such an id goes into a window's #i/#t tags (an authored id the
         * client does not have would show nothing, or worse).
         */
        private static boolean farmable(int itemId) {
            return itemId > 0 && ItemConstants.getInventoryType(itemId) == InventoryType.ETC
                    && ItemInformationProvider.getInstance().getName(itemId) != null;
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

        @Override
        public List<String> allPotions(Character bot) {
            Inventory use = bot.getInventory(InventoryType.USE);
            if (use == null) {
                return List.of();
            }
            Map<Integer, Integer> counts = new HashMap<>();
            for (Item item : use.list()) {
                if (item.getQuantity() > 0 && AgentUseItemClassificationPolicy.isRecoveryPotion(
                        AgentUseItemClassificationPolicy.itemEffect(item.getItemId()))) {
                    counts.merge(item.getItemId(), (int) item.getQuantity(), Integer::sum);
                }
            }
            return counts.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .map(potion -> potionCount(potion.getValue(), CompanionView.itemName(potion.getKey()))).toList();
        }
    }

    /** "38 Red Potions", "1 Elixir". */
    static String potionCount(int count, String name) {
        return count + " " + name + (count == 1 || name.endsWith("s") ? "" : "s");
    }
}
