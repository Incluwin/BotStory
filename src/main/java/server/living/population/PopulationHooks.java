package server.living.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.integration.AgentPresence;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.life.Monster;
import server.living.population.paper.PaperWriters;
import server.living.standing.HireStanding;
import server.maps.MapleMap;

import java.util.List;
import java.util.Set;

/**
 * Engine events fanned out to the population: reactions (level-ups, deaths, rare drops, bosses),
 * journals, and human chat near citizens. Called from {@link server.living.LivingWorldHooks};
 * every method is a no-op unless the population is running.
 */
public final class PopulationHooks {
    private static final Logger log = LoggerFactory.getLogger(PopulationHooks.class);
    /** Drops worth at least this much on the price index count as "rare" for reactions. */
    private static final long RARE_DROP_PRICE = 200_000L;

    private PopulationHooks() {
    }

    private static int channelOf(Character chr) {
        return chr.getClient() == null ? 1 : chr.getClient().getChannel();
    }

    /**
     * A player's stock alliance invite reached {@code master}, a guild master who is a bot: a loaded citizen master
     * answers it after a moment (CitizenAlliances.invited); anyone else leaves it to expire as stock.
     */
    public static void allianceInvited(Character inviter, Character master, int allianceId) {
        Population population = Population.get();
        if (population == null || inviter == null || master == null) {
            return;
        }
        try {
            population.social().alliances().invited(inviter, master, allianceId);
        } catch (RuntimeException failure) {
            log.debug("population allianceInvited hook failed", failure);
        }
    }

    /** A guild joined an alliance through the stock accept: a citizen of a bot guild in it welcomes them. */
    public static void allianceJoined(int allianceId, int guildId) {
        Population population = Population.get();
        if (population == null) {
            return;
        }
        try {
            population.social().alliances().joined(allianceId, guildId);
        } catch (RuntimeException failure) {
            log.debug("population allianceJoined hook failed", failure);
        }
    }

    public static void levelUp(Character chr) {
        Population population = Population.get();
        if (population == null || chr == null || chr.getMap() == null || server.living.resident.ResidentShaper.shaping()) {
            return;
        }
        try {
            CitizenSession self = population.presence().sessionOf(chr);
            // early levels fly by; only every few of them are worth a "ding" (10+ always are)
            boolean worthMentioning = chr.getLevel() >= 10 || chr.getLevel() % 5 == 0;
            if (self != null) {
                self.journal.add("level", "reached level " + chr.getLevel());
                if (worthMentioning) {
                    population.chatter().event(ChatterEngine.Event.of("levelup.self", chr.getMapId(), channelOf(chr)).self(self).subject(chr));
                }
            }
            if (worthMentioning) {
                population.chatter().event(ChatterEngine.Event.of("levelup.other", chr.getMapId(), channelOf(chr)).subject(chr));
            }
            Boolean human = paperSubject(population, chr); // the town paper's milestones (N1.2)
            if (human != null) {
                PaperWriters.levelReached(chr.getName(), chr.getLevel(), human);
            }
            population.guildChat().levelUp(chr, System.currentTimeMillis()); // a guild mate's gz (N5.1)
        } catch (RuntimeException failure) {
            log.debug("population levelUp hook failed", failure);
        }
    }

    public static void jobAdvanced(Character chr) {
        Population population = Population.get();
        if (population == null || chr == null || chr.getMap() == null || server.living.resident.ResidentShaper.shaping()) {
            return;
        }
        try {
            CitizenSession self = population.presence().sessionOf(chr);
            if (self != null) {
                self.journal.add("job", "became a " + chr.getJob().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
            }
            population.chatter().event(ChatterEngine.Event.of("jobadvance.other", chr.getMapId(), channelOf(chr)).subject(chr));
            Boolean human = paperSubject(population, chr); // the town paper's 3rd and 4th jobs (N1.2)
            if (human != null) {
                PaperWriters.jobReached(chr.getName(), chr.getJob().getId(),
                        server.living.roster.CompanionView.jobName(chr.getJob().getId()), human);
            }
        } catch (RuntimeException failure) {
            log.debug("population jobAdvanced hook failed", failure);
        }
    }

    /** Who the paper writes up: TRUE for a player (not a GM), FALSE for a citizen, null for anyone else (a companion). */
    static Boolean paperSubject(Population population, Character chr) {
        if (!AgentPresence.isAgent(chr)) {
            return chr.isGM() ? null : Boolean.TRUE;
        }
        return population.isCitizen(chr.getId()) ? Boolean.FALSE : null;
    }

    public static void died(Character chr) {
        Population population = Population.get();
        if (population == null || chr == null || chr.getMap() == null) {
            return;
        }
        try {
            CitizenSession self = population.presence().sessionOf(chr);
            if (self != null) {
                CitizenSpots spots = population.fields().spots();
                if (spots != null) {
                    spots.died(self, System.currentTimeMillis()); // its TRAIN stint is booked with the death (F2.1)
                }
                self.journal.add("death", "died at " + population.fields().name(chr.getMapId()).toLowerCase(java.util.Locale.ROOT));
                int avoidMs = population.config().FIELD_BOSS_AVOID_MS;
                if (avoidMs > 0 && AgentBossField.near(chr.getMap())) {
                    // beside a field boss it never fights: it trains elsewhere while the boss stands (PresenceScheduler)
                    self.avoidUntilMs = System.currentTimeMillis() + avoidMs;
                    self.avoidMapId = chr.getMapId();
                }
                if (population.needs() != null && AgentBossField.near(chr.getMap())) { // it pins a bounty on it (N6.2)
                    population.needs().bounties().diedBeside(self, chr.getMap(), System.currentTimeMillis());
                }
                population.chatter().event(ChatterEngine.Event.of("death.self", chr.getMapId(), channelOf(chr)).self(self).subject(chr));
            }
            population.chatter().event(ChatterEngine.Event.of("death.other", chr.getMapId(), channelOf(chr)).subject(chr));
        } catch (RuntimeException failure) {
            log.debug("population died hook failed", failure);
        }
    }

    /** N6.3: the human who owns a field boss's drops when a citizen invited into their fight topped it; else null. */
    public static Character fieldBossDropOwner(Monster monster, Character candidate) {
        Population population = Population.get();
        return population == null || population.camps() == null ? null : population.camps().dropOwner(monster, candidate);
    }

    public static void mobKilled(Monster monster, Set<Integer> attackerIds, MapleMap map) {
        Population population = Population.get();
        if (population == null || monster == null || map == null) {
            return;
        }
        try {
            for (int attackerId : attackerIds) {
                CitizenSession session = population.presence().session(attackerId);
                if (session != null && session.journal.bump("kills") % 25 == 1) {
                    session.journal.add("kills", "killed " + monster.getName().toLowerCase(java.util.Locale.ROOT) + "s at "
                            + population.fields().name(map.getId()).toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (population.killSteals() != null
                    && (population.worldEvents() == null || !population.worldEvents().isInvader(monster))) {
                population.killSteals().killed(monster, attackerIds, map); // who took whose kill (N2.4)
            }
            if (population.fieldBossKills() != null) { // a field boss's record and bounties (WORLD_SYSTEMS_PLAN N6)
                population.fieldBossKills().killed(monster, map, attackerIds, System.currentTimeMillis());
            }
            if (population.needs() != null) { // a hunt bounty's kill (N6.2): one map read for a monster none names
                population.needs().bounties().mobKilled(monster, System.currentTimeMillis());
            }
            if (monster.isBoss() && monster.getStats().getLevel() >= 30) {
                int channel = map.getChannelServer() == null ? 1 : map.getChannelServer().getId();
                population.chatter().event(ChatterEngine.Event.of("boss", map.getId(), channel).subject(monster.getName(), monster.getLevel()));
            }
        } catch (RuntimeException failure) {
            log.debug("population mobKilled hook failed", failure);
        }
    }

    public static void itemGained(Character chr, int itemId, int quantity) {
        Population population = Population.get();
        if (population == null || chr == null || chr.getMap() == null || quantity <= 0) {
            return;
        }
        try {
            if (population.market().price(itemId) < RARE_DROP_PRICE) {
                return;
            }
            String name = ItemInformationProvider.getInstance().getName(itemId);
            String item = name == null ? "something rare" : name.toLowerCase(java.util.Locale.ROOT);
            CitizenSession self = population.presence().sessionOf(chr);
            if (self != null) {
                self.journal.add("drop", "got a " + item);
                population.chatter().event(ChatterEngine.Event.of("drop.rare.self", chr.getMapId(), channelOf(chr)).self(self).item(item));
            }
            population.chatter().event(ChatterEngine.Event.of("drop.rare", chr.getMapId(), channelOf(chr)).subject(chr).item(item));
        } catch (RuntimeException failure) {
            log.debug("population itemGained hook failed", failure);
        }
    }

    /**
     * A sale at a player shop or hired merchant, any seller and buyer: the price index learns its unit price
     * (docs/ECONOMY_PLAN.md E2), unless seller and buyer share an account (no learning from a player's alts). This runs
     * on the buyer's thread under the shop's lock, so it only queues the sale.
     */
    public static void shopSale(int sellerId, int sellerAccountId, Character buyer, int itemId, long grossPrice, int units) {
        Population population = Population.get();
        if (population == null || buyer == null || units <= 0 || buyer.getAccountID() == sellerAccountId) {
            return;
        }
        population.market().sold(sellerId, itemId, Math.round(grossPrice / (double) units));
    }

    /** A player famed ({@code delta} 1) or defamed (-1) someone: a citizen remembers it of them. */
    public static void famed(Character giver, Character target, int delta) {
        Population population = Population.get();
        if (population == null || population.memories() == null || giver == null || target == null
                || AgentPresence.isAgent(giver) || delta == 0) {
            return;
        }
        try {
            CitizenSession self = population.presence().sessionOf(target);
            PersonMemory.Kind kind = delta > 0 ? PersonMemory.Kind.FAMED : PersonMemory.Kind.DEFAMED;
            if (self != null) {
                population.memories().record(self.characterId, giver.getId(), kind, target.getMapId(), "", 1);
            } else {
                HireStanding.remember(target.getId(), giver.getId(), kind, target.getMapId(), ""); // a hired one (C1.2)
            }
        } catch (RuntimeException failure) {
            log.debug("population famed hook failed", failure);
        }
    }

    /**
     * A party quest cleared ({@code scriptName}, e.g. KerningPQ): a citizen in a player's party (a guest) remembers
     * clearing it with each player of that party, and so does a hired one while HIRE_FRIENDSHIP_ENABLED (it has no
     * session). A run citizens lead themselves is CitizenPartyQuests' to remember, with how each player left it.
     */
    public static void partyQuestCleared(List<Character> players, String scriptName) {
        Population population = Population.get();
        if (population == null || population.memories() == null || players == null || players.isEmpty()) {
            return;
        }
        try {
            CitizenPartyQuests runs = population.partyQuests();
            String quest = questName(scriptName);
            for (Character member : players) {
                CitizenSession self = member == null ? null : population.presence().sessionOf(member);
                if (member == null || member.getParty() == null
                        || self != null && runs != null && runs.holds(self.characterId)) {
                    continue;
                }
                for (Character player : players) {
                    if (player != null && !AgentPresence.isAgent(player) && player.getParty() != null
                            && player.getParty().getId() == member.getParty().getId()) {
                        if (self != null) {
                            population.memories().record(self.characterId, player.getId(), PersonMemory.Kind.PQ_CLEAR, 0,
                                    quest, 1);
                        } else { // a hired citizen has no session, and remembers its runs all the same (C1.2)
                            HireStanding.remember(member.getId(), player.getId(), PersonMemory.Kind.PQ_CLEAR, 0, quest);
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.debug("population partyQuestCleared hook failed", failure);
        }
    }

    /** A cleared event instance, for the clear records (F7.1, records.ClearLog); memory only on this thread. */
    public static void eventCleared(List<Character> players, String scriptName, String eventName, long startedAtMs) {
        Population population = Population.get();
        if (population == null || population.clears() == null) {
            return;
        }
        try {
            population.clears().cleared(players, scriptName, eventName, startedAtMs, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            log.debug("population eventCleared hook failed", failure);
        }
    }

    /** What players call a party quest's event script: KerningPQ is "kpq"; one not listed by its name. */
    static String questName(String scriptName) {
        if (scriptName == null || scriptName.isBlank()) {
            return "";
        }
        return switch (scriptName) {
            case "KerningPQ" -> "kpq";
            case "HenesysPQ" -> "hpq";
            case "LudiPQ" -> "lpq";
            case "OrbisPQ" -> "opq";
            case "PiratePQ" -> "ppq";
            case "EllinPQ" -> "epq";
            case "AmoriaPQ" -> "apq";
            case "LudiMazePQ" -> "lmpq";
            case "MagatiaPQ_A", "MagatiaPQ_Z" -> "mpq";
            case "CWKPQ" -> "cwkpq";
            default -> scriptName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    /** A human's whisper to a citizen by name (docs/BOT_CONCEPT_PLAN.md F5c); true when a citizen took it. */
    public static boolean whisper(Character speaker, String targetName, String text) {
        Population population = Population.get();
        if (population == null || speaker == null || AgentPresence.isAgent(speaker)) {
            return false;
        }
        try {
            return population.whispers().onWhisper(speaker, targetName, text);
        } catch (RuntimeException failure) {
            log.debug("population whisper hook failed", failure);
            return false;
        }
    }

    /**
     * Human map chat answering a rival crew's open duel challenge ("yes", "nah"; docs/PVP_PLAN.md V3); true when it did.
     * Asked before the casino and any companion or Resident, so nobody else takes the player's "yes".
     */
    public static boolean rivalChallenge(Character speaker, String text) {
        Population population = Population.get();
        if (population == null || speaker == null || population.rivals() == null || AgentPresence.isAgent(speaker)) {
            return false;
        }
        try {
            return population.rivals().challenges().heard(speaker, text);
        } catch (RuntimeException failure) {
            log.debug("population rival challenge hook failed", failure);
            return false;
        }
    }

    /**
     * Human map chat said to a casino table or host near them ("deal me in", "hit", "cho", "drink?",
     * docs/SOLOMAPLING_PORT.md); true when one took it. LivingWorldHooks asks this before a companion or Resident may
     * answer: live 2026-11-08 a companion's reply to "drink?" and "yes" kept the bar host from ever hearing them.
     */
    public static boolean casino(Character speaker, String text) {
        Population population = Population.get();
        if (population == null || speaker == null || population.casino() == null || AgentPresence.isAgent(speaker)) {
            return false;
        }
        try {
            return population.casino().heard(speaker, text);
        } catch (RuntimeException failure) {
            log.debug("population casino hook failed", failure);
            return false;
        }
    }

    /**
     * A human's line in their guild chat (multi-chat 2, docs/WORLD_SYSTEMS_PLAN.md N5.1): their guild's online
     * citizens may answer; true when one took it.
     */
    public static boolean guildChat(Character speaker, String text) {
        Population population = Population.get();
        if (population == null || speaker == null || AgentPresence.isAgent(speaker)) {
            return false;
        }
        try {
            return population.guildChat().heard(speaker, text, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            log.debug("population guild chat hook failed", failure);
            return false;
        }
    }

    /** A human guild member logged in or out (the stock guild online notice): a guild mate greets or sees them off. */
    public static void guildMemberOnline(Character chr, boolean online) {
        Population population = Population.get();
        if (population == null || chr == null || AgentPresence.isAgent(chr)) {
            return;
        }
        try {
            population.guildChat().memberOnline(chr, online, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            log.debug("population guild online hook failed", failure);
        }
    }

    /** Human map chat near citizens; true when a citizen took it. */
    public static boolean chat(Character speaker, String text) {
        Population population = Population.get();
        if (population == null || speaker == null || AgentPresence.isAgent(speaker)) {
            return false;
        }
        try {
            return population.conversation().onChat(speaker, text);
        } catch (RuntimeException failure) {
            log.debug("population chat hook failed", failure);
            return false;
        }
    }
}
