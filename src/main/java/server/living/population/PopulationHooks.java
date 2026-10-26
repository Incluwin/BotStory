package server.living.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.integration.AgentPresence;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.life.Monster;
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
        } catch (RuntimeException failure) {
            log.debug("population jobAdvanced hook failed", failure);
        }
    }

    public static void died(Character chr) {
        Population population = Population.get();
        if (population == null || chr == null || chr.getMap() == null) {
            return;
        }
        try {
            CitizenSession self = population.presence().sessionOf(chr);
            if (self != null) {
                self.journal.add("death", "died at " + population.fields().name(chr.getMapId()).toLowerCase(java.util.Locale.ROOT));
                int avoidMs = population.config().FIELD_BOSS_AVOID_MS;
                if (avoidMs > 0 && AgentBossField.near(chr.getMap())) {
                    // beside a field boss it never fights: it trains elsewhere while the boss stands (PresenceScheduler)
                    self.avoidUntilMs = System.currentTimeMillis() + avoidMs;
                    self.avoidMapId = chr.getMapId();
                }
                population.chatter().event(ChatterEngine.Event.of("death.self", chr.getMapId(), channelOf(chr)).self(self).subject(chr));
            }
            population.chatter().event(ChatterEngine.Event.of("death.other", chr.getMapId(), channelOf(chr)).subject(chr));
        } catch (RuntimeException failure) {
            log.debug("population died hook failed", failure);
        }
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

    /** A player famed ({@code delta} 1) or defamed (-1) someone: a citizen remembers it of them. */
    public static void famed(Character giver, Character target, int delta) {
        Population population = Population.get();
        if (population == null || population.memories() == null || giver == null || target == null
                || AgentPresence.isAgent(giver) || delta == 0) {
            return;
        }
        try {
            CitizenSession self = population.presence().sessionOf(target);
            if (self != null) {
                population.memories().record(self.characterId, giver.getId(),
                        delta > 0 ? PersonMemory.Kind.FAMED : PersonMemory.Kind.DEFAMED, target.getMapId(), "", 1);
            }
        } catch (RuntimeException failure) {
            log.debug("population famed hook failed", failure);
        }
    }

    /**
     * A party quest cleared ({@code scriptName}, e.g. KerningPQ): a citizen in a player's party (a guest) remembers
     * clearing it with each player of that party. A run citizens lead themselves is CitizenPartyQuests' to remember,
     * with how each player left it.
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
                if (self == null || member.getParty() == null || runs != null && runs.holds(self.characterId)) {
                    continue;
                }
                for (Character player : players) {
                    if (player != null && !AgentPresence.isAgent(player) && player.getParty() != null
                            && player.getParty().getId() == member.getParty().getId()) {
                        population.memories().record(self.characterId, player.getId(), PersonMemory.Kind.PQ_CLEAR, 0, quest, 1);
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.debug("population partyQuestCleared hook failed", failure);
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
