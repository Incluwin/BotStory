package server.living.population.casino;

import client.Character;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.living.population.Citizen;
import server.living.population.CitizenSession;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A venue run by one citizen per channel at one spot (the dice host, the bar, the drop game, the gachapon show, the NX
 * seller). The citizen walks to its spot, is {@link Post#ready} once there, gets {@link #step} every 20 s and, where a
 * player may be waiting on it (a trade window), {@link #quick} every second while a human is on its map. Its shift ends
 * when it is not {@link #busy}; then {@link #closing} and it goes on with its day.
 */
abstract class HostPost<P extends HostPost.Post> implements Venue {
    static final long QUICK_MS = 1_000;

    /** One citizen's post on one channel. */
    static class Post {
        final CitizenSession session;
        final long until;
        volatile boolean ready;
        volatile boolean quickRunning;

        Post(CitizenSession session, long until) {
            this.session = session;
            this.until = until;
        }

        Character agent() {
            return session.agent();
        }
    }

    protected final CasinoHost host;
    protected final CasinoContent content;
    /** Channel -> its post. */
    protected final Map<Integer, P> posts = new ConcurrentHashMap<>();

    HostPost(CasinoHost host, CasinoContent content) {
        this.host = host;
        this.content = content;
    }

    /** Where the host stands. */
    abstract Point spot();

    /** A post for this citizen, or null when it cannot take it (its purse, its bag). */
    abstract P open(CitizenSession session, long until);

    /** Every 20 s once the host stands at its spot. */
    abstract void step(P post, long now);

    /** Every second while a human is on the map; nothing by default. */
    void quick(P post, long now) {
    }

    /** True while the host is mid-game: its shift end waits. */
    boolean busy(P post) {
        return false;
    }

    /** Before the host leaves its post. */
    void closing(P post) {
    }

    /** True when this post has work for {@link #quick}. */
    boolean wantsQuick() {
        return false;
    }

    @Override
    public boolean wants(Citizen citizen) {
        return enabled() && !posts.containsKey(citizen.channel());
    }

    @Override
    public boolean take(CitizenSession session, long now) {
        Character agent = session.agent();
        if (!enabled() || agent == null || session.entry == null || posts.containsKey(session.channel)) {
            return false;
        }
        P post = open(session, now + host.shiftMs());
        if (post == null || posts.putIfAbsent(session.channel, post) != null) {
            return false;
        }
        walk(session, spot());
        host.after(5_000L + host.random().nextInt(5_000), () -> post.ready = true);
        session.journal.add("casino", "took up a post: " + name());
        return true;
    }

    @Override
    public boolean holds(int characterId) {
        for (P post : posts.values()) {
            if (post.session.characterId == characterId) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void tick(long now) {
        for (P post : new ArrayList<>(posts.values())) {
            Character agent = post.agent();
            if (agent == null || agent.getMapId() != mapId() || !enabled()) {
                leave(post, agent == null ? "logged off" : "left the map");
                continue;
            }
            if (now >= post.until && !busy(post)) {
                leave(post, "shift over");
                continue;
            }
            if (post.ready) {
                step(post, now);
                if (wantsQuick() && !post.quickRunning && host.humanOn(post.session.channel, mapId())) {
                    post.quickRunning = true;
                    host.after(QUICK_MS, () -> quickLoop(post));
                }
            }
        }
    }

    private void quickLoop(P post) {
        if (posts.get(post.session.channel) != post || !host.humanOn(post.session.channel, mapId())) {
            post.quickRunning = false;
            return;
        }
        quick(post, System.currentTimeMillis());
        host.after(QUICK_MS, () -> quickLoop(post));
    }

    void leave(P post, String why) {
        if (!posts.remove(post.session.channel, post)) {
            return;
        }
        closing(post);
        host.after(3_000L, () -> host.done(post.session, name() + ": " + why));
    }

    @Override
    public void stop() {
        for (P post : new ArrayList<>(posts.values())) {
            leave(post, "casino closed");
        }
    }

    @Override
    public String status() {
        StringBuilder out = new StringBuilder(name()).append(' ').append(enabled() ? "on" : "off").append(':');
        for (Map.Entry<Integer, P> entry : posts.entrySet()) {
            out.append(" [ch").append(entry.getKey()).append(' ').append(entry.getValue().session.name())
                    .append(entry.getValue().ready ? "" : " walking").append(']');
        }
        return out.toString();
    }

    /** The post on the human's channel, when the human stands within {@code reach} of its host. */
    P near(Character human, int reach) {
        P post = posts.get(human.getClient().getChannel());
        Character agent = post == null ? null : post.agent();
        if (post == null || !post.ready || agent == null || agent.getMapId() != human.getMapId()
                || agent.getPosition().distance(human.getPosition()) > reach) {
            return null;
        }
        return post;
    }

    void walk(CitizenSession session, Point spot) {
        Character agent = session.agent();
        if (agent == null || agent.getMap() == null || session.entry == null) {
            return;
        }
        Point ground = AgentGroundingService.findGroundPoint(agent.getMap(), new Point(spot.x, spot.y - 1));
        AgentMovementCommandRuntime.moveTo(session.entry, ground != null ? ground : spot, false);
    }

    static boolean says(String said, String... words) {
        for (String word : words) {
            if (said.equals(word) || said.startsWith(word + " ") || said.endsWith(" " + word) || said.contains(" " + word + " ")) {
                return true;
            }
        }
        return false;
    }
}
