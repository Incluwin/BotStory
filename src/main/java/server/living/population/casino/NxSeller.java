package server.living.population.casino;

import client.Character;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.Trade;
import server.living.population.ChatterEngine;
import server.living.population.CitizenSession;

import java.awt.Point;
import java.util.Map;

/**
 * A citizen selling NX cash codes in the Free Market entrance (SoloMapling's NXMerchantBot). It calls out its offer now
 * and then; a player who trades it exactly NX_CODE_PRICE gets, once the trade goes through, a whisper with a real
 * code worth NX_CODE_AMOUNT (NxCodes) to enter at the Cash Shop's coupon window. Any other amount is handed back with a
 * word in the trade chat. SoloMapling delivered the code through Maple Messenger; a whisper is how a player would.
 */
final class NxSeller extends HostPost<NxSeller.NxPost> {
    private static final long TRADE_WAIT_MS = 60_000L;

    final class NxPost extends HostPost.Post {
        volatile Character buyer;
        volatile long tradeUntil;

        NxPost(CitizenSession session, long until) {
            super(session, until);
        }
    }

    NxSeller(CasinoHost host, CasinoContent content) {
        super(host, content);
    }

    @Override
    public String name() {
        return "nxseller";
    }

    @Override
    public int mapId() {
        return content.nxSellerMap();
    }

    @Override
    public boolean enabled() {
        return host.population().config().NX_SELLER_ENABLED;
    }

    @Override
    Point spot() {
        Character agent = null;
        for (NxPost post : posts.values()) {
            agent = post.agent();
        }
        return agent == null ? new Point(0, 0) : agent.getPosition();
    }

    @Override
    NxPost open(CitizenSession session, long until) {
        return new NxPost(session, until);
    }

    @Override
    public boolean take(CitizenSession session, long now) {
        // it stands where it arrived in the entrance, among the other players
        Character agent = session.agent();
        if (!enabled() || agent == null || posts.containsKey(session.channel)) {
            return false;
        }
        NxPost post = open(session, now + host.shiftMs());
        if (posts.putIfAbsent(session.channel, post) != null) {
            return false;
        }
        post.ready = true;
        session.journal.add("casino", "sells NX codes in the Free Market");
        return true;
    }

    @Override
    boolean wantsQuick() {
        return true;
    }

    @Override
    boolean busy(NxPost post) {
        return post.buyer != null;
    }

    @Override
    void step(NxPost post, long now) {
        if (post.buyer == null && host.random().nextInt(100) < 20 && host.humanOn(post.session.channel, mapId())) {
            boolean flavor = host.random().nextInt(100) < 25;
            host.say(post.session, flavor ? "casino.nx.flavor" : "casino.nx.sell",
                    Map.of("price", Chips.mesos(host.population().config().NX_CODE_PRICE),
                            "nx", host.population().config().NX_CODE_AMOUNT / 1000 + "k"));
        }
    }

    @Override
    void quick(NxPost post, long now) {
        Character agent = post.agent();
        if (agent == null) {
            return;
        }
        if (post.buyer == null) {
            Character inviter = TradeDesk.inviter(agent);
            if (inviter != null) {
                post.buyer = inviter;
                post.tradeUntil = now + TRADE_WAIT_MS;
                host.after(1_000L + host.random().nextInt(1_500), () -> host.runAs(post.session, () -> TradeDesk.accept(agent, inviter)));
            }
            return;
        }
        Character buyer = post.buyer;
        if (TradeDesk.partner(agent) == null) {
            if (agent.getTrade() == null || now > post.tradeUntil) {
                TradeDesk.cancel(agent);
                post.buyer = null;
            }
            return;
        }
        if (!TradeDesk.partnerLocked(agent)) {
            if (now > post.tradeUntil) {
                TradeDesk.cancel(agent);
                post.buyer = null;
            }
            return;
        }
        int price = host.population().config().NX_CODE_PRICE;
        if (TradeDesk.offered(agent) != price) {
            TradeDesk.chat(agent, line(post, "casino.nx.wrong", Map.of("price", Chips.mesos(price))));
            host.after(1_500L, () -> host.runAs(post.session, () -> TradeDesk.cancel(agent)));
            post.buyer = null;
            return;
        }
        long before = agent.getMeso();
        host.runAs(post.session, () -> {
            TradeDesk.chat(agent, line(post, "casino.nx.thanks", Map.of()));
            TradeDesk.confirm(agent);
            if (agent.getMeso() - before >= price - Trade.getFee(price)) { // the trade went through, less its fee (5% at 50m)
                deliver(post, buyer);
            }
            post.buyer = null;
        });
    }

    private void deliver(NxPost post, Character buyer) {
        String code = NxCodes.generate(host.random());
        if (!NxCodes.create(code, host.population().config().NX_CODE_AMOUNT)) {
            return;
        }
        String text = line(post, "casino.nx.code", Map.of());
        Character agent = post.agent();
        if (text == null || agent == null) {
            return;
        }
        host.after(3_000L + host.random().nextInt(3_000), () -> {
            host.population().chatter().whisper(post.session, buyer, text);
            // the code itself goes as typed, never through the voice's case and typos
            host.after(4_000L + host.random().nextInt(2_000), () -> {
                if (buyer.getClient() != null && agent.getClient() != null) {
                    AgentPacketGatewayRuntime.packets().sendWhisperReceive(buyer, agent.getName(),
                            post.session.channel - 1, false, code.replace("-", ""));
                }
            });
        });
    }

    @Override
    void closing(NxPost post) {
        Character agent = post.agent();
        if (agent != null && agent.getTrade() != null) {
            TradeDesk.cancel(agent);
        }
    }

    @Override
    public boolean heard(Character human, String text, long now) {
        return false; // a buyer trades it
    }

    private String line(NxPost post, String trigger, Map<String, ?> slots) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, post.session.mapId, post.session.channel).self(post.session);
        for (Map.Entry<String, ?> slot : slots.entrySet()) {
            event.slot(slot.getKey(), String.valueOf(slot.getValue()));
        }
        return host.population().chatter().compose(event);
    }
}
