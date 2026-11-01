package server.living.population.casino;

import client.Character;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import client.Skill;
import client.SkillFactory;
import server.TimerManager;
import server.Trade;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.living.population.CitizenSession;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The drop game in the Henesys Department Store (SoloMapling's DropGameBot and its spectators). A player trades the
 * host a tier's fee (10m medium, 50m elite; anything else is handed back with a word in the trade chat), accepts its
 * party invite, and for two minutes chases it around the store while it drops an item every 4-6 s that only they can
 * pick up and that vanishes after about a second and a half. A thief host casts its own Haste on both first. Citizens
 * on the map now and then cheer a drop. The fee is a sink paid to the host's purse as a trade; the drops come from the
 * pools in casino.yaml.
 */
final class DropGame extends HostPost<DropGame.DropPost> {
    private static final int HASTE = 4101004;
    private static final int BANDIT_HASTE = 4201003;
    private static final long PARTY_WAIT_MS = 20_000L;
    private static final long TRADE_WAIT_MS = 60_000L;
    private static final int SPECIAL_PERCENT = 33;

    enum Stage { IDLE, TRADE, PARTY, GAME }

    final class DropPost extends HostPost.Post {
        volatile Stage stage = Stage.IDLE;
        volatile Character player;
        volatile CasinoContent.DropTier tier;
        volatile long stageUntil;
        volatile int dropsSinceCheer;
        volatile int nextCheerAt = 7;
        volatile int routeIndex;
        /** The mesos the trade brought in (the fee less the trade fee), handed back if the game cannot start. */
        volatile long received;

        DropPost(CitizenSession session, long until) {
            super(session, until);
        }
    }

    DropGame(CasinoHost host, CasinoContent content) {
        super(host, content);
    }

    @Override
    public String name() {
        return "dropgame";
    }

    @Override
    public int mapId() {
        return content.dropGameMap();
    }

    @Override
    public boolean enabled() {
        return host.population().config().DROP_GAME_ENABLED;
    }

    @Override
    Point spot() {
        return content.dropHost();
    }

    @Override
    DropPost open(CitizenSession session, long until) {
        return new DropPost(session, until);
    }

    @Override
    boolean wantsQuick() {
        return true;
    }

    @Override
    boolean busy(DropPost post) {
        return post.stage != Stage.IDLE;
    }

    @Override
    void step(DropPost post, long now) {
        if (post.stage == Stage.IDLE && host.random().nextInt(100) < 15 && host.humanOn(post.session.channel, mapId())) {
            host.say(post.session, "casino.drop.advertise", fees());
        }
    }

    @Override
    void quick(DropPost post, long now) {
        Character agent = post.agent();
        if (agent == null) {
            return;
        }
        switch (post.stage) {
            case IDLE -> {
                Character inviter = TradeDesk.inviter(agent);
                if (inviter != null) {
                    post.player = inviter;
                    post.stage = Stage.TRADE;
                    post.stageUntil = now + TRADE_WAIT_MS;
                    host.after(1_000L + host.random().nextInt(1_500), () -> host.runAs(post.session, () -> {
                        TradeDesk.accept(agent, inviter);
                        TradeDesk.chat(agent, line(post, "casino.drop.rules", fees()));
                    }));
                }
            }
            case TRADE -> trade(post, agent, now);
            case PARTY -> party(post, agent, now);
            case GAME -> {
                if (post.player == null || post.player.getMapId() != mapId() || !post.player.isLoggedinWorld()) {
                    host.say(post.session, "casino.drop.gone", Map.of("name", post.player == null ? "" : post.player.getName()));
                    end(post, agent);
                } else if (now >= post.stageUntil) {
                    host.say(post.session, "casino.drop.end", Map.of("name", post.player.getName()));
                    agent.changeFaceExpression(2);
                    end(post, agent);
                }
            }
        }
    }

    private void trade(DropPost post, Character agent, long now) {
        Character partner = TradeDesk.partner(agent);
        if (partner == null) {
            if (agent.getTrade() == null || now > post.stageUntil) {
                if (agent.getTrade() != null) {
                    TradeDesk.cancel(agent);
                }
                reset(post);
            }
            return;
        }
        if (!TradeDesk.partnerLocked(agent)) {
            if (now > post.stageUntil) {
                TradeDesk.chat(agent, line(post, "casino.drop.slow", Map.of()));
                TradeDesk.cancel(agent);
                reset(post);
            }
            return;
        }
        int offered = TradeDesk.offered(agent);
        CasinoContent.DropTier tier = null;
        for (CasinoContent.DropTier candidate : content.dropTiers()) {
            if (candidate.fee() == offered) {
                tier = candidate;
            }
        }
        if (tier == null) {
            TradeDesk.chat(agent, line(post, "casino.drop.wrong", fees()));
            host.after(1_500L, () -> host.runAs(post.session, () -> TradeDesk.cancel(agent)));
            reset(post);
            return;
        }
        Character player = post.player;
        if (player != null && player.getParty() != null && player.getParty() != agent.getParty()) {
            // the game is played in the host's party: one already in a party cannot be invited
            TradeDesk.chat(agent, line(post, "casino.drop.inparty", Map.of()));
            host.after(1_500L, () -> host.runAs(post.session, () -> TradeDesk.cancel(agent)));
            reset(post);
            return;
        }
        CasinoContent.DropTier chosen = tier;
        post.tier = chosen;
        post.stage = Stage.PARTY;
        post.stageUntil = now + PARTY_WAIT_MS;
        host.runAs(post.session, () -> {
            TradeDesk.chat(agent, line(post, "casino.drop.locked", Map.of("tier", chosen.id())));
            long before = agent.getMeso();
            TradeDesk.confirm(agent);
            long received = agent.getMeso() - before;
            if (received < chosen.fee() - Trade.getFee(chosen.fee())) {
                reset(post); // the trade did not go through (cancelled, a meso cap): no fee, no game
                return;
            }
            post.received = received;
            host.after(2_500L, () -> host.runAs(post.session, () -> invite(post, agent)));
        });
    }

    /** The host parties the player who paid; if it cannot, it hands the fee back. */
    private void invite(DropPost post, Character agent) {
        Character player = post.player;
        if (post.stage != Stage.PARTY || player == null) {
            return;
        }
        if (agent.getParty() != null && agent.getParty() == player.getParty()) {
            return;
        }
        if (agent.getParty() == null) {
            AgentPartyGatewayRuntime.party().createAgentParty(agent);
        }
        if (AgentPartyGatewayRuntime.party().invitePartyMember(agent, player)) {
            host.say(post.session, "casino.drop.party", Map.of("name", player.getName()));
            return;
        }
        if (Floor.purse(agent, player, post.received)) {
            host.say(post.session, "casino.drop.refund", Map.of("name", player.getName()));
        }
        end(post, agent);
    }

    private void party(DropPost post, Character agent, long now) {
        Character player = post.player;
        if (player != null && agent.getParty() != null && player.getParty() == agent.getParty()) {
            host.say(post.session, "casino.drop.start", Map.of("name", player.getName(), "tier", post.tier.id()));
            haste(agent, player);
            post.stage = Stage.GAME;
            post.stageUntil = now + content.dropGameMs();
            host.after(3_000L + host.random().nextInt(3_000), () -> dropNext(post));
            walkRoute(post);
            return;
        }
        if (player == null || player.getMapId() != mapId() || now > post.stageUntil) {
            host.say(post.session, "casino.drop.forfeit", Map.of("name", player == null ? "" : player.getName()));
            end(post, agent);
        }
    }

    /** A thief host casts its own Haste on itself and the player. */
    private void haste(Character agent, Character player) {
        for (int skillId : new int[]{HASTE, BANDIT_HASTE}) {
            int level = agent.getSkillLevel(skillId);
            Skill skill = level > 0 ? SkillFactory.getSkill(skillId) : null;
            if (skill != null) {
                skill.getEffect(level).applyTo(agent);
                skill.getEffect(level).applyTo(player);
                return;
            }
        }
    }

    private void walkRoute(DropPost post) {
        if (post.stage != Stage.GAME || posts.get(post.session.channel) != post) {
            return;
        }
        List<Point> route = content.dropRoute();
        if (!route.isEmpty() && post.session.entry != null) {
            post.routeIndex = (post.routeIndex + 1 + host.random().nextInt(Math.max(1, route.size() - 1))) % route.size();
            AgentMovementCommandRuntime.moveTo(post.session.entry, route.get(post.routeIndex), false);
        }
        host.after(3_500L + host.random().nextInt(2_500), () -> walkRoute(post));
    }

    private void dropNext(DropPost post) {
        Character agent = post.agent();
        Character player = post.player;
        if (post.stage != Stage.GAME || agent == null || player == null || System.currentTimeMillis() >= post.stageUntil) {
            return;
        }
        CasinoContent.Weighted entry = roll(content.dropPools().get(post.tier.id()), host.random());
        MapleMap map = agent.getMap();
        if (entry != null && map != null) {
            Item item = ItemConstants.getInventoryType(entry.itemId()) == client.inventory.InventoryType.EQUIP
                    ? ItemInformationProvider.getInstance().getEquipById(entry.itemId()) : new Item(entry.itemId(), (short) 0, (short) 1);
            MapItem drop = Floor.drop(map, agent, player, item, new Point(agent.getPosition()), true);
            if (drop != null) {
                TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
                    if (!drop.isPickedUp()) {
                        map.makeDisappearItemFromMap(drop);
                    }
                }, post.tier.despawnMs());
            }
            cheer(post, map, ItemInformationProvider.getInstance().getName(entry.itemId()));
        }
        host.after(4_000L + host.random().nextInt(2_000), () -> dropNext(post));
    }

    /** Every 7-12 drops a citizen on the map who is not the host says something about it. */
    private void cheer(DropPost post, MapleMap map, String itemName) {
        if (++post.dropsSinceCheer < post.nextCheerAt || itemName == null) {
            return;
        }
        post.dropsSinceCheer = 0;
        post.nextCheerAt = 7 + host.random().nextInt(6);
        List<CitizenSession> watching = new ArrayList<>();
        for (CitizenSession session : host.population().presence().sessions()) {
            if (session != post.session && session.loaded() && session.mapId == map.getId()
                    && session.channel == post.session.channel) {
                watching.add(session);
            }
        }
        if (watching.isEmpty()) {
            return;
        }
        CitizenSession by = watching.get(host.random().nextInt(watching.size()));
        host.after(1_500L + host.random().nextInt(3_500), () -> host.say(by, "casino.drop.cheer",
                Map.of("item", itemName.toLowerCase(java.util.Locale.ROOT),
                        "name", post.player == null ? "" : post.player.getName())));
    }

    private void end(DropPost post, Character agent) {
        host.runAs(post.session, () -> {
            if (agent.getParty() != null) {
                AgentPartyGatewayRuntime.party().leaveCurrentParty(agent);
            }
        });
        reset(post);
        walk(post.session, spot());
    }

    private void reset(DropPost post) {
        post.stage = Stage.IDLE;
        post.player = null;
        post.tier = null;
        post.received = 0;
    }

    @Override
    void closing(DropPost post) {
        Character agent = post.agent();
        if (agent != null && agent.getTrade() != null) {
            TradeDesk.cancel(agent);
        }
    }

    @Override
    public boolean heard(Character human, String text, long now) {
        return false; // the drop game is entered by trade
    }

    private Map<String, Object> fees() {
        List<CasinoContent.DropTier> tiers = content.dropTiers();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tiers.size(); i++) {
            out.append(i == 0 ? "" : i == tiers.size() - 1 ? " or " : ", ").append(tiers.get(i).id()).append(' ')
                    .append(Chips.mesos(tiers.get(i).fee()));
        }
        return Map.of("fees", out.toString());
    }

    /** A filled line of the trigger in the host's voice, for the trade chat; null when none fills. */
    private String line(DropPost post, String trigger, Map<String, ?> slots) {
        server.living.population.ChatterEngine.Event event = server.living.population.ChatterEngine.Event.of(trigger,
                post.session.mapId, post.session.channel).self(post.session);
        for (Map.Entry<String, ?> slot : slots.entrySet()) {
            event.slot(slot.getKey(), String.valueOf(slot.getValue()));
        }
        String line = host.population().chatter().compose(event);
        return line == null ? null
                : host.population().voices().renderAnswer(post.session.citizen, line, System.currentTimeMillis(), false);
    }

    /** One item of a pool: a special one a third of the time when the pool has them. */
    static CasinoContent.Weighted roll(CasinoContent.DropPool pool, Random random) {
        if (pool == null) {
            return null;
        }
        boolean special = !pool.special().isEmpty() && (pool.items().isEmpty() || random.nextInt(100) < SPECIAL_PERCENT);
        return pick(special ? pool.special() : pool.items(), random);
    }

    static CasinoContent.Weighted pick(List<CasinoContent.Weighted> entries, Random random) {
        int total = 0;
        for (CasinoContent.Weighted entry : entries) {
            total += Math.max(0, entry.weight());
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (CasinoContent.Weighted entry : entries) {
            roll -= Math.max(0, entry.weight());
            if (roll < 0) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }
}
