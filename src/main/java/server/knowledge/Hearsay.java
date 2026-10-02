package server.knowledge;

import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.ContentAnswer;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.ContentQuestionDetector.Kind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Open content questions per map and channel, and what an answer to one truthfully teaches whoever overhears it
 * (docs/SERVER_KNOWLEDGE_PLAN.md 7.5). A line teaches only when it answers someone else's question asked there within a
 * minute, is no question itself, says nothing negated, and every name it mentions belongs to the true answer (the
 * sources, places, routes or spots an all-knowing bot would give), at least one of them of a kind that answers the
 * question. So a wrong, mixed or hedged-off answer teaches nothing, and gossip about something else is not an answer.
 */
public final class Hearsay {
    static final long OPEN_MS = 60_000;
    private static final int PER_PLACE = 2;
    private static final int PLACES = 512;
    private static final int TRUTH_MAX = 512;
    private static final int PLACE_MAPS = 3;
    private static final int PATH_TARGETS = 8;
    private static final int TRAINING_MAPS = 200;
    private static final int ALL_KNOWING = 200;
    private static final Set<String> NEGATION = Set.of("not", "no", "nah", "nope", "dont", "doesnt", "never", "isnt", "arent",
            "wrong", "idk");
    private static final List<EntityKind> MENTIONED = List.of(EntityKind.ITEM, EntityKind.MOB, EntityKind.MAP, EntityKind.NPC,
            EntityKind.REGION, EntityKind.PQ, EntityKind.ROLE);
    private static final long[] NOTHING = new long[0];

    private final ContentAnswers answers;
    private final Supplier<WorldKnowledge> knowledge;
    /** (channel << 32 | map) -> the open questions there, newest last; the oldest place goes first past {@value #PLACES}. */
    private final Map<Long, Deque<Open>> places = new LinkedHashMap<>(PLACES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Deque<Open>> eldest) {
            return size() > PLACES;
        }
    };

    Hearsay(ContentAnswers answers, Supplier<WorldKnowledge> knowledge) {
        this.answers = answers;
        this.knowledge = knowledge;
    }

    /** A content question was said on this map and channel (by a human or a citizen). Stores the line only. */
    public void asked(int channel, int mapId, int askerId, int askerLevel, String line, long nowMs) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (places) {
            Deque<Open> open = places.computeIfAbsent(place(channel, mapId), key -> new ArrayDeque<>());
            open.addLast(new Open(mapId, askerId, askerLevel, line, nowMs));
            while (open.size() > PER_PLACE) {
                open.removeFirst();
            }
        }
    }

    /** MAP/MOB/ITEM {@link Entity#key()}s this line truthfully teaches about the newest open question there; empty when none. */
    public long[] answered(int channel, int mapId, int speakerId, String line, long nowMs) {
        if (line == null || line.isBlank()) {
            return NOTHING;
        }
        Open question = null;
        synchronized (places) {
            Deque<Open> open = places.get(place(channel, mapId));
            if (open != null) {
                for (Iterator<Open> newest = open.descendingIterator(); newest.hasNext() && question == null; ) {
                    Open candidate = newest.next();
                    if (candidate.askerId != speakerId && nowMs - candidate.atMs <= OPEN_MS) {
                        question = candidate;
                    }
                }
            }
        }
        if (question == null || ContentQuestionDetector.shape(line) != null) {
            return NOTHING;
        }
        for (String token : NameIndex.normalize(line).split(" ")) {
            if (NEGATION.contains(token)) {
                return NOTHING;
            }
        }
        WorldKnowledge k = knowledge.get();
        List<NameIndex.Mention> mentions = k.names().mentions(line, MENTIONED);
        if (mentions.isEmpty()) {
            return NOTHING;
        }
        Truth truth = question.truth(this, k);
        if (truth.defining.isEmpty()) {
            return NOTHING;
        }
        Set<Long> learned = new LinkedHashSet<>(truth.subject == null ? List.of() : teach(k, truth, truth.subject));
        boolean answers = false;
        boolean any = false;
        for (NameIndex.Mention mention : mentions) {
            Entity entity = mention.candidate().entity();
            if (truth.subject != null && entity.key() == truth.subject.key()) {
                continue; // the question's own subject says nothing
            }
            List<Long> keys = teach(k, truth, entity);
            if (keys.isEmpty()) {
                return NOTHING; // a false claim, alone or mixed with true ones, teaches nothing
            }
            any = true;
            answers |= truth.defining.contains(entity.kind());
            learned.addAll(keys);
        }
        if (!any || !answers) {
            return NOTHING;
        }
        long[] out = new long[learned.size()];
        int i = 0;
        for (long key : learned) {
            out[i++] = key;
        }
        return out;
    }

    /** The true answer set of a question, as an all-knowing bot would give it; empty for a kind that has none. */
    private Truth truthOf(WorldKnowledge k, Open question) {
        ContentAnswer probe = answers.explain(new Ask(question.line, new Asker(question.askerId, "", question.mapId,
                question.askerLevel, 0, 0), new Responder(0, ALL_KNOWING, null, null, Set.of()), name -> false, question.atMs));
        Entity subject = probe.subject();
        Kind kind = probe.kind();
        Set<Long> keys = new HashSet<>();
        EnumSet<EntityKind> defining = EnumSet.noneOf(EntityKind.class);
        if (kind == null || probe.outcome() != ContentAnswers.Outcome.ANSWERED) {
            return new Truth(null, keys, defining);
        }
        switch (kind) {
            case DROP_SOURCE, GET_WHERE -> {
                if (subject != null && subject.kind() == EntityKind.ITEM) {
                    for (DropSource drop : k.dropSources(subject.id())) {
                        if (!drop.global()) {
                            add(keys, EntityKind.MOB, drop.mobId());
                            places(k, keys, k.spawnMaps(drop.mobId()));
                        }
                    }
                    shops(k, keys, subject.id());
                    defining.addAll(List.of(EntityKind.MOB, EntityKind.NPC, EntityKind.ROLE));
                }
            }
            case BUY_WHERE -> {
                if (subject != null && subject.kind() == EntityKind.ITEM) {
                    shops(k, keys, subject.id());
                    defining.addAll(List.of(EntityKind.NPC, EntityKind.ROLE, EntityKind.MAP));
                }
            }
            case MOB_DROPS -> {
                if (subject != null && subject.kind() == EntityKind.MOB) {
                    for (int mob : subject.ids()) {
                        for (DropSource drop : k.mobDrops(mob)) {
                            add(keys, EntityKind.ITEM, drop.itemId());
                        }
                    }
                    defining.add(EntityKind.ITEM);
                }
            }
            case WHERE_IS -> {
                if (subject != null) {
                    places(k, keys, targets(k, subject));
                    defining.addAll(List.of(EntityKind.MAP, EntityKind.REGION));
                }
            }
            case ROUTE -> {
                if (subject != null) {
                    List<Integer> targets = targets(k, subject);
                    for (int i = 0; i < targets.size() && i < PATH_TARGETS; i++) {
                        places(k, keys, k.path(question.mapId, targets.get(i)));
                    }
                    defining.addAll(List.of(EntityKind.MAP, EntityKind.REGION));
                }
            }
            case TRAIN_WHERE -> {
                int level = probe.shape() != null && probe.shape().level() > 0 ? probe.shape().level() : question.askerLevel;
                for (int map : k.trainingMaps(level, null, TRAINING_MAPS)) {
                    add(keys, EntityKind.MAP, map);
                    k.map(map).filter(fact -> !fact.mobs().isEmpty())
                            .ifPresent(fact -> add(keys, EntityKind.MOB, ContentAnswers.mainMob(fact)));
                }
                defining.addAll(List.of(EntityKind.MAP, EntityKind.REGION, EntityKind.MOB));
            }
            default -> {
                return new Truth(null, keys, defining);
            }
        }
        return new Truth(kind == Kind.TRAIN_WHERE ? null : subject, keys, defining); // a training spot is the answer, not the subject
    }

    /**
     * What one mentioned (or asked-about) thing teaches as MAP/MOB/ITEM keys, all of them in the truth set: itself, an NPC's
     * first true map, at most three true maps of a region or PQ, a role's NPCs' true maps. Empty when it is not true.
     */
    private static List<Long> teach(WorldKnowledge k, Truth truth, Entity entity) {
        List<Long> out = new ArrayList<>();
        switch (entity.kind()) {
            case MAP, MOB, ITEM -> {
                if (truth.keys.contains(entity.key()) || entity == truth.subject) {
                    out.add(entity.key());
                }
            }
            case NPC -> firstTrue(truth, k.npcMaps(entity.id()), out, 1);
            case REGION, PQ -> firstTrue(truth, entity.ids(), out, PLACE_MAPS);
            case ROLE -> {
                for (int npc : entity.ids()) {
                    firstTrue(truth, k.npcMaps(npc), out, 1);
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static void firstTrue(Truth truth, List<Integer> maps, List<Long> out, int limit) {
        int taken = 0;
        for (int map : maps) {
            long key = Entity.simple(EntityKind.MAP, map, "", "").key();
            if (taken < limit && truth.keys.contains(key)) {
                out.add(key);
                taken++;
            }
        }
    }

    /** Where a subject is: an NPC's maps, a map's or region's or PQ's ids, a mob's spawns, a role's NPCs' maps. */
    private static List<Integer> targets(WorldKnowledge k, Entity subject) {
        List<Integer> out = new ArrayList<>();
        switch (subject.kind()) {
            case NPC -> out.addAll(k.npcMaps(subject.id()));
            case MOB -> out.addAll(k.spawnMaps(subject.id()));
            case ROLE -> subject.ids().forEach(npc -> out.addAll(k.npcMaps(npc)));
            default -> out.addAll(subject.ids());
        }
        return out;
    }

    /** The shops selling an item: their NPCs, maps and towns. */
    private static void shops(WorldKnowledge k, Set<Long> keys, int itemId) {
        for (ShopSource shop : k.shopsSelling(itemId)) {
            add(keys, EntityKind.NPC, shop.npcId());
            places(k, keys, shop.npcMaps());
        }
    }

    /** Maps and their towns. */
    private static void places(WorldKnowledge k, Set<Long> keys, List<Integer> maps) {
        for (int map : maps) {
            add(keys, EntityKind.MAP, map);
            int town = k.townOf(map);
            if (town != 0) {
                add(keys, EntityKind.MAP, town);
            }
        }
    }

    private static void add(Set<Long> keys, EntityKind kind, int id) {
        if (keys.size() < TRUTH_MAX) {
            keys.add(Entity.simple(kind, id, "", "").key());
        }
    }

    private static long place(int channel, int mapId) {
        return (long) channel << 32 | (mapId & 0xffffffffL);
    }

    /** An open question's true answers, built once on the first reply that names something; no defining kind: none. */
    private record Truth(Entity subject, Set<Long> keys, Set<EntityKind> defining) {
    }

    private static final class Open {
        final int mapId;
        final int askerId;
        final int askerLevel;
        final String line;
        final long atMs;
        private volatile Truth truth;

        Open(int mapId, int askerId, int askerLevel, String line, long atMs) {
            this.mapId = mapId;
            this.askerId = askerId;
            this.askerLevel = askerLevel;
            this.line = line;
            this.atMs = atMs;
        }

        /** Built outside the place lock (an explain is tens of microseconds); two first replies may both build it. */
        Truth truth(Hearsay hearsay, WorldKnowledge k) {
            Truth built = truth;
            if (built == null) {
                built = hearsay.truthOf(k, this);
                truth = built;
            }
            return built;
        }
    }
}
