package server.living.crew;

import client.Character;
import config.LivingWorldConfig;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.combat.AgentCombatTargetEligibilityPolicy;
import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.life.Monster;
import server.living.LivingWorld;
import server.living.audit.TellAudit;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.crew.CrewTies.SharedClear;
import server.living.crew.CrewTieStore.RunRow;
import server.living.imperfections.Imperfections;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.population.records.Clear;
import server.living.population.records.QuestLabels;
import server.living.roster.PqEntrance;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crew ties and banter in the living world (docs/BOT_CONCEPT_PLAN.md F6.4): the ties (always, a view read at each
 * owner's login), the banter (only while living_world.CREW_BANTER_ENABLED), the catalog, and the live ports both use.
 * The hooks hand everything to companion-ops; nothing here blocks a login, a chat line or a clear.
 */
public final class Crew {
    private static final Logger log = LoggerFactory.getLogger(Crew.class);

    private final LivingWorld world;
    private final CrewTieStore store = new CrewTieStore();
    private final CrewTies ties;
    private final CrewBanter banter;
    private volatile BanterCatalog catalog;

    public Crew(LivingWorld world) {
        this.world = world;
        this.ties = new CrewTies(new TiePorts());
        this.banter = new CrewBanter(new BanterPorts(), ties, this::catalog, new FactSources());
    }

    public CrewTies ties() {
        return ties;
    }

    public CrewBanter banter() {
        return banter;
    }

    BanterCatalog catalog() {
        BanterCatalog loaded = catalog;
        if (loaded == null) {
            loaded = BanterCatalog.load(Path.of(world.config().CONTENT_DIR));
            catalog = loaded;
        }
        return loaded;
    }

    /** {@code !world reload}: banter.yaml is read again at its next use. */
    public void reload() {
        catalog = null;
    }

    // ---- hooks (any thread: they hand over to companion-ops) ---------------------------------------

    /** An owner logged in: its shared clears are read (whatever the switch) and its banter starts afresh. */
    public void loggedIn(Character chr) {
        if (chr == null || AgentPresence.isAgent(chr) || !world.roster().isOwner(chr.getId())) {
            return;
        }
        int ownerId = chr.getId();
        world.executor().submit("crew.login " + ownerId, () -> {
            try {
                ties.load(ownerId);
                banter.loggedIn(ownerId);
            } catch (RuntimeException failure) {
                log.warn("crew ties of {} were not loaded at login", ownerId, failure);
            }
        });
    }

    public void loggedOut(Character chr) {
        if (chr == null || AgentPresence.isAgent(chr)) {
            return;
        }
        int ownerId = chr.getId();
        world.executor().submit("crew.logout " + ownerId, () -> {
            Character back = world.roster().onlineCharacter(ownerId);
            if (back != null && back != chr) {
                return; // on again in another session (a channel change): its login read the crew already
            }
            ties.drop(ownerId);
            banter.loggedOut(ownerId);
        });
    }

    /** An owner's map or party chat line: a whole "quiet" or "talk more" goes to companion-ops (only while on). */
    public void heard(Character speaker, String text) {
        if (speaker == null || !world.config().CREW_BANTER_ENABLED || AgentPresence.isAgent(speaker)
                || !world.roster().isOwner(speaker.getId()) || CrewBanter.muteWord(text) == null) {
            return;
        }
        int ownerId = speaker.getId();
        world.executor().submit("crew.banter.heard " + ownerId, () -> banter.heard(ownerId, text));
    }

    /** A clear was booked (ClearLog, on companion-ops): the ties count it and the banter may mark the moment. */
    public void booked(Clear clear) {
        for (SharedClear shared : ties.booked(clear)) {
            banter.cleared(shared);
        }
    }

    // ---- the live ports -------------------------------------------------------------------------

    private static Character loadedAgent(int characterId) {
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
    }

    private final class TiePorts implements CrewTies.Ports {
        @Override
        public Persona persona(String personaId) {
            return world.content().persona(personaId);
        }

        @Override
        public Collection<Persona> personas() {
            return world.content().personas().values();
        }

        @Override
        public int characterIdOf(String personaId) {
            return world.residents().characterIdOf(personaId);
        }

        @Override
        public String personaIdOfCharacter(int characterId) {
            return world.directory().personaIdOfCharacter(characterId);
        }

        @Override
        public int ownerOf(String personaId) {
            return world.roster().cachedOwnerOf(personaId);
        }

        @Override
        public int partnerIdOf(int characterId) {
            Character loaded = loadedAgent(characterId);
            return loaded == null ? -1 : loaded.getPartnerId();
        }

        @Override
        public List<SharedClear> clearsWith(int ownerId) {
            return store.clearsWith(ownerId);
        }

        @Override
        public Map<TieKind, Integer> seeds() {
            return catalog().seeds();
        }

        @Override
        public int clearPoints() {
            return world.config().CREW_TIE_CLEAR_POINTS;
        }

        @Override
        public int friendAffinity() {
            return world.config().CREW_TIE_FRIEND_AFFINITY;
        }
    }

    private final class BanterPorts implements CrewBanter.Ports {
        @Override
        public boolean on() {
            return world.config().CREW_BANTER_ENABLED;
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public CrewBanter.Settings settings() {
            LivingWorldConfig config = world.config();
            return new CrewBanter.Settings(config.CREW_BANTER_TICK_MS, config.CREW_BANTER_GAP_MINUTES * 60_000L,
                    config.CREW_BANTER_CHANCE, config.CREW_BANTER_AFTER_CLEAR_WINDOW_MS, config.CREW_BANTER_OWNER_IDLE_MS);
        }

        @Override
        public Collection<Integer> onlineOwners() {
            List<Integer> out = new ArrayList<>();
            for (Character chr : Server.getInstance().getWorld(world.config().WORLD).getPlayerStorage().getAllCharacters()) {
                if (!AgentPresence.isAgent(chr) && chr.getParty() != null && world.roster().isOwner(chr.getId())) {
                    out.add(chr.getId());
                }
            }
            return out;
        }

        @Override
        public CrewBanter.Scene scene(int ownerId) {
            Character owner = world.roster().onlineCharacter(ownerId);
            MapleMap map = owner == null ? null : owner.getMap();
            Party party = owner == null ? null : owner.getParty();
            Point at = owner == null ? null : owner.getPosition();
            if (map == null || party == null || at == null || AgentPresence.isAgent(owner)) {
                return null;
            }
            int range = world.config().CREW_BANTER_CALM_RANGE_PX;
            List<Monster> mobs = AgentMapPerception.monsters(map);
            boolean calm = owner.isAlive() && calmAround(mobs, at, range);
            List<CrewBanter.Speaker> speakers = new ArrayList<>();
            Imperfections imperfections = Imperfections.active();
            for (PartyCharacter member : party.getMembers()) {
                int id = member.getId();
                if (id == ownerId || world.directory().ownerOfCharacter(id) != ownerId) {
                    continue;
                }
                Character bot = map.getCharacterById(id);
                String personaId = world.directory().personaIdOfCharacter(id);
                if (bot == null || !bot.isAlive() || personaId == null || world.commitments().isCommitted(id)
                        || imperfections != null && imperfections.companionAway(id)
                        || world.mentors() != null && world.mentors().leading(id)) {
                    continue;
                }
                if (bot.getPosition() != null && !calmAround(mobs, bot.getPosition(), range)) {
                    calm = false;
                }
                speakers.add(new CrewBanter.Speaker(id, personaId, bot.getName(), bot.getLevel(), styleOf(personaId, id)));
            }
            for (Monster mob : mobs) {
                if (mob.isBoss() && mob.isAlive()) {
                    calm = false; // a boss map is never a calm moment
                }
            }
            boolean instance = map.getEventInstance() != null || world.commitments().ownerCommitted(ownerId);
            return new CrewBanter.Scene(ownerId, owner.getName(), party.getId(), map.getId(), map.isTown(), instance,
                    calm, at.x, at.y, speakers);
        }

        @Override
        public boolean say(CrewBanter.Speaker speaker, String line) {
            Character bot = loadedAgent(speaker.characterId());
            if (bot == null || !world.roster().sayInParty(bot, line)) {
                return false;
            }
            TellAudit.chatLine(bot.getId(), bot.getMapId(), "party", line, System.currentTimeMillis());
            return true;
        }

        @Override
        public void later(String what, long delayMs, Runnable work) {
            if (delayMs <= 0L) {
                world.executor().submit(what, work);
                return;
            }
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                    () -> world.executor().submit(what, work), delayMs);
        }

        @Override
        public void persistMute(int ownerId, boolean muted) {
            world.store().event(ownerId, null, CrewBanter.MUTE_EVENT, muted ? "on" : "off");
        }

        @Override
        public Boolean storedMute(int ownerId) {
            java.util.Optional<LivingStore.EventRow> row =
                    world.store().latestCharacterEvent(ownerId, List.of(CrewBanter.MUTE_EVENT));
            return row == null ? null : row.isPresent() && "on".equals(row.get().detail());
        }

        @Override
        public boolean phrasePackAnswers(String line) {
            String resolved = world.phrases() == null ? null : world.phrases().resolve(line);
            return resolved != null && resolved.startsWith("reply:");
        }

        @Override
        public String render(CrewBanter.Speaker speaker, String line, long seed) {
            Citizen citizen = speaker.style() == null ? null : citizen(speaker.characterId());
            if (citizen == null) {
                return line;
            }
            boolean number = line.chars().anyMatch(java.lang.Character::isDigit);
            return number ? Population.get().voices().renderAnswer(citizen, line, seed, false)
                    : Population.get().voices().render(citizen, line, seed);
        }

        @Override
        public long typingMs(CrewBanter.Speaker speaker, String line) {
            Citizen citizen = speaker.style() == null ? null : citizen(speaker.characterId());
            if (citizen != null) {
                return Population.get().voices().delayMs(citizen, line);
            }
            return Math.min(CrewBanter.MAX_ANSWER_DELAY_MS, 600L + CrewBanter.RESIDENT_MS_PER_CHAR * line.length());
        }

        @Override
        public String questLabel(String script) {
            return Crew.this.questLabel(script);
        }

        /** No hostile living mob within {@code range} of {@code here} (AgentFumbles.calmTown's test, on any map). */
        private boolean calmAround(List<Monster> mobs, Point here, int range) {
            for (Monster mob : mobs) {
                Point there = mob.getPosition();
                if (AgentCombatTargetEligibilityPolicy.isHostileLivingMonster(mob)
                        && (there == null || there.distanceSq(here) <= (double) range * range)) {
                    return false;
                }
            }
            return true;
        }

        /** A hire's citizen voice style; null for a Resident (its lines as written). */
        private String styleOf(String personaId, int characterId) {
            if (!LivingContent.isHire(personaId)) {
                return null;
            }
            Citizen citizen = citizen(characterId);
            return citizen == null ? null : Population.get().voices().styleOf(citizen);
        }

        private Citizen citizen(int characterId) {
            Population population = Population.get();
            return population == null ? null : population.citizen(characterId);
        }
    }

    private final class FactSources implements RivalFacts.Sources {
        @Override
        public List<String> activities() {
            List<String> keys = new ArrayList<>(PqEntrance.keys());
            for (AgentBossRunDefinition definition : AgentBossRunCatalog.all()) {
                if (!keys.contains(definition.key())) {
                    keys.add(definition.key());
                }
            }
            return keys;
        }

        @Override
        public int[] record(String activity, int companionId) {
            if (world.partyQuests() == null) {
                return null;
            }
            LivingStore.CompanionRunStats stats = world.partyQuests().records().record(activity, companionId);
            return new int[] {stats.runs(), stats.clears()};
        }

        @Override
        public List<RunRow> runs(int ownerId, int first, int second, int limit) {
            return store.runs(ownerId, first, second, limit);
        }

        @Override
        public Map<String, Long> bests(int ownerId, int companionId) {
            return ties.bests(ownerId, companionId);
        }

        @Override
        public String activityLabel(String activity) {
            AgentBossRules.Boss rules = AgentBossRules.of(activity);
            return rules == null ? activity.toLowerCase(Locale.ROOT) : rules.label().toLowerCase(Locale.ROOT);
        }

        @Override
        public String questLabel(String script) {
            return Crew.this.questLabel(script);
        }
    }

    /** What players call a quest's event script: its first alias in rivals.yaml ("kpq"), else its label. */
    private String questLabel(String script) {
        Population population = Population.get();
        QuestLabels labels = population == null || population.clears() == null ? null : population.clears().labels();
        if (labels != null) {
            for (QuestLabels.Quest quest : labels.all()) {
                if (quest.script().equals(script)) {
                    return RivalFacts.spoken(quest.label(), quest.aliases());
                }
            }
        }
        return script == null ? "" : script.toLowerCase(Locale.ROOT);
    }
}
