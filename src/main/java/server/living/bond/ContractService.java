package server.living.bond;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Contract;
import server.living.config.Content.ContractItem;
import server.living.config.Content.LeaveRule;
import server.living.config.Content.Persona;
import server.living.roster.Commitments;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recruitment: when a Persona offers its terms, what the player must pay or prove, the join
 * itself (roster + party), the contract clock while they travel together, and the ways it ends.
 */
public final class ContractService {
    private static final Logger log = LoggerFactory.getLogger(ContractService.class);
    /** What a companion committed to a run says once when its walk-out comes due (it goes when the run is over). */
    public static final String AFTER_THIS_RUN_LINE = "after this run, i'm heading home.";
    /** A bench tolerance this short, in days (0 is one hour), is warned about before a bench (plan flow 10). */
    static final int LOW_BENCH_TOLERANCE_DAYS = 1;

    /**
     * ROSTER_CONTRACT_MODE (decision 3): whether a rostered companion's authored terms still end its contract on
     * their own. The authored end rules, and what each mode does with them:
     * <ul>
     *   <li>CONTRACT_END: the contract's {@code durationHours} of travel together are up (its {@code leavesOn} lists
     *   CONTRACT_END);</li>
     *   <li>outlevelled: the owner is more than the contract's {@code maxLevelGap} levels above the companion out with
     *   them;</li>
     *   <li>LEADER_DEATH_3X: the owner's third death while the companion is out (its {@code leavesOn} lists it);</li>
     *   <li>bench tolerance: the persona's {@code benchToleranceDays} on the bench (0 is one hour, like Dorran's; below
     *   0 never).</li>
     * </ul>
     * AUTHORED applies all four, PERMANENT_ON_ROSTER none, PERMANENT_AT_SWORN all four until the owner's bond with the
     * persona reaches SWORN and none from then on. In every mode an explicit end ({@link #end}, which arcs use) and a
     * dismissal still end a contract, wages keep working, LEADER_OFFLINE_20M is never a walk-out (the owner's logout
     * parks and benches, decision 8a), and a walk-out due while the companion is committed to a run waits for the run.
     */
    public enum Mode {
        AUTHORED, PERMANENT_ON_ROSTER, PERMANENT_AT_SWORN;

        /** The configured mode; anything else is the default, PERMANENT_ON_ROSTER. */
        public static Mode of(String configured) {
            try {
                return configured == null ? PERMANENT_ON_ROSTER : valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return PERMANENT_ON_ROSTER;
            }
        }

        /** True while the authored end rules apply to a companion whose owner's bond with it is at {@code stage}. */
        public boolean authoredTermsHold(BondStage stage) {
            return switch (this) {
                case AUTHORED -> true;
                case PERMANENT_ON_ROSTER -> false;
                case PERMANENT_AT_SWORN -> stage == null || !stage.atLeast(BondStage.SWORN);
            };
        }
    }

    /** A walk-out waiting for its companion's run to end: whose companion it is, and why it goes. */
    private record AfterTheRun(int ownerId, String personaId, String reason) { }

    private final LivingWorld world;
    /** Companion character id -> its walk-out that waits for its commitment to end (Commitments). */
    private final Map<Integer, AfterTheRun> afterTheRun = new ConcurrentHashMap<>();

    public ContractService(LivingWorld world) {
        this.world = world;
    }

    public Contract contractOf(Persona persona) {
        return world.content().contract(persona.contract());
    }

    /** Why the persona will not (yet) offer to join, or null when the offer can be made. */
    public String blocker(Character chr, Persona persona) {
        Contract contract = contractOf(persona);
        if (contract == null || !world.arcs().recruitable(persona) || !world.arcs().contractUnlocked(persona)) {
            return "not-recruitable";
        }
        int owner = world.roster().ownerOf(persona.id());
        if (owner != 0) {
            return owner == chr.getId() ? "already-yours" : "taken";
        }
        Bond bond = world.bonds().peek(chr.getId(), persona.id());
        BondStage stage = bond == null ? BondStage.NONE : bond.stage();
        if (!stage.atLeast(contract.offerWhen().bondStage())) {
            return "bond";
        }
        Set<String> worldFlags = world.store().worldFlags();
        for (String flag : contract.offerWhen().flags()) {
            if (!world.bonds().hasFlag(chr.getId(), persona.id(), flag, worldFlags)) {
                return "flags";
            }
        }
        if (contract.offerWhen().arc() != null
                && !world.arcs().chapterReached(persona, contract.offerWhen().arc(), contract.offerWhen().chapterAtLeast())) {
            return "arc";
        }
        if (bond != null && bond.leftAtMs() > 0 && contract.terms().cooldownHoursAfterLeave() > 0
                && System.currentTimeMillis() - bond.leftAtMs() < contract.terms().cooldownHoursAfterLeave() * 3_600_000L) {
            return "cooldown";
        }
        if (world.roster().roster(chr).size() >= world.config().ROSTER_MAX) {
            return "roster-full";
        }
        return null;
    }

    /**
     * The player asked the persona to join. States terms, or completes the join when they are met: then the join
     * runs on companion-ops and the persona answers from there, and this returns null (nothing to say yet).
     */
    public String askToJoin(Character chr, Persona persona) {
        String blocker = blocker(chr, persona);
        if (blocker != null) {
            return refusal(persona, blocker, chr);
        }
        Contract contract = contractOf(persona);
        Bond bond = world.bonds().bond(chr, persona);
        long now = System.currentTimeMillis();
        boolean rejoin = bond.leftAtMs() > 0;
        long mesoDue = rejoin ? contract.terms().rejoinMeso() : contract.price().meso();
        if (bond.contractState() != ContractState.OFFERED || bond.offerExpiresAtMs() < now) {
            Bond offered = new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), bond.flags(),
                    ContractState.OFFERED, 0L, now + world.config().CONTRACT_OFFER_MINUTES * 60_000L, 0, 0L,
                    bond.partySeconds(), bond.leftAtMs(), now);
            world.bonds().save(offered);
            world.store().event(chr.getId(), persona.id(), "contract.offered", persona.contract());
            List<String> lines = world.arcs().lines(persona, "offer");
            String voice = lines.isEmpty() ? "here's my price." : lines.get(Math.floorMod(chr.getId(), lines.size()));
            String price = describePrice(contract, mesoDue, offered);
            server.living.resident.LivingTalk.open(chr, server.living.resident.ResidentDialogue.portraitOf(persona))
                    .say(voice)
                    .say("#e" + persona.name() + "'s terms#n" + "\r\n\r\n" + price)
                    .menu("Well?", List.of(
                            new server.living.resident.LivingTalk.Option("I have it. Let's go.",
                                    () -> world.residents().reply(persona, chr, askToJoin(chr, persona))),
                            new server.living.resident.LivingTalk.Option("Give me some time.",
                                    () -> world.residents().reply(persona, chr, "the offer stands for "
                                            + world.config().CONTRACT_OFFER_MINUTES + " minutes."))))
                    .show();
            return voice + " " + price + " Say join again when you have it.";
        }
        List<String> missing = missing(chr, contract, bond, mesoDue);
        if (!missing.isEmpty()) {
            return "not yet: " + String.join(", ", missing) + ".";
        }
        // the recruit writes the roster: on companion-ops (plan "CompanionExecutor"), which answers for itself
        world.executor().submit("contract.join " + persona.id(),
                () -> world.residents().reply(persona, chr, join(chr, persona)));
        return null;
    }

    /**
     * On companion-ops: the join, if the terms still stand there (a second click, a payment or another join may
     * have come first, so nothing is charged twice).
     */
    private String join(Character chr, Persona persona) {
        String blocker = blocker(chr, persona);
        if (blocker != null) {
            return refusal(persona, blocker, chr);
        }
        Contract contract = contractOf(persona);
        Bond bond = world.bonds().bond(chr, persona);
        if (bond.contractState() != ContractState.OFFERED || bond.offerExpiresAtMs() < System.currentTimeMillis()) {
            return null; // the offer lapsed meanwhile; asking again names the price again
        }
        long mesoDue = bond.leftAtMs() > 0 ? contract.terms().rejoinMeso() : contract.price().meso();
        List<String> missing = missing(chr, contract, bond, mesoDue);
        return missing.isEmpty() ? execute(chr, persona, contract, bond, mesoDue)
                : "not yet: " + String.join(", ", missing) + ".";
    }

    private String execute(Character chr, Persona persona, Contract contract, Bond bond, long mesoDue) {
        long owed = Math.max(0L, mesoDue - bond.paidMeso());
        if (owed > 0) {
            chr.gainMeso((int) -Math.min(owed, Integer.MAX_VALUE), true);
        }
        List<ContractItem> kept = new ArrayList<>();
        for (ContractItem item : contract.price().items()) {
            InventoryType type = ItemConstants.getInventoryType(item.itemId());
            if (item.keep()) {
                InventoryManipulator.removeById(chr.getClient(), type, item.itemId(), item.count(), false, false);
                kept.add(item);
            }
        }
        String result = server.living.roster.RosterVoice.line(persona, world.roster().recruit(chr, persona));
        long now = System.currentTimeMillis();
        Bond active = new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), bond.flags(),
                ContractState.ACTIVE, now, 0L, bond.proofProgress(), bond.paidMeso() + owed, bond.partySeconds(), 0L, now);
        world.bonds().save(active);
        world.store().event(chr.getId(), persona.id(), "contract.active", persona.contract() + " paid " + (bond.paidMeso() + owed));
        Character companion = world.roster().companion(world.roster().entry(chr.getId(), persona.id()));
        if (companion != null) {
            for (ContractItem item : kept) {
                InventoryManipulator.addById(companion.getClient(), item.itemId(), (short) item.count());
            }
            if (!kept.isEmpty()) {
                try {
                    server.agents.capabilities.equipment.AgentEquipmentService.autoEquip(companion, chr, null, true);
                } catch (RuntimeException failure) {
                    log.debug("autoequip after contract failed", failure);
                }
            }
        }
        world.bonds().addPoints(chr, persona, 10, "joined");
        return result;
    }

    private List<String> missing(Character chr, Contract contract, Bond bond, long mesoDue) {
        List<String> missing = new ArrayList<>();
        long owed = Math.max(0L, mesoDue - bond.paidMeso());
        if (owed > 0 && chr.getMeso() < owed) {
            missing.add(owed + " meso");
        }
        for (ContractItem item : contract.price().items()) {
            InventoryType type = ItemConstants.getInventoryType(item.itemId());
            if (chr.getInventory(type).countById(item.itemId()) < item.count()) {
                missing.add(itemName(item.itemId()) + (item.count() > 1 ? " x" + item.count() : ""));
            }
        }
        if (contract.price().proof() != null && bond.proofProgress() < contract.price().proof().count()) {
            missing.add((contract.price().proof().count() - bond.proofProgress()) + " more kills");
        }
        return missing;
    }

    /** Partial payment toward an open offer. */
    public String pay(Character chr, Persona persona, long amount) {
        Bond bond = world.bonds().peek(chr.getId(), persona.id());
        if (bond == null || bond.contractState() != ContractState.OFFERED) {
            return persona.name() + " has not named a price. Ask to join first.";
        }
        Contract contract = contractOf(persona);
        long due = (bond.leftAtMs() > 0 ? contract.terms().rejoinMeso() : contract.price().meso()) - bond.paidMeso();
        long pay = Math.max(0L, Math.min(amount, due));
        if (pay <= 0) {
            return "nothing more is owed.";
        }
        if (chr.getMeso() < pay) {
            return "you don't have " + pay + " meso.";
        }
        chr.gainMeso((int) -pay, true);
        Bond updated = new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), bond.flags(),
                bond.contractState(), bond.contractStartedAtMs(), bond.offerExpiresAtMs(), bond.proofProgress(),
                bond.paidMeso() + pay, bond.partySeconds(), bond.leftAtMs(), System.currentTimeMillis());
        world.bonds().save(updated);
        return persona.name() + " pockets " + pay + " meso (" + Math.max(0L, due - pay) + " to go).";
    }

    public void onMobKilled(int mobId, Set<Integer> attackerIds, MapleMap map) {
        for (int attackerId : attackerIds) {
            Character chr = map.getCharacterById(attackerId);
            if (chr == null || AgentPresence.isAgent(chr)) {
                continue;
            }
            for (Bond bond : world.store().bondsOf(chr.getId())) {
                if (bond.contractState() != ContractState.OFFERED) {
                    continue;
                }
                Persona persona = world.content().persona(bond.personaId());
                Contract contract = persona == null ? null : contractOf(persona);
                if (contract == null || contract.price().proof() == null || !contract.price().proof().mobIds().contains(mobId)) {
                    continue;
                }
                int progress = bond.proofProgress() + 1;
                world.bonds().save(new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), bond.flags(),
                        bond.contractState(), bond.contractStartedAtMs(), bond.offerExpiresAtMs(), progress, bond.paidMeso(),
                        bond.partySeconds(), bond.leftAtMs(), System.currentTimeMillis()));
                int needed = contract.price().proof().count();
                if (progress == needed || progress % 10 == 0) {
                    chr.dropMessage(5, persona.name() + "'s proof: " + Math.min(progress, needed) + "/" + needed + " kills.");
                }
            }
        }
    }

    // ---- deaths --------------------------------------------------------------------------

    /** owner:persona -> owner deaths while the contract has been active (LEADER_DEATH_3X). */
    private final java.util.Map<String, Integer> deaths = new java.util.concurrent.ConcurrentHashMap<>();

    public void onOwnerDied(Character owner) {
        if (AgentPresence.isAgent(owner)) {
            return;
        }
        for (RosterEntry entry : world.roster().roster(owner)) {
            if (entry.state() != RosterState.ACTIVE) {
                continue;
            }
            Persona persona = world.content().persona(entry.personaId());
            Contract contract = persona == null ? null : contractOf(persona);
            if (contract == null || !contract.terms().leavesOn().contains(LeaveRule.LEADER_DEATH_3X)
                    || !authoredTermsHold(owner.getId(), persona.id())) {
                continue;
            }
            int count = deaths.merge(owner.getId() + ":" + persona.id(), 1, Integer::sum);
            if (count >= 3) {
                deaths.remove(owner.getId() + ":" + persona.id());
                List<String> lines = world.arcs().lines(persona, "leaveDeaths");
                leave(owner, persona, "leader died three times", lines.isEmpty() ? "three times. i'm not dying for this." : lines.get(0));
            } else {
                List<String> lines = world.arcs().lines(persona, "ownerDied");
                if (!lines.isEmpty()) {
                    world.residents().reply(persona, owner, lines.get(Math.floorMod(count, lines.size())));
                }
            }
        }
    }

    // ---- clock ---------------------------------------------------------------------------

    /**
     * The contract clocks (companion-ops, every minute): travel time accrues for companions out with their owner (in the
     * party, on the way or waiting outside a run), and the authored end rules the mode keeps (see {@link Mode}) end a
     * contract whose time, level gap or bench tolerance is up.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        Mode mode = mode();
        for (RosterEntry entry : outWithOwners()) {
            Persona persona = world.content().persona(entry.personaId());
            Bond bond = world.bonds().peek(entry.characterId(), entry.personaId());
            if (persona == null || bond == null || bond.contractState() != ContractState.ACTIVE) {
                continue;
            }
            Contract contract = contractOf(persona);
            Character owner = world.roster().onlineCharacter(entry.characterId());
            if (owner == null || contract == null) {
                continue;
            }
            if (!world.roster().coPresent(owner, persona.id()) && world.roster().companion(entry) == null) {
                continue;
            }
            int seconds = bond.partySeconds() + 60;
            world.bonds().save(new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(), bond.flags(),
                    bond.contractState(), bond.contractStartedAtMs(), bond.offerExpiresAtMs(), bond.proofProgress(),
                    bond.paidMeso(), seconds, bond.leftAtMs(), now));
            if (!mode.authoredTermsHold(bond.stage())) {
                continue; // decision 3: permanent, so the clock only counts
            }
            if (contract.terms().durationHours() > 0 && seconds >= contract.terms().durationHours() * 3600
                    && contract.terms().leavesOn().contains(LeaveRule.CONTRACT_END)) {
                end(owner, persona, "contract ended");
                continue;
            }
            Character companion = world.roster().companion(entry);
            if (contract.terms().maxLevelGap() > 0 && companion != null
                    && owner.getLevel() - companion.getLevel() > contract.terms().maxLevelGap()) {
                end(owner, persona, "outlevelled");
            }
        }
        for (RosterEntry entry : world.store().allBenched()) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null || entry.state() != RosterState.BENCHED || persona.benchToleranceDays() < 0) {
                continue;
            }
            if (persona.benchToleranceDays() == 0 && now - entry.benchedAtMs() > 3_600_000L
                    || persona.benchToleranceDays() > 0 && now - entry.benchedAtMs() > persona.benchToleranceDays() * 86_400_000L) {
                Character owner = world.roster().onlineCharacter(entry.characterId());
                // an owner just back, whose login has not called the crew back yet: a bench the logout made waits for it
                if (owner != null && !world.roster().awaitingLogin(owner.getId()) && authoredTermsHold(owner.getId(), persona.id())) {
                    end(owner, persona, "walked out");
                }
            }
        }
    }

    /** The owners' companions out with them: in the party (ACTIVE, TRAVELLING) or waiting outside a run (HOLD). */
    private List<RosterEntry> outWithOwners() {
        List<RosterEntry> out = new ArrayList<>(world.store().allActive());
        for (RosterEntry entry : world.store().allInTransit()) {
            if (entry.state() == RosterState.TRAVELLING || entry.state() == RosterState.HOLD) {
                out.add(entry);
            }
        }
        return out;
    }

    /** ROSTER_CONTRACT_MODE, as configured now. */
    public Mode mode() {
        return Mode.of(world.config().ROSTER_CONTRACT_MODE);
    }

    /** True while the owner's authored terms with the persona still end its contract (the bond is read only when the mode asks). */
    private boolean authoredTermsHold(int ownerId, String personaId) {
        Mode mode = mode();
        if (mode != Mode.PERMANENT_AT_SWORN) {
            return mode == Mode.AUTHORED;
        }
        Bond bond = world.bonds().peek(ownerId, personaId);
        return mode.authoredTermsHold(bond == null ? BondStage.NONE : bond.stage());
    }

    /**
     * Ends the contract (a rule the mode keeps, or an arc ending it explicitly in any mode): the companion says goodbye
     * and goes home by the release path, or once its run is over when it is committed to one.
     */
    public void end(Character owner, Persona persona, String reason) {
        leave(owner, persona, reason, leaveLine(persona, owner, "that's it for me. see you around."));
    }

    /** The owner parts ways with the companion; one committed to a run goes home when the run is over. */
    public String dismiss(Character owner, Persona persona) {
        RosterEntry entry = world.roster().entry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        if (afterTheRun(owner.getId(), owner, persona, "dismissed")) {
            return persona.name() + " heads home once this run is over.";
        }
        world.residents().reply(persona, owner, leaveLine(persona, owner, partingLine(owner, persona)));
        return world.roster().release(owner, persona, "dismissed");
    }

    /** The farewell when the persona has no authored leave lines: warm once there is a bond, curt without one. */
    private String partingLine(Character owner, Persona persona) {
        Bond bond = world.bonds() == null ? null : world.bonds().peek(owner.getId(), persona.id());
        boolean known = bond != null && bond.stage() != null && bond.stage().atLeast(BondStage.MET);
        return known ? "take care. you know where to find me." : "fine.";
    }

    private void leave(Character owner, Persona persona, String reason, String farewell) {
        if (afterTheRun(owner.getId(), owner, persona, reason)) {
            return;
        }
        world.residents().reply(persona, owner, farewell);
        owner.dropMessage(5, world.roster().release(owner, persona, reason));
    }

    private String leaveLine(Persona persona, Character owner, String fallback) {
        List<String> lines = world.arcs().lines(persona, "leave");
        return lines.isEmpty() ? fallback : lines.get(Math.floorMod(owner.getId(), lines.size()));
    }

    // ---- walk-outs after a run (Commitments) ---------------------------------------------

    /**
     * True when the companion is committed to a run (Commitments): its walk-out waits for the commitment to end, and
     * the first time it comes due the companion says so in its own words ("after this run, i'm heading home."), to its
     * owner when they are online. The first reason due is the one it goes for.
     */
    private boolean afterTheRun(int ownerId, Character owner, Persona persona, String reason) {
        int characterId = world.residents().characterIdOf(persona.id());
        Commitments commitments = world.commitments();
        if (characterId == 0 || commitments == null || !commitments.isCommitted(characterId)) {
            return false;
        }
        if (afterTheRun.putIfAbsent(characterId, new AfterTheRun(ownerId, persona.id(), reason)) == null) {
            if (owner != null) {
                List<String> lines = world.arcs().lines(persona, "leaveAfterRun");
                world.residents().reply(persona, owner, lines.isEmpty() ? AFTER_THIS_RUN_LINE
                        : lines.get(Math.floorMod(ownerId, lines.size())));
            }
            world.store().event(ownerId, persona.id(), "contract.after_the_run", reason);
        }
        return true;
    }

    /**
     * A commitment ended (the Commitments listener LivingWorld hands to companion-ops): each member's walk-out that
     * waited for it goes now by the normal release path, with its goodbye; one committed to another run meanwhile
     * waits for that one. With its owner offline by then it goes home all the same, without a word.
     */
    public void commitmentEnded(Commitments.Ended ended) {
        for (Commitments.Member member : ended.members()) {
            AfterTheRun due = afterTheRun.remove(member.characterId());
            Persona persona = due == null ? null : world.content().persona(due.personaId());
            RosterEntry entry = persona == null ? null : world.store().rosterEntry(due.ownerId(), persona.id());
            if (entry == null || entry.state() == RosterState.RELEASED) {
                continue;
            }
            Character owner = world.roster().onlineCharacter(due.ownerId());
            if (afterTheRun(due.ownerId(), owner, persona, due.reason())) {
                continue;
            }
            if (owner == null) {
                world.roster().release(due.ownerId(), persona, due.reason());
                continue;
            }
            world.residents().reply(persona, owner, leaveLine(persona, owner, "that's it for me. see you around."));
            owner.dropMessage(5, world.roster().release(owner, persona, due.reason()));
        }
    }

    // ---- bench warning -------------------------------------------------------------------

    /**
     * How long the companion sits on the bench before it walks out ("an hour", "a day"), when a bench could end its
     * contract: a low-tolerance persona (benchToleranceDays 0 or 1) whose authored terms still hold under
     * ROSTER_CONTRACT_MODE (AUTHORED, or PERMANENT_AT_SWORN before SWORN). Null when benching it risks nothing, which
     * is always so in PERMANENT_ON_ROSTER. The menus ask Yes/No before such a bench.
     */
    public String benchWalkOutAfter(int ownerId, Persona persona) {
        int days = persona.benchToleranceDays();
        if (days < 0 || days > LOW_BENCH_TOLERANCE_DAYS || !authoredTermsHold(ownerId, persona.id())) {
            return null;
        }
        return days == 0 ? "an hour" : "a day";
    }

    /** What such a companion says as it goes to camp, in its own words (the warning a chat bench gets); else null. */
    public String benchWarning(int ownerId, Persona persona) {
        String after = benchWalkOutAfter(ownerId, persona);
        if (after == null) {
            return null;
        }
        List<String> lines = world.arcs().lines(persona, "benchWarning");
        return lines.isEmpty() ? after + " at camp with nothing to do and i walk."
                : lines.get(Math.floorMod(ownerId, lines.size()));
    }

    /** Called by the roster whenever a persona leaves a roster, whatever the reason (its owner may be offline or gone). */
    public void onReleased(int ownerId, Persona persona, String reason) {
        afterTheRun.values().removeIf(due -> due.ownerId() == ownerId && due.personaId().equals(persona.id()));
        Bond bond = world.bonds().peek(ownerId, persona.id());
        if (bond == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int penalty = "dismissed".equals(reason) ? -5 : 0;
        world.bonds().save(new Bond(bond.characterId(), bond.personaId(), bond.stage(), Math.max(0, bond.points() + penalty),
                bond.flags(), ContractState.ENDED, bond.contractStartedAtMs(), 0L, 0, 0L, bond.partySeconds(), now, now));
        world.store().event(ownerId, persona.id(), "contract.ended", reason);
    }

    // ---- text ----------------------------------------------------------------------------

    private String describePrice(Contract contract, long mesoDue, Bond bond) {
        List<String> parts = new ArrayList<>();
        long owed = Math.max(0L, mesoDue - bond.paidMeso());
        if (owed > 0) {
            parts.add(owed + " meso");
        }
        for (ContractItem item : contract.price().items()) {
            parts.add(itemName(item.itemId()) + (item.count() > 1 ? " x" + item.count() : "") + (item.keep() ? " (i keep it)" : " (show me)"));
        }
        if (contract.price().proof() != null) {
            parts.add(contract.price().proof().count() + " kills of " + mobName(contract.price().proof().mobIds().get(0))
                    + " counted from now");
        }
        String terms = contract.terms().durationHours() > 0 ? " I stay " + contract.terms().durationHours() + " hours of travel." : "";
        return (parts.isEmpty() ? "No price." : String.join(", ", parts) + ".") + terms;
    }

    private String refusal(Persona persona, String blocker, Character chr) {
        List<String> lines = world.arcs().lines(persona, "refuse." + blocker);
        if (lines.isEmpty()) {
            lines = world.arcs().lines(persona, "refuse");
        }
        if (!lines.isEmpty()) {
            return lines.get(Math.floorMod(chr.getId(), lines.size()));
        }
        return switch (blocker) {
            case "not-recruitable" -> "i don't travel.";
            case "already-yours" -> "i'm already with you.";
            case "taken" -> "i'm spoken for right now.";
            case "bond" -> "i don't know you well enough.";
            case "flags", "arc" -> "there's something you'd need to understand first.";
            case "cooldown" -> "not so soon after last time.";
            case "roster-full" -> "you've got enough people following you around.";
            default -> "no.";
        };
    }

    private static String itemName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name;
    }

    private static String mobName(int mobId) {
        var monster = server.life.LifeFactory.getMonster(mobId);
        return monster == null ? "monsters" : monster.getName();
    }
}
