package server.living.population.casino;

import client.Character;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.living.population.CitizenSession;
import server.maps.MapItem;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A citizen opening gachapon boxes in Henesys Market (SoloMapling's GachaBot), paid and real: each box costs it
 * GACHA_BOX_PRICE from its purse (booked as an NPC purchase), springs open beside it ({@link ReactorShow}) and sprays a
 * handful of drops, which are its alone and go into its bag when it picks them up. A box holds mostly small mesos, now
 * and then a potion, ore, jewel or crystal, and in one box of fifty a prize (casino.yaml gacha). It opens a box every
 * GACHA_BOX_GAP_MS or so while a player is in the Market, three to six of them, and says what it thinks of each.
 * SoloMapling's gacha had a Stormcaster Gloves in every box, from nothing; its level-up and scroll cheers are
 * ChatterEngine's reactions now.
 */
final class GachaShow extends HostPost<GachaShow.GachaPost> {
    final class GachaPost extends HostPost.Post {
        volatile int boxesLeft = 3 + host.random().nextInt(4);
        volatile long nextBoxAt;
        volatile boolean opening;

        GachaPost(CitizenSession session, long until) {
            super(session, until);
        }
    }

    GachaShow(CasinoHost host, CasinoContent content) {
        super(host, content);
    }

    @Override
    public String name() {
        return "gacha";
    }

    @Override
    public int mapId() {
        return content.gacha().mapId();
    }

    @Override
    public boolean enabled() {
        return host.population().config().GACHA_SHOW_ENABLED;
    }

    @Override
    Point spot() {
        return content.gacha().spot();
    }

    @Override
    GachaPost open(CitizenSession session, long until) {
        return session.agent().getMeso() >= 2L * host.population().config().GACHA_BOX_PRICE ? new GachaPost(session, until) : null;
    }

    @Override
    boolean busy(GachaPost post) {
        return post.opening;
    }

    @Override
    void step(GachaPost post, long now) {
        Character agent = post.agent();
        int price = host.population().config().GACHA_BOX_PRICE;
        if (post.boxesLeft <= 0 || agent.getMeso() < price) {
            leave(post, "done with boxes");
            return;
        }
        if (post.opening || now < post.nextBoxAt || !host.humanOn(post.session.channel, mapId())) {
            return;
        }
        post.opening = true;
        post.boxesLeft--;
        post.nextBoxAt = now + host.population().config().GACHA_BOX_GAP_MS + host.random().nextInt(60_000);
        host.runAs(post.session, () -> {
            List<ReactorShow.Prize> prizes = box(content.gacha(), host.random());
            boolean prize = prizes.stream().anyMatch(p -> isPrize(content.gacha(), p.itemId()));
            Point at = new Point(agent.getPosition().x + 60, agent.getPosition().y);
            List<MapItem> drops;
            try {
                try (var ignored = MesoFlows.as(MesoAccount.NPC_PURCHASE)) {
                    agent.gainMeso(-price, false);
                }
                drops = ReactorShow.open(agent, content.gacha().reactor(), at, prizes);
            } catch (RuntimeException failure) {
                post.opening = false; // a failed box never holds the host at its post
                throw failure;
            }
            long sprayMs = 200L * prizes.size();
            host.after(sprayMs + 2_000L + host.random().nextInt(2_000), () -> {
                post.opening = false; // first, so a failed pickup cannot hold the host at its post either
                host.runAs(post.session, () -> Floor.pickUp(agent, drops));
            });
            host.after(sprayMs + 3_500L, () -> host.say(post.session,
                    prize ? "casino.gacha.prize" : "casino.gacha.open", Map.of()));
        });
    }

    @Override
    public boolean heard(Character human, String text, long now) {
        return false;
    }

    /** One box: {@code size} drops, each small mesos unless a filler roll makes it an item; a prize in a few boxes. */
    static List<ReactorShow.Prize> box(CasinoContent.Gacha gacha, Random random) {
        List<ReactorShow.Prize> out = new ArrayList<>();
        for (int i = 0; i < gacha.size(); i++) {
            if (random.nextDouble() < gacha.fillerChance()) {
                String type = weighted(gacha.typeWeights(), random);
                String tier = weighted(gacha.tierWeights(), random);
                List<Integer> items = type == null || tier == null ? List.of()
                        : gacha.filler().getOrDefault(type, Map.of()).getOrDefault(tier, List.of());
                if (!items.isEmpty()) {
                    out.add(new ReactorShow.Prize(items.get(random.nextInt(items.size())), 0));
                    continue;
                }
            }
            List<Integer> mesos = gacha.mesos();
            out.add(new ReactorShow.Prize(0, mesos.isEmpty() ? 10 : mesos.get(random.nextInt(mesos.size()))));
        }
        if (random.nextDouble() < gacha.prizeChance()) {
            CasinoContent.Weighted prize = DropGame.pick(gacha.prizes(), random);
            if (prize != null) {
                out.add(gacha.size() / 4 + random.nextInt(Math.max(1, out.size() - gacha.size() / 4)),
                        new ReactorShow.Prize(prize.itemId(), 0));
            }
        }
        return out;
    }

    private static boolean isPrize(CasinoContent.Gacha gacha, int itemId) {
        for (CasinoContent.Weighted prize : gacha.prizes()) {
            if (prize.itemId() == itemId) {
                return true;
            }
        }
        return false;
    }

    private static String weighted(Map<String, Integer> weights, Random random) {
        int total = 0;
        for (int weight : weights.values()) {
            total += Math.max(0, weight);
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            roll -= Math.max(0, entry.getValue());
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return null;
    }
}
