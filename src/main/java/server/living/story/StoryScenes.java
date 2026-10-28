package server.living.story;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.life.MonsterListener;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Scene;
import server.living.config.Content.SceneBeat;
import server.living.config.Content.SceneFoe;
import server.living.config.Content.SceneSpawn;
import server.living.config.Content.Story;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;
import server.living.roster.Commitments;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A SCENE step played out (docs/BOT_CONCEPT_PLAN.md F6.2): the owner and the companions in its cast go into a private
 * copy of a stock map ({@link Stagehand.Stage}), the companions speak their lines in map chat, effects from the safe list
 * play ({@link SceneEffect}), authored monsters come and are fought, and a race scene (F6.6, D14) pits two rivals against
 * one foe, its winner read from the damage each dealt. Then everyone is set down where the owner started and the step is
 * done ({@code QuestLedger.sceneDone}); its closing window waits until the owner has landed and no window is open.
 *
 * <p>Everything here runs on companion-ops, from the story sweep. The game threads only report into concurrent state:
 * who left the stage (a warp away, a revive, a disconnect, {@link SceneInstance}) and which monsters died. Every end goes
 * through {@link #leave}: each character is unregistered before it is warped out (so it lands on the channel's map), the
 * commitments end and the copy is let go. An abort (the owner leaves, the lead companion is lost, too long, the switch
 * goes off) leaves the step on SCENE, to be played again; nothing is stored mid-scene, so a restart loses only it.</p>
 */
public final class StoryScenes {
    private static final Logger log = LoggerFactory.getLogger(StoryScenes.class);
    /** The warp in and the companions' arrival, before the first beat. */
    static final long SETTLE_MS = 2500L;
    /** The lead companion off the stage this long after the start: the scene cannot go on. */
    static final long LOST_MS = 12_000L;
    /** Back at the exit, then the closing window (or the abort line). */
    static final long CLOSE_DELAY_MS = 1500L;
    /** A closing nobody could show within this long is dropped (the step stays SCENE). */
    static final long CLOSE_GIVE_UP_MS = 3_600_000L;
    static final long EFFECT_MS = 1500L;
    static final long TYPING_MS_PER_CHAR = 45L;
    static final long LINE_MAX_MS = 6000L;

    private final LivingWorld world;
    private final Stagehand hands;
    private final LongSupplier clock;
    private final Map<Integer, Run> runs = new ConcurrentHashMap<>();
    private final Map<Integer, Closing> closings = new ConcurrentHashMap<>();

    /** A scene the owner played (or that was cut short): the step's end, or the line that says it was. */
    record Closing(String questId, String storyId, boolean done, long atMs, long givenUpAtMs) { }

    /** One scene being played: its owner, cast and stage, and how far its beats are. */
    static final class Run {
        final int ownerId;
        final Story story;
        final Scene scene;
        final String questId;
        final String rival;
        final Map<String, Integer> cast; // persona id -> character id of the companions in the scene
        final Stagehand.Stage stage;
        final int exitMapId;
        final Point exitAt;
        final long startedAtMs;
        final long deadlineMs;
        final List<SceneBeat> beats;
        final Set<Monster> alive = ConcurrentHashMap.newKeySet();
        final Set<Integer> left = ConcurrentHashMap.newKeySet();
        final Map<Integer, Integer> shares = new ConcurrentHashMap<>();
        volatile boolean disconnected;
        volatile boolean racing;
        volatile boolean foeDown;
        volatile boolean banner;
        String outcome;
        int beat;
        long nextAtMs;

        Run(int ownerId, Story story, Scene scene, String questId, String rival, Map<String, Integer> cast,
            Stagehand.Stage stage, int exitMapId, Point exitAt, long now, long maxMs) {
            this.ownerId = ownerId;
            this.story = story;
            this.scene = scene;
            this.questId = questId;
            this.rival = rival;
            this.cast = cast;
            this.stage = stage;
            this.exitMapId = exitMapId;
            this.exitAt = exitAt;
            this.startedAtMs = now;
            this.deadlineMs = now + maxMs;
            this.beats = new ArrayList<>(scene.beats());
            this.nextAtMs = now + SETTLE_MS;
        }

        String key() {
            return "story.scene." + ownerId;
        }

        int lead() {
            Integer id = cast.get(story.persona());
            return id == null ? 0 : id;
        }
    }

    public StoryScenes(LivingWorld world) {
        this(world, Stagehand.LIVE, System::currentTimeMillis);
    }

    StoryScenes(LivingWorld world, Stagehand hands, LongSupplier clock) {
        this.world = world;
        this.hands = hands;
        this.clock = clock;
    }

    /** True when a scene or its closing waits: the story sweep has work. */
    boolean any() {
        return !runs.isEmpty() || !closings.isEmpty();
    }

    boolean running(int ownerId) {
        return runs.containsKey(ownerId);
    }

    Run run(int ownerId) {
        return runs.get(ownerId);
    }

    /** True for a scene's private map: no boss friendship there (LivingWorldHooks.mobKilled). */
    public static boolean onStage(MapleMap map) {
        return map != null && map.getEventInstance() instanceof SceneInstance;
    }

    // ---- starting -----------------------------------------------------------------------------

    /**
     * The owner asked to go (the card, a capstone's last page): the companion asks if they are ready, as the answer to
     * that click; yes starts it on companion-ops.
     */
    void ask(Character owner, Story story, String questId) {
        Persona lead = world.content().persona(story.persona());
        List<String> ready = story.lines("scene.ready");
        String text = ready.isEmpty() ? "ready? it's not far." : ready.get(Math.floorMod(owner.getId(), ready.size()));
        int ownerId = owner.getId();
        LivingTalk.open(owner, ResidentDialogue.portraitOf(lead))
                .yesNo(text, () -> world.executor().submit("story.scene.start " + ownerId,
                        () -> start(ownerId, story, questId)), () -> { })
                .show();
    }

    /**
     * Starts the scene of the quest's SCENE step, on companion-ops; null when it started, else why not, which the
     * companion says (the owner hears it in party chat, or as a whisper).
     */
    String start(int ownerId, Story story, String questId) {
        Character owner = world.roster().onlineCharacter(ownerId);
        String problem = owner == null ? "offline" : startProblem(owner, story, questId);
        if (problem != null) {
            if (owner != null) {
                say(owner, story, problem);
            }
            return problem;
        }
        var quest = world.content().quest(questId);
        var state = world.store().questState(ownerId, questId);
        Scene scene = world.content().scene(quest.step(state.stepIndex()).scene());
        String rival = story.rival() != null && questId.equals(story.rival().quest()) ? story.rival().persona() : null;
        Map<String, Character> present = present(owner, scene);
        Point exitAt = new Point(owner.getPosition());
        int exitMapId = owner.getMapId();
        Map<Integer, Character> movers = new LinkedHashMap<>();
        movers.put(ownerId, owner);
        Map<String, Integer> cast = new LinkedHashMap<>();
        for (Map.Entry<String, Character> member : present.entrySet()) {
            cast.put(member.getKey(), member.getValue().getId());
            movers.put(member.getValue().getId(), member.getValue());
        }
        long maxMs = Math.min(scene.maxMinutes(), world.config().STORY_SCENE_MAX_MINUTES) * 60_000L;
        Run[] made = new Run[1];
        SceneInstance.Exits exits = new SceneInstance.Exits() {
            @Override
            public void left(Character chr) {
                if (made[0] != null) {
                    made[0].left.add(chr.getId());
                }
            }

            @Override
            public void disconnected(Character chr) {
                if (made[0] != null) {
                    made[0].left.add(chr.getId());
                    made[0].disconnected |= chr.getId() == ownerId;
                }
            }
        };
        Stagehand.Stage stage = hands.open(owner, scene.mapId(), exitMapId, exits);
        if (stage == null) {
            say(owner, story, "hm. not today. let's try later.");
            return "no stage";
        }
        Run run = new Run(ownerId, story, scene, questId, rival, cast, stage, exitMapId, exitAt, clock.getAsLong(), maxMs);
        made[0] = run;
        runs.put(ownerId, run);
        Commitments commitments = world.commitments();
        for (Character chr : movers.values()) {
            stage.register(chr);
            if (chr != owner && commitments != null) {
                commitments.commit(run.key(), ownerId, chr.getId());
            }
        }
        MapleMap map = stage.map();
        Point at = Stagehand.landing(map, scene.x(), scene.y());
        hands.warp(owner, map, at);
        int index = 1;
        for (Character bot : present.values()) {
            hands.warp(bot, map, new Point(at.x + (index % 2 == 0 ? 40 : -40) * index, at.y));
            index++;
        }
        world.store().event(ownerId, story.persona(), "story.scene.start", scene.id() + " (" + questId + ") cast "
                + cast.keySet());
        log.info("Story scene {} for {} with {}", scene.id(), owner.getName(), cast.keySet());
        return null;
    }

    /** Why the scene cannot start now, in the companion's words; null when it can. */
    String startProblem(Character owner, Story story, String questId) {
        if (!world.config().PERSONAL_ARCS_ENABLED) {
            return "another time.";
        }
        if (runs.containsKey(owner.getId())) {
            return "we're already in the middle of it.";
        }
        var quest = world.content().quest(questId);
        var state = world.store().questState(owner.getId(), questId);
        var step = quest == null || state == null ? null : quest.step(state.stepIndex());
        Scene scene = step == null || step.scene() == null ? null : world.content().scene(step.scene());
        if (scene == null || state.status() != server.living.store.LivingStore.QuestStatus.ACTIVE) {
            return "never mind. it can wait.";
        }
        if (owner.getHp() <= 0 || owner.getEventInstance() != null || owner.getMap() == null) {
            return "not here. after this.";
        }
        Map<String, Character> present = present(owner, scene);
        if (!present.containsKey(story.persona())) {
            return "stay close. i'll show you when we're together.";
        }
        if (story.rival() != null && questId.equals(story.rival().quest()) && !present.containsKey(story.rival().persona())) {
            List<String> call = story.lines("rival.call");
            return call.isEmpty() ? "not without the other one. go get them." : call.get(Math.floorMod(owner.getId(), call.size()));
        }
        return null;
    }

    /**
     * The cast members out with the owner now: on their roster ACTIVE, loaded, alive, on the owner's map and in no run.
     */
    Map<String, Character> present(Character owner, Scene scene) {
        Map<String, Character> out = new LinkedHashMap<>();
        Commitments commitments = world.commitments();
        for (String personaId : scene.cast()) {
            RosterEntry entry = world.roster().entry(owner.getId(), personaId);
            Character bot = entry == null || entry.state() != RosterState.ACTIVE ? null : world.roster().companion(entry);
            if (bot == null || bot.getHp() <= 0 || bot.getMap() == null || bot.getMap() != owner.getMap()
                    || bot.getEventInstance() != null || commitments != null && commitments.isCommitted(bot.getId())) {
                continue;
            }
            out.put(personaId, bot);
        }
        return out;
    }

    // ---- playing (companion-ops, the story sweep) ---------------------------------------------

    void tick() {
        long now = clock.getAsLong();
        for (Run run : new ArrayList<>(runs.values())) {
            try {
                step(run, now);
            } catch (RuntimeException failure) {
                log.warn("story scene {} for {} failed; ending it", run.scene.id(), run.ownerId, failure);
                abort(run, "failure");
            }
        }
        for (Map.Entry<Integer, Closing> entry : new ArrayList<>(closings.entrySet())) {
            close(entry.getKey(), entry.getValue(), now);
        }
    }

    private void step(Run run, long now) {
        Character owner = world.roster().onlineCharacter(run.ownerId);
        if (!world.config().PERSONAL_ARCS_ENABLED) {
            abort(run, "switched off");
            return;
        }
        if (owner == null || run.disconnected || run.left.contains(run.ownerId)) {
            abort(run, "the owner left");
            return;
        }
        boolean settled = now - run.startedAtMs >= SETTLE_MS;
        if (settled && owner.getMap() != run.stage.map()) {
            abort(run, "the owner is off the stage");
            return;
        }
        if (now >= run.deadlineMs) {
            abort(run, "too long");
            return;
        }
        if (!onStage(run, run.lead()) && (run.left.contains(run.lead()) || now - run.startedAtMs >= LOST_MS)) {
            abort(run, "the companion is off the stage");
            return;
        }
        if (run.racing && run.outcome == null) {
            int rivalId = run.cast.getOrDefault(run.rival, 0);
            if (run.foeDown) {
                resolve(run, owner, null);
            } else if (rivalId == 0 || run.left.contains(rivalId) || now - run.startedAtMs >= LOST_MS && !onStage(run, rivalId)) {
                resolve(run, owner, run.story.persona()); // the rival is gone: the companion wins by forfeit
            } else {
                return;
            }
        }
        if (!run.alive.isEmpty() || now < run.nextAtMs) {
            return;
        }
        if (run.beat >= run.beats.size()) {
            finish(run);
            return;
        }
        play(run, run.beats.get(run.beat++), owner, now);
    }

    private boolean onStage(Run run, int characterId) {
        if (characterId == 0 || run.left.contains(characterId)) {
            return false;
        }
        Character chr = character(run, characterId);
        return chr != null && chr.getMap() == run.stage.map();
    }

    private Character character(Run run, int characterId) {
        for (Map.Entry<String, Integer> member : run.cast.entrySet()) {
            if (member.getValue() == characterId) {
                RosterEntry entry = world.roster().entry(run.ownerId, member.getKey());
                return entry == null ? null : world.roster().companion(entry);
            }
        }
        return null;
    }

    private void play(Run run, SceneBeat beat, Character owner, long now) {
        run.nextAtMs = now;
        if (beat.line() != null) {
            if (beat.ifPresent() != null && !run.cast.containsKey(beat.ifPresent())) {
                return; // an optional line for a companion who did not come
            }
            Integer speakerId = run.cast.get(beat.by());
            Character speaker = speakerId == null || !onStage(run, speakerId) ? null : character(run, speakerId);
            if (speaker == null) {
                return;
            }
            String text = render(beat.line(), owner, run, beat.by());
            hands.say(speaker, text);
            run.nextAtMs = now + pace(text);
            return;
        }
        if (beat.effect() != null) {
            SceneEffect effect = SceneEffect.of(beat.effect());
            if (effect != null) {
                if (effect == SceneEffect.BANNER && run.banner) {
                    SceneEffect.clearBanner(run.stage.map());
                }
                effect.play(run.stage.map(), beat, character(run, run.lead()), owner);
                run.banner |= effect == SceneEffect.BANNER;
                run.nextAtMs = now + (effect == SceneEffect.CLOCK ? 0L : EFFECT_MS);
            }
            return;
        }
        if (!beat.spawn().isEmpty()) {
            int index = 0;
            for (SceneSpawn spawn : beat.spawn()) {
                for (int n = 0; n < spawn.count(); n++) {
                    Point at = spawn.x() != 0 || spawn.y() != 0 ? new Point(spawn.x() + 25 * n, spawn.y())
                            : new Point(owner.getPosition().x + (index % 2 == 0 ? 160 : -160) + 30 * (index / 2),
                            owner.getPosition().y);
                    spawn(run, spawn.mobId(), at, false);
                    index++;
                }
            }
            return;
        }
        if (beat.race()) {
            SceneFoe foe = foeFor(run);
            if (foe != null) {
                Point at = new Point(owner.getPosition().x + 120, owner.getPosition().y);
                if (spawn(run, foe.mobId(), at, true)) {
                    run.racing = true;
                }
            }
            return;
        }
        run.nextAtMs = now + beat.seconds() * 1000L;
    }

    private boolean spawn(Run run, int mobId, Point at, boolean foe) {
        Monster[] holder = new Monster[1];
        Monster mob = hands.spawn(run.stage, mobId, at, made -> {
            holder[0] = made;
            run.alive.add(made);
            return killWatch(run, made, foe);
        });
        if (mob == null) {
            if (holder[0] != null) {
                run.alive.remove(holder[0]);
            }
            return false;
        }
        return true;
    }

    /** Hears a scene monster's death, inside its monitor on the killer's thread: memory only. */
    private MonsterListener killWatch(Run run, Monster mob, boolean foe) {
        return new MonsterListener() {
            @Override
            public void monsterKilled(int aniTime) {
                if (foe) {
                    long hp = Math.max(1L, mob.getMaxHp());
                    for (int racer : List.of(run.lead(), run.cast.getOrDefault(run.rival, 0))) {
                        if (racer != 0) {
                            run.shares.put(racer, (int) Math.round(100.0 * mob.damageBy(racer) / hp));
                        }
                    }
                    run.foeDown = true;
                }
                run.alive.remove(mob);
            }

            @Override
            public void monsterDamaged(client.Character from, int trueDmg) {
            }

            @Override
            public void monsterHealed(int trueHeal) {
            }
        };
    }

    /** The first foe band that fits the racers' average level. */
    private SceneFoe foeFor(Run run) {
        int total = 0;
        int racers = 0;
        for (int id : List.of(run.lead(), run.cast.getOrDefault(run.rival, 0))) {
            Character racer = id == 0 ? null : character(run, id);
            if (racer != null) {
                total += racer.getLevel();
                racers++;
            }
        }
        int level = racers == 0 ? 1 : total / racers;
        for (SceneFoe foe : run.scene.foes()) {
            if (level <= foe.maxLevel()) {
                return foe;
            }
        }
        return run.scene.foes().isEmpty() ? null : run.scene.foes().get(run.scene.foes().size() - 1);
    }

    /**
     * The race's result: the larger damage share wins, shares within STORY_RACE_EVEN_PERCENT are even; {@code forfeitTo}
     * wins when the other racer left before the foe fell. Its outcome beats play next; both bonds get the flags and the
     * event row keeps the numbers.
     */
    void resolve(Run run, Character owner, String forfeitTo) {
        String lead = run.story.persona();
        int mine = run.shares.getOrDefault(run.lead(), 0);
        int theirs = run.shares.getOrDefault(run.cast.getOrDefault(run.rival, 0), 0);
        String key;
        if (forfeitTo != null) {
            key = run.scene.outcome().containsKey("forfeit." + forfeitTo) ? "forfeit." + forfeitTo : "won." + forfeitTo;
        } else if (Math.abs(mine - theirs) <= world.config().STORY_RACE_EVEN_PERCENT) {
            key = "even";
        } else {
            key = "won." + (mine > theirs ? lead : run.rival);
        }
        run.outcome = key;
        run.racing = false;
        run.beats.addAll(run.beat, run.scene.outcome().getOrDefault(key, List.of()));
        String raced = StoryState.rivalryFlag(lead, run.rival);
        String result = raced + "." + (key.equals("even") ? "even" : "won." + key.substring(key.indexOf('.') + 1));
        for (String personaId : List.of(lead, run.rival)) {
            world.bonds().setFlag(run.ownerId, personaId, raced);
            world.bonds().setFlag(run.ownerId, personaId, result);
        }
        world.store().event(run.ownerId, lead, "story.race", lead + "=" + mine + "% " + run.rival + "=" + theirs
                + "% of the foe's HP, " + key + (forfeitTo != null ? " (forfeit)" : ""));
        log.info("Story race for {}: {} {}% vs {} {}% -> {}", owner.getName(), lead, mine, run.rival, theirs, key);
    }

    /** A line with its slots filled: the owner's name, and in a race a speaker's share and the other racer's. */
    String render(String line, Character owner, Run run, String speaker) {
        int lead = run.shares.getOrDefault(run.lead(), 0);
        int rival = run.shares.getOrDefault(run.cast.getOrDefault(run.rival, 0), 0);
        boolean leadSpeaks = run.story.persona().equals(speaker);
        return line.replace("{owner}", owner.getName())
                .replace("{mine}", String.valueOf(leadSpeaks ? lead : rival))
                .replace("{theirs}", String.valueOf(leadSpeaks ? rival : lead));
    }

    private long pace(String text) {
        return Math.min(LINE_MAX_MS, world.config().STORY_LINE_MIN_MS + TYPING_MS_PER_CHAR * text.length());
    }

    // ---- ending -------------------------------------------------------------------------------

    private void finish(Run run) {
        leave(run);
        long now = clock.getAsLong();
        closings.put(run.ownerId, new Closing(run.questId, run.story.id(), true, now + CLOSE_DELAY_MS, now + CLOSE_GIVE_UP_MS));
        world.store().event(run.ownerId, run.story.persona(), "story.scene.finish", run.scene.id()
                + (run.outcome == null ? "" : " " + run.outcome));
    }

    /**
     * Ends the scene early: everyone out, the step stays SCENE, and the companion says it will be another day. A race
     * already settled (its foe fell, both bonds flagged) is done all the same: it is never raced twice, and its step
     * completes.
     */
    void abort(Run run, String reason) {
        leave(run);
        long now = clock.getAsLong();
        boolean settled = run.outcome != null;
        closings.put(run.ownerId, new Closing(run.questId, run.story.id(), settled, now + CLOSE_DELAY_MS,
                now + CLOSE_GIVE_UP_MS));
        world.store().event(run.ownerId, run.story.persona(), "story.scene.abort", run.scene.id() + ": " + reason
                + (settled ? " (the race was settled: done)" : ""));
        log.info("Story scene {} for {} ended early: {}", run.scene.id(), run.ownerId, reason);
    }

    /** Ends every scene now (the switch went off, a shutdown): each one aborts. */
    void abortAll(String reason) {
        for (Run run : new ArrayList<>(runs.values())) {
            abort(run, reason);
        }
    }

    /**
     * Everyone out: each character still on the stage is unregistered first and then set down at the owner's start
     * (a companion beside them), the commitments end, and the copy is let go.
     */
    private void leave(Run run) {
        runs.remove(run.ownerId);
        Character owner = world.roster().onlineCharacter(run.ownerId);
        MapleMap stage = run.stage.map();
        if (run.banner) {
            SceneEffect.clearBanner(stage);
        }
        MapleMap exit = owner == null ? null : hands.channelMap(owner, run.exitMapId);
        if (owner != null) {
            run.stage.unregister(owner);
            if (owner.getMap() == stage && exit != null) {
                hands.warp(owner, exit, run.exitAt);
            }
        }
        int index = 1;
        for (Map.Entry<String, Integer> member : run.cast.entrySet()) {
            Character bot = character(run, member.getValue());
            if (bot == null) {
                continue;
            }
            run.stage.unregister(bot);
            if (bot.getMap() == stage && exit != null) {
                hands.warp(bot, exit, new Point(run.exitAt.x + (index % 2 == 0 ? 40 : -40) * index, run.exitAt.y));
            }
            index++;
        }
        Commitments commitments = world.commitments();
        if (commitments != null) {
            commitments.releaseKey(run.key());
        }
        run.stage.end();
    }

    /**
     * After the owner is back: a finished scene completes its step (its closing window, so only once no window is
     * open), an aborted one gets the companion's line. Waits while the owner is away; given up after an hour.
     */
    private void close(int ownerId, Closing closing, long now) {
        if (now >= closing.givenUpAtMs()) {
            closings.remove(ownerId);
            return;
        }
        Character owner = world.roster().onlineCharacter(ownerId);
        if (now < closing.atMs() || owner == null || owner.getEventInstance() != null || !LivingTalk.mayShow(owner)) {
            return;
        }
        closings.remove(ownerId);
        Story story = world.content().story(closing.storyId());
        if (closing.done()) {
            world.quests().sceneDone(owner, closing.questId());
        } else if (story != null) {
            List<String> lines = story.lines("scene.abort");
            say(owner, story, lines.isEmpty() ? "let's try that another day." : lines.get(Math.floorMod(ownerId, lines.size())));
        }
    }

    /** The story's companion says {@code line} to its owner: in party chat when it is out with them, else a whisper. */
    private void say(Character owner, Story story, String line) {
        Persona persona = world.content().persona(story.persona());
        RosterEntry entry = persona == null ? null : world.roster().entry(owner.getId(), persona.id());
        Character bot = entry == null ? null : world.roster().companion(entry);
        if (bot == null || !world.roster().sayInParty(bot, line)) {
            if (persona != null) {
                world.residents().whisper(persona, owner, line);
            }
        }
    }
}
