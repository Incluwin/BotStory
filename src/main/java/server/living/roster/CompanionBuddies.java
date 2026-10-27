package server.living.roster;

import client.BotClient;
import client.Character;
import net.server.Server;
import net.server.world.Party;
import server.living.BuddyRequests;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.Population;
import server.living.standing.HireStanding;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Companions ask you to be buddies (P9; decisions 2026-10-06, probe 8): a Resident whose friendship with you reached
 * COMPANION_BUDDY_FRIENDSHIP_LEVEL ("Friend"), and a hired hand after COMPANION_BUDDY_HIRE_PARTY_MINUTES in your party
 * (counted in living_acquaintance, as a citizen's) or, while HIRE_FRIENDSHIP_ENABLED, once its friendship with you
 * reaches that level too (docs/BOT_CONCEPT_PLAN.md C1.4), send you the stock buddy request once while you are both online
 * (BuddyRequests: it lists you first). A Resident remembers it asked in its bond's flags, a hired hand in the
 * acquaintance's buddy_asked, as a citizen does. Your own companions land in your "Crew" group as you accept
 * (BuddylistModifyHandler asks {@link #groupFor}); you move them like any friend. COMPANION_BUDDY_REQUESTS off: nobody
 * new asks, and accepts land in "Default Group" as before. Every {@link #SWEEP_MS} on companion-ops (store reads).
 */
public final class CompanionBuddies {
    /** How often the online players are looked at; a hired hand's party time grows a minute a look. */
    public static final long SWEEP_MS = 60_000L;
    /** The bond flag of a Resident that asked (or found itself on the list already). */
    static final String ASKED_FLAG = "buddy.asked";
    static final String CREW_GROUP = "Crew";
    static final String DEFAULT_GROUP = "Default Group";

    private final LivingWorld world;
    private final Supplier<Population> population;
    /** A character in the world by id, else null. */
    private final IntFunction<Character> online;

    public CompanionBuddies(LivingWorld world) {
        this(world, Population::get, id -> world.roster().onlineCharacter(id));
    }

    CompanionBuddies(LivingWorld world, Supplier<Population> population, IntFunction<Character> online) {
        this.world = world;
        this.population = population;
        this.online = online;
    }

    /** Companion-ops: the players online now (no bots) are looked at. */
    public void tick() {
        if (!world.config().COMPANION_BUDDY_REQUESTS) {
            return;
        }
        List<Character> players = new ArrayList<>();
        for (Character chr : Server.getInstance().getWorld(world.config().WORLD).getPlayerStorage().getAllCharacters()) {
            if (chr != null && !(chr.getClient() instanceof BotClient) && chr.isLoggedinWorld()) {
                players.add(chr);
            }
        }
        sweep(players);
    }

    void sweep(Collection<Character> players) {
        if (!world.config().COMPANION_BUDDY_REQUESTS) {
            return;
        }
        for (Character player : players) {
            residents(player);
            hiredHands(player);
        }
    }

    /** The group your accept puts {@code otherCharacterId} in: "Crew" for your own companion, else the stock one. */
    public String groupFor(Character player, int otherCharacterId) {
        CompanionDirectory directory = world.directory();
        return world.config().COMPANION_BUDDY_REQUESTS && directory != null
                && directory.ownerOfCharacter(otherCharacterId) == player.getId() ? CREW_GROUP : DEFAULT_GROUP;
    }

    private void residents(Character player) {
        int friend = world.config().COMPANION_BUDDY_FRIENDSHIP_LEVEL;
        for (Bond bond : world.store().bondsOf(player.getId())) {
            String personaId = bond.personaId();
            if (BondService.PLAYER_BUCKET.equals(personaId) || LivingContent.isHire(personaId) || bond.has(ASKED_FLAG)
                    || world.friendship().levelFor(bond.points()) < friend) {
                continue;
            }
            Persona persona = world.content().persona(personaId);
            Character bot = persona == null ? null : online.apply(world.residents().characterIdOf(persona.id()));
            if (bot != null && (player.getBuddylist().contains(bot.getId()) || BuddyRequests.ask(bot, player))) {
                world.bonds().setFlag(player.getId(), personaId, ASKED_FLAG);
            }
        }
    }

    private void hiredHands(Character player) {
        Population citizens = population.get();
        if (citizens == null) {
            return;
        }
        for (RosterEntry entry : world.store().roster(player.getId())) {
            if (!LivingContent.isHire(entry.personaId()) || entry.state() == RosterState.RELEASED) {
                continue;
            }
            int characterId = LivingContent.hireCharacterId(entry.personaId());
            Acquaintance known = citizens.store().acquaintance(characterId, player.getId());
            Character bot = online.apply(characterId);
            if (known.buddyAsked() || bot == null) {
                continue;
            }
            boolean together = sameParty(bot, player);
            int minutes = known.partyMinutes() + (together ? 1 : 0);
            boolean due = minutes >= world.config().COMPANION_BUDDY_HIRE_PARTY_MINUTES || friend(player, entry.personaId());
            boolean asked = due && (player.getBuddylist().contains(characterId) || BuddyRequests.ask(bot, player));
            if (together || asked) {
                citizens.store().saveAcquaintance(new Acquaintance(characterId, player.getId(), minutes, known.mapMinutes(),
                        asked, known.guildAsked()));
            }
        }
    }

    /** A hire's friendship with its owner reached COMPANION_BUDDY_FRIENDSHIP_LEVEL, while HIRE_FRIENDSHIP_ENABLED (C1.4). */
    private boolean friend(Character player, String hireId) {
        return HireStanding.on(world)
                && HireStanding.level(world, player.getId(), hireId) >= world.config().COMPANION_BUDDY_FRIENDSHIP_LEVEL;
    }

    private static boolean sameParty(Character bot, Character player) {
        Party mine = player.getParty();
        Party its = bot.getParty();
        return mine != null && its != null && mine.getId() == its.getId();
    }
}
