package server.living.population.rivals;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PvpConfig;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.integration.AgentPresence;
import server.pvp.DuelField;
import server.pvp.DuelLog;
import server.pvp.PvpDamageModel;
import server.pvp.PvpGrindMinutes;
import tools.PacketCreator;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3.3: what a rival challenge pays its human fighters (docs/PVP_PLAN.md section 2 "Rewards"). A win: 20 grind-minutes
 * of EXP (twice that on the account's first win of the day), 3 Event Trophies, the crew's stake (30 meso grind-minutes
 * from its fighters' purses, booked as {@link MesoAccount#PVP_STAKE}) and a 25% chance of a 60% attack scroll for the
 * winner's weapon. The EXP, stake and scroll chance scale with the odds ({@link #oddsMultiplier}), and a win more than
 * 85% likely pays the Trophies alone. A loss or a draw: 1 Trophy, plus 5 grind-minutes of EXP to a side that took at
 * least half the crew fighters' max HP. A fighter who left the duel earns nothing; a GM earns nothing unless a GM
 * brought the challenge (a test); an account is paid for at most 3 challenges a day. The day's count lives in memory
 * like the rest of the challenge bookkeeping: a restart forgets it.
 */
final class RivalRewards {
    private static final Logger log = LoggerFactory.getLogger(RivalRewards.class);

    static final int WIN_TROPHIES = 3;
    static final int LOSS_TROPHIES = 1;
    static final double WIN_EXP_GMIN = 20d;
    static final double LOSS_EXP_GMIN = 5d;
    static final double STAKE_GMIN = 30d;
    static final double SCROLL_CHANCE = 0.25d;
    /** A losing side that took this share of the crew fighters' max HP still earns {@link #LOSS_EXP_GMIN}. */
    static final double LOSS_EXP_SHARE = 0.5d;
    /** A win more likely than this pays the Trophies alone. */
    static final double TROPHIES_ONLY_ABOVE = 0.85d;
    static final int DAILY_REWARDED = 3;
    /**
     * How steeply the odds follow the sides' pace ratio r: p = 1 / (1 + r^-ODDS_SLOPE). At matched gear a 3-5 level lead
     * (r about 1.06-1.10) won 57-60% of practice duels in runs 24-27; this slope gives 56-60%.
     */
    static final double ODDS_SLOPE = 4d;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** What one human is paid. */
    record Plan(long exp, int trophies, long stake, int scrollId) { }

    /** A side's fighters for the odds: each one's gear edge (G^gamma) and level. */
    record Fighter(double gearEdge, int level) { }

    private final Random random;
    /** account id -> {day index, challenges paid that day, wins paid that day} */
    private final Map<Integer, long[]> days = new ConcurrentHashMap<>();

    RivalRewards(Random random) {
        this.random = random;
    }

    // ---- the numbers ----------------------------------------------------------------------------------------------

    /**
     * The chance side A beats side B before the fight: the ratio of their paces from each side's mean log gear edge and
     * the level factor between their mean levels (the damage model's steps 3 and 4), through {@link #ODDS_SLOPE}.
     */
    static double winChance(List<Fighter> sideA, List<Fighter> sideB, PvpConfig config) {
        if (sideA.isEmpty() || sideB.isEmpty()) {
            return 0.5d;
        }
        int levelA = (int) Math.round(sideA.stream().mapToInt(Fighter::level).average().orElse(1));
        int levelB = (int) Math.round(sideB.stream().mapToInt(Fighter::level).average().orElse(1));
        double lnRatio = meanLn(sideA) - meanLn(sideB)
                + Math.log(PvpDamageModel.levelFactor(levelA, levelB, config)
                / PvpDamageModel.levelFactor(levelB, levelA, config));
        return 1d / (1d + Math.exp(-ODDS_SLOPE * lnRatio));
    }

    private static double meanLn(List<Fighter> side) {
        return side.stream().mapToDouble(f -> Math.log(Math.max(1e-6, f.gearEdge()))).average().orElse(0d);
    }

    /** Every win reward is multiplied by this: 0.75 for a sure win, 1.25 for beating a sure loss. */
    static double oddsMultiplier(double p) {
        return 0.75d + 0.5d * (1d - p);
    }

    static Plan win(double p, boolean firstWinToday, double expGmin, long stakeShare, int scrollId, double roll) {
        if (p > TROPHIES_ONLY_ABOVE) {
            return new Plan(0, WIN_TROPHIES, 0, 0);
        }
        double multiplier = oddsMultiplier(p);
        long exp = Math.round(WIN_EXP_GMIN * expGmin * (firstWinToday ? 2 : 1) * multiplier);
        int scroll = scrollId > 0 && roll < SCROLL_CHANCE * multiplier ? scrollId : 0;
        return new Plan(exp, WIN_TROPHIES, stakeShare, scroll);
    }

    static Plan loss(boolean tookHalf, double expGmin) {
        return new Plan(tookHalf ? Math.round(LOSS_EXP_GMIN * expGmin) : 0, LOSS_TROPHIES, 0, 0);
    }

    /** The crew's whole stake for a win at odds {@code p}: 30 meso grind-minutes at the main's level, odds-scaled. */
    static long stake(double p, double mesoGmin) {
        return p > TROPHIES_ONLY_ABOVE ? 0L : Math.round(STAKE_GMIN * mesoGmin * oddsMultiplier(p));
    }

    /** The 60% weapon (or magic) attack scroll for a weapon, or 0 for none. */
    static int attackScrollFor(int weaponId) {
        return server.pvp.PvpScrolls.attack(weaponId, 60);
    }

    /**
     * What each purse pays toward {@code target}: even shares, a short purse's gap spread over the others, never more
     * than a purse holds. The total is {@code min(target, sum of purses)}.
     */
    static long[] shares(long[] purses, long target) {
        long[] take = new long[purses.length];
        long left = Math.max(0L, target);
        while (left > 0) {
            int payers = 0;
            for (int i = 0; i < purses.length; i++) {
                if (purses[i] - take[i] > 0) {
                    payers++;
                }
            }
            if (payers == 0) {
                break;
            }
            long each = (left + payers - 1) / payers;
            for (int i = 0; i < purses.length && left > 0; i++) {
                long part = Math.min(each, Math.min(purses[i] - take[i], left));
                if (part > 0) {
                    take[i] += part;
                    left -= part;
                }
            }
        }
        return take;
    }

    // ---- the day's count ------------------------------------------------------------------------------------------

    private long[] day(int accountId, long now) {
        long index = now / DAY_MS;
        return days.compute(accountId, (id, old) -> old == null || old[0] != index ? new long[] {index, 0, 0} : old);
    }

    int paidToday(int accountId, long now) {
        return (int) day(accountId, now)[1];
    }

    // ---- paying ---------------------------------------------------------------------------------------------------

    /**
     * Pays the human fighters of a finished challenge duel (side 0 is the player side). {@code crewFighters} are the
     * crew's side, {@code crewMaxHp} their max HP summed at the start, {@code p} the player side's odds then.
     */
    void pay(DuelField.Result result, List<Character> players, List<Character> crewFighters, String crewName,
             double p, long crewMaxHp, boolean forced, int mainLevel, long now) {
        boolean won = result.winnerSide() == 0;
        Set<Integer> left = new HashSet<>();
        long crewTook = 0;
        for (DuelLog.Row row : result.rows()) {
            if (row.left()) {
                left.add(row.charId());
            }
            if (row.side() == 1) {
                crewTook += row.taken();
            }
        }
        List<Character> paid = new ArrayList<>();
        for (Character human : players) {
            if (human == null || AgentPresence.isAgent(human) || human.getClient() == null) {
                continue;
            }
            if (left.contains(human.getId())) {
                human.dropMessage(5, "You left the rival duel, so it pays nothing.");
            } else if (human.isGM() && !forced) {
                continue;
            } else if (paidToday(human.getAccountID(), now) >= DAILY_REWARDED) {
                human.dropMessage(5, "You've had " + DAILY_REWARDED + " rewarded rival challenges today; "
                        + "this one pays nothing.");
            } else {
                paid.add(human);
            }
        }
        if (paid.isEmpty()) {
            return;
        }
        long stakeEach = 0;
        if (won) {
            long target = stake(p, PvpGrindMinutes.get().mesoPerMinute(mainLevel));
            stakeEach = collect(crewFighters, target - target % paid.size()) / paid.size();
        }
        boolean tookHalf = crewMaxHp > 0 && crewTook >= LOSS_EXP_SHARE * crewMaxHp;
        for (Character human : paid) {
            long[] day = day(human.getAccountID(), now);
            double expGmin = PvpGrindMinutes.get().expPerMinute(human.getLevel());
            Plan plan;
            if (won) {
                Item weapon = human.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
                plan = win(p, day[2] == 0, expGmin, stakeEach, weapon == null ? 0 : attackScrollFor(weapon.getItemId()),
                        random.nextDouble());
                day[2]++;
            } else {
                plan = loss(tookHalf, expGmin);
            }
            day[1]++;
            grant(human, plan, won, crewName);
            log.info("Rival reward: {} {} {} (odds {}): {} EXP, {} Trophies, {} mesos stake, scroll {}{}",
                    human.getName(), won ? "beat" : "lost to", crewName, String.format(Locale.ROOT, "%.2f", p),
                    plan.exp(), plan.trophies(), plan.stake(), plan.scrollId(), human.isGM() ? " (GM test)" : "");
        }
    }

    /** Takes {@code target} (or what they hold) from the crew fighters' purses; returns what was taken. */
    private static long collect(List<Character> crewFighters, long target) {
        List<Character> payers = crewFighters.stream().filter(c -> c != null && c.getMeso() > 0).toList();
        long[] purses = payers.stream().mapToLong(Character::getMeso).toArray();
        long[] take = shares(purses, target);
        long taken = 0;
        for (int i = 0; i < payers.size(); i++) {
            if (take[i] > 0) {
                try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_STAKE)) {
                    payers.get(i).gainMeso((int) -take[i], false);
                }
                taken += take[i];
            }
        }
        return taken;
    }

    private static void grant(Character human, Plan plan, boolean won, String crewName) {
        StringBuilder note = new StringBuilder(won ? "You beat " + crewName : "You lost to " + crewName);
        if (plan.exp() > 0) {
            human.gainExp((int) Math.min(Integer.MAX_VALUE, plan.exp()), true, true);
        }
        if (giveItem(human, ItemId.EVENT_TROPHY, plan.trophies())) {
            note.append(won ? ": " : ", but earned ").append(plan.trophies())
                    .append(plan.trophies() == 1 ? " Event Trophy" : " Event Trophies");
        }
        if (plan.stake() > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_STAKE)) {
                human.gainMeso((int) plan.stake(), true);
            }
            note.append(" and their stake of ").append(NumberFormat.getIntegerInstance(Locale.US).format(plan.stake()))
                    .append(" mesos");
        }
        if (plan.scrollId() > 0) {
            giveItem(human, plan.scrollId(), 1);
        }
        human.dropMessage(5, note.append('.').toString());
    }

    private static boolean giveItem(Character human, int itemId, int count) {
        if (count <= 0) {
            return false;
        }
        if (InventoryManipulator.addById(human.getClient(), itemId, (short) count)) {
            human.sendPacket(PacketCreator.getShowItemGain(itemId, (short) count, true));
            return true;
        }
        human.dropMessage(5, "Your inventory is full, so a rival reward was lost.");
        log.warn("Rival reward: no room for {} x{} for {}", itemId, count, human.getName());
        return false;
    }
}
