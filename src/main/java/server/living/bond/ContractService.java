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
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recruitment: when a Persona offers its terms, what the player must pay or prove, the join
 * itself (roster + party), the contract clock while they travel together, and the ways it ends.
 */
public final class ContractService {
    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final LivingWorld world;

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

    /** The player asked the persona to join. States terms, or completes the join when they are met. */
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
        return execute(chr, persona, contract, bond, mesoDue);
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
        String result = world.roster().recruit(chr, persona);
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
            if (contract == null || !contract.terms().leavesOn().contains(LeaveRule.LEADER_DEATH_3X)) {
                continue;
            }
            int count = deaths.merge(owner.getId() + ":" + persona.id(), 1, Integer::sum);
            if (count >= 3) {
                deaths.remove(owner.getId() + ":" + persona.id());
                List<String> lines = world.arcs().lines(persona, "leaveDeaths");
                world.residents().reply(persona, owner, lines.isEmpty() ? "three times. i'm not dying for this." : lines.get(0));
                owner.dropMessage(5, world.roster().release(owner, persona, "leader died three times"));
            } else {
                List<String> lines = world.arcs().lines(persona, "ownerDied");
                if (!lines.isEmpty()) {
                    world.residents().reply(persona, owner, lines.get(Math.floorMod(count, lines.size())));
                }
            }
        }
    }

    // ---- clock ---------------------------------------------------------------------------

    public void tick() {
        long now = System.currentTimeMillis();
        for (RosterEntry entry : world.store().allActive()) {
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
                if (owner != null) {
                    end(owner, persona, "walked out");
                }
            }
        }
    }

    public void end(Character owner, Persona persona, String reason) {
        List<String> lines = world.arcs().lines(persona, "leave");
        world.residents().reply(persona, owner, lines.isEmpty() ? "that's it for me. see you around." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        owner.dropMessage(5, world.roster().release(owner, persona, reason));
    }

    public String dismiss(Character owner, Persona persona) {
        RosterEntry entry = world.roster().entry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        List<String> lines = world.arcs().lines(persona, "leave");
        world.residents().reply(persona, owner, lines.isEmpty() ? "fine." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        return world.roster().release(owner, persona, "dismissed");
    }

    /** Called by the roster whenever a persona leaves a roster, whatever the reason. */
    public void onReleased(Character owner, Persona persona, String reason) {
        Bond bond = world.bonds().peek(owner.getId(), persona.id());
        if (bond == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int penalty = "dismissed".equals(reason) ? -5 : 0;
        world.bonds().save(new Bond(bond.characterId(), bond.personaId(), bond.stage(), Math.max(0, bond.points() + penalty),
                bond.flags(), ContractState.ENDED, bond.contractStartedAtMs(), 0L, 0, 0L, bond.partySeconds(), now, now));
        world.store().event(owner.getId(), persona.id(), "contract.ended", reason);
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
