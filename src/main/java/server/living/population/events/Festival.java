package server.living.population.events;

import client.Character;
import server.living.population.CitizenSession;
import server.living.population.CitizenSession.Activity;
import server.living.population.JumpQuestCourses;
import server.living.population.JumpQuestCourses.Course;
import server.living.population.JumpQuestCourses.Leg;
import server.living.population.casino.Venue;
import server.living.population.paper.PaperDesk;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A season's festival (docs/WORLD_SYSTEMS_PLAN.md N4.4): for an evening one town has the region's minigame tables and the
 * drop game (both take it from {@link WorldModifier#festivalTown}), and a jump-quest meet on one of its courses. The
 * player or citizen who beat the most different people at the tables and the first to the top of the meet's course get a
 * prize of the fixed table and their name in the paper.
 */
final class Festival implements WorldEvents.Runner {
    /** Citizens sent to the tables as it opens; the weight of PLAY plans brings more. */
    static final int TABLE_CITIZENS = 6;
    static final int MEET_CITIZENS = 6;
    /** A table champion beat at least this many different people. */
    static final int CHAMPION_MIN = 3;
    /** A climber this near the course's top is up; one this far from it is on the way. */
    static final int TOP_PX = 120;
    static final int BELOW_PX = 400;

    private final WorldEvents events;
    private final WorldEvents.Run run;
    private final EventCatalog.FestivalTown town;
    /** Winner id -> the people it beat here. */
    private final Map<Integer, Set<Integer>> beaten = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    /** Players seen on a course of the meet below its top since it began. */
    private final Set<Integer> climbing = new HashSet<>();
    private boolean meetOpen;
    private boolean meetDone;

    Festival(WorldEvents events, WorldEvents.Run run) {
        this.events = events;
        this.run = run;
        this.town = events.catalog().festivalTown(run.row.site());
    }

    @Override
    public void begin(long now, boolean resumed) {
        if (town == null) {
            throw new IllegalStateException("no festival town " + run.row.site());
        }
        events.set(WorldModifier.Effect.festival(run.source(), town.region(), town.map(), events.catalog().festivals().weight(),
                run.endsAtMs));
        meetDone = resumed && now >= meetAtMs(); // its meet was held, or lost, before the restart: never a second one
        if (!resumed) {
            events.notice.accept("[World Event] The " + town.name() + " festival is on! Games, prizes and a climb, for the next "
                    + run.entry.minutes() / 60 + " hours.");
        }
        int sent = 0;
        for (CitizenSession session : free()) {
            if (sent < TABLE_CITIZENS && session.activity != Activity.PLAY && !session.citizen.has(server.living.population.Citizen.FLAG_AFK_TOWNIE)) {
                go(session, Activity.PLAY, town.map(), now);
                sent++;
            }
        }
    }

    /** A quarter into it. */
    private long meetAtMs() {
        return run.endsAtMs - run.entry.durationMs() * 3 / 4;
    }

    private void go(CitizenSession session, Activity activity, int mapId, long now) {
        session.afkUntilMs = 0L;
        session.lastPlanAtMs = now;
        events.population().presence().travel(session, activity, mapId, now);
    }

    private List<CitizenSession> free() {
        List<CitizenSession> out = new ArrayList<>();
        for (CitizenSession session : events.population().presence().sessions()) {
            if (session.channel == run.channel && town.region().equals(session.citizen.homeRegion()) && events.free(session)) {
                out.add(session);
            }
        }
        Collections.shuffle(out, events.random());
        return out;
    }

    @Override
    public boolean step(long now) {
        hostDropGame(now);
        if (!meetDone && !meetOpen && now >= meetAtMs()) {
            openMeet(now);
        }
        if (meetOpen) {
            watchMeet();
        }
        return false;
    }

    /** The drop game's post here is taken when nobody has it. */
    private void hostDropGame(long now) {
        if (!events.population().config().DROP_GAME_ENABLED || events.population().casino() == null
                || events.population().casino().venues() == null) {
            return;
        }
        for (Venue venue : events.population().casino().venues().all()) {
            if (!venue.name().equals("dropgame") || venue.mapId() != town.map()) {
                continue;
            }
            for (CitizenSession session : free()) {
                if (venue.wants(session.citizen) && session.activity != Activity.CASINO) {
                    session.afkUntilMs = 0L;
                    session.lastPlanAtMs = now;
                    events.population().casino().send(session, venue.name(), now);
                }
                return; // one asked a step, on its way until it holds the post
            }
        }
    }

    // ---- the jump-quest meet -----------------------------------------------------------------------------------------

    private List<Course> courses() {
        List<Course> out = new ArrayList<>();
        if (!events.population().config().JQ_ENABLED) {
            return out;
        }
        for (Course course : JumpQuestCourses.SHIPPED) {
            if (town.courses().contains(course.key())) {
                out.add(course);
            }
        }
        return out;
    }

    private void openMeet(long now) {
        List<Course> courses = courses();
        if (courses.isEmpty() || events.population().jumpQuests() == null) {
            meetDone = true;
            return;
        }
        meetOpen = true;
        events.notice.accept("[World Event] The " + town.name() + " festival's climb begins: first to the top of the "
                + courses.getFirst().spoken() + " takes the prize.");
        int sent = 0;
        for (CitizenSession session : free()) {
            Character agent = session.agent();
            Course course = agent == null ? null : courses.stream().filter(one -> one.fits(agent.getLevel())
                    && agent.getMeso() >= 2L * one.fee()).findFirst().orElse(null);
            if (course == null || sent >= MEET_CITIZENS || session.activity == Activity.CASINO) {
                continue;
            }
            session.afkUntilMs = 0L;
            session.lastPlanAtMs = now;
            events.population().jumpQuests().send(session, course.key(), now);
            sent++;
        }
    }

    /** Players up a course of the meet: each was seen below its top since it began, so nobody waits up there for it. */
    private void watchMeet() {
        for (Course course : courses()) {
            Leg last = course.legs().getLast();
            for (Leg leg : course.legs()) {
                MapleMap map = events.population().map(run.channel, leg.mapId());
                if (map == null) {
                    continue;
                }
                for (Character human : WorldEvents.humansOn(map)) {
                    boolean onTopMap = leg.mapId() == last.mapId();
                    double away = onTopMap && human.getPosition() != null ? human.getPosition().distance(last.top()) : BELOW_PX;
                    if (away >= BELOW_PX) {
                        climbing.add(human.getId());
                    } else if (away <= TOP_PX && climbing.contains(human.getId())) {
                        meetWon(human);
                        return;
                    }
                }
            }
        }
    }

    /** A citizen is up a jump quest's last leg (CitizenJumpQuests); the first here wins the meet. */
    void climbed(CitizenSession session, Course course) {
        if (meetOpen && session.channel == run.channel && town.courses().contains(course.key()) && session.agent() != null) {
            meetWon(session.agent());
        }
    }

    private void meetWon(Character winner) {
        if (meetDone) {
            return;
        }
        meetDone = true;
        meetOpen = false;
        events.notice.accept("[World Event] " + winner.getName() + " wins the climb at the " + town.name() + " festival!");
        events.award(winner, events.catalog().festivalPrizes(), "You won the climb at the " + town.name() + " festival!");
        PaperDesk.file("town.meet", 200, Map.of("name", winner.getName(), "place", town.name()));
    }

    // ---- the tables ----------------------------------------------------------------------------------------------------

    /** {@code winner} beat {@code loser} at a table on {@code mapId}. */
    void played(Character winner, Character loser, int mapId) {
        if (mapId == town.map() && winner.getClient() != null && winner.getClient().getChannel() == run.channel) {
            beaten.computeIfAbsent(winner.getId(), id -> new HashSet<>()).add(loser.getId());
            names.put(winner.getId(), winner.getName());
        }
    }

    @Override
    public String end(long now) {
        int champion = 0;
        int most = CHAMPION_MIN - 1;
        for (Map.Entry<Integer, Set<Integer>> one : beaten.entrySet()) {
            if (one.getValue().size() > most) {
                most = one.getValue().size();
                champion = one.getKey();
            }
        }
        if (champion == 0) {
            PaperDesk.file("town.festival.quiet", 80, Map.of("place", town.name()));
            return "no champion";
        }
        String name = names.get(champion);
        events.notice.accept("[World Event] The " + town.name() + " festival is over. " + name + " is the champion of the tables!");
        events.award(events.online(champion), events.catalog().festivalPrizes(),
                "You are the champion of the tables at the " + town.name() + " festival!");
        PaperDesk.file("town.festival", 200, Map.of("name", name, "place", town.name(), "count", String.valueOf(most)));
        return "champion " + name;
    }

    @Override
    public void cleanUp() {
        events.clear(run.source());
        meetOpen = false;
    }
}
