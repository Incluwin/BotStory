package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunMember.Phase;
import server.expeditions.Expedition;
import server.integration.AgentPresence;
import server.life.Monster;
import server.life.MonsterStats;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.seat;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;

/**
 * Who gets a monster's drops and mesos in a companion boss run (P7 Part B step 11, decision B7, through
 * LivingWorldHooks.bossDropOwner from Monster.killBy): when a bench ally of the run topped the damage, its owner gets
 * them while she stands on the monster's map; else the top damager there who is a player and no Agent; with no human
 * there the ally keeps them. After a logout handoff the human it stays with takes its owner's place. An ally with an
 * online human in its party, a party companion, a human, a party quest's event, a field, a run that is gone and the
 * ally switch off all keep the stock owner. Every monster of the run's event counts (a claw as much as the body).
 */
class CompanionBossAlliesDropOwnerTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int ISOLDE = 901;
    private static final int BEN = 20;
    private static final int CARA = 30;

    private final BossAllyHarness h = new BossAllyHarness();
    private final Character ben = human(BEN, "Ben");
    private final Character cara = human(CARA, "Cara");
    private MockedStatic<LivingWorld> living;
    private MapleMap battle;
    private EventInstanceManager fight;

    @BeforeEach
    void annCalledHerAlliesDown() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.game.roster();
        when(h.game.world.bossRuns()).thenReturn(h.bossRuns); // the hook reaches them as LivingWorld wires it
        AgentPresence.install(chr -> h.game.personaOfBot.containsKey(chr.getId()));
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(h.game.world);
    }

    @AfterEach
    void close() {
        living.close();
        h.close();
    }

    @Test
    void anAllyThatToppedTheDamageHandsTheDropToItsOwnerOnTheMap() {
        inTheFight();
        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        Monster claw = monster(AgentBalrogDefinition.INITIAL_CLAW_MOB, battle);
        for (Monster mob : List.of(body, claw)) {
            hit(mob, ally(ORRIN), 900);
            hit(mob, ally(MAGNA), 300);
            hit(mob, h.game.ann, 100);
        }

        assertSame(h.game.ann, dropOwner(body, ally(ORRIN)));
        assertSame(h.game.ann, dropOwner(claw, ally(ORRIN)), "every monster of the run's event");
        assertSame(h.game.ann, dropOwner(body, ally(MAGNA)));
    }

    @Test
    void withItsOwnerOffTheMapTheTopHumanDamagerThereGetsIt() {
        inTheFight();
        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        hit(body, h.game.ann, 400);
        h.game.moveAnn(BossAllyHarness.ROOM, 0, 246); // she went down and came to outside
        hit(body, ally(ORRIN), 900);
        hit(body, ally(MAGNA), 600); // an Agent: never the one
        stands(ben, battle);
        hit(body, ben, 100);
        stands(cara, battle);
        hit(body, cara, 50);

        assertSame(ben, dropOwner(body, ally(ORRIN)));
    }

    @Test
    void withNoHumanOnTheMapTheAllyKeepsIt() {
        inTheFight();
        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        hit(body, h.game.ann, 400);
        h.game.moveAnn(BossAllyHarness.ROOM, 0, 246);
        hit(body, ally(ORRIN), 900);
        hit(body, ally(MAGNA), 600);

        assertSame(ally(ORRIN), dropOwner(body, ally(ORRIN)));
    }

    @Test
    void afterAHandoffTheHumanItStaysWithGetsIt() {
        inTheFight();
        stands(ben, battle);
        stands(cara, battle);
        when(fight.getPlayerById(BEN)).thenReturn(ben);
        when(fight.getPlayerById(CARA)).thenReturn(cara);
        when(fight.getPlayers()).thenReturn(List.of(ally(ORRIN), ben, cara, ally(MAGNA)));
        h.game.annOnline = false;
        h.game.moveAnn(HENESYS, 0, 0);
        h.game.roster().onOwnerLogout(h.game.ann, fight);
        assertTrue(h.open.calls.contains("handTo " + ORRIN + " -> " + BEN), h.open.calls.toString());

        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        hit(body, ally(ORRIN), 900);
        hit(body, cara, 300); // the top human damager, not the heir
        hit(body, ben, 100);

        assertSame(ben, dropOwner(body, ally(ORRIN)));
    }

    @Test
    void aPartyCompanionKeepsTheStockOwner() {
        inTheFight();
        Persona isolde = h.game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, HENESYS);
        h.game.outWithAnn(isolde);
        Character bot = h.game.bot(isolde);
        bot.changeMap(battle, new Point(0, 0));
        when(battle.getCharacterById(ISOLDE)).thenReturn(bot);
        h.open.phases.put(ISOLDE, Phase.INSIDE);
        h.open.anchors.put(ISOLDE, ANN); // a follower of Ann's in the same run
        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        hit(body, bot, 900);
        hit(body, h.game.ann, 100);

        assertSame(bot, dropOwner(body, bot), "Ann is in its party");

        h.game.offline.add(ANN); // her client went away; her logout has not reached her companions yet
        h.game.annOnline = false;
        h.game.moveAnn(HENESYS, 0, 0);
        stands(ben, battle);
        hit(body, ben, 50);

        assertSame(bot, dropOwner(body, bot), "no human left in its party: still no ally");
    }

    @Test
    void anAllyWithAnOnlineHumanInItsPartyKeepsIt() {
        inTheFight();
        stands(ben, battle);
        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        hit(body, ally(ORRIN), 900);
        hit(body, ben, 200);
        hit(body, h.game.ann, 100);
        Party allies = party(ORRIN, MAGNA); // its party of allies holds no human
        when(ally(ORRIN).getParty()).thenReturn(allies);
        assertSame(h.game.ann, dropOwner(body, ally(ORRIN)), "a party of allies only");

        PartyCharacter benRow = member(BEN, false, true);
        List<PartyCharacter> rows = List.of(member(ORRIN, true, true), member(MAGNA, true, true), benRow);
        when(allies.getMembers()).thenReturn(rows);

        assertSame(ally(ORRIN), dropOwner(body, ally(ORRIN)), "Ben, online, shares its party");

        when(benRow.isOnline()).thenReturn(false); // Ben logged off; the party lists him offline

        assertSame(h.game.ann, dropOwner(body, ally(ORRIN)), "an offline human in its party does not count");
    }

    @Test
    void aHumanCandidateKeepsItWithoutAskingTheRuns() {
        inTheFight();
        stands(ben, battle);
        Monster body = monster(AgentBalrogDefinition.BODY_MOB, battle);
        hit(body, ben, 900);
        hit(body, h.game.ann, 100);
        clearInvocations(h.game.world);

        assertSame(ben, dropOwner(body, ben));
        assertSame(h.game.ann, dropOwner(body, h.game.ann));
        verify(h.game.world, never()).bossRuns();
    }

    @Test
    void aPartyQuestEventAFieldOrARunThatIsGoneKeepsTheStockOwner() {
        inTheFight();
        Character orrin = ally(ORRIN);

        MapleMap pq = mock(MapleMap.class); // a party quest's stage: an event with no expedition
        when(pq.getId()).thenReturn(103000800);
        when(pq.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(pq.getCharacterById(ANN)).thenReturn(h.game.ann);
        assertSame(orrin, dropOwner(struck(pq, orrin), orrin));

        MapleMap other = mock(MapleMap.class); // another expedition's fight, with no run of companions
        EventInstanceManager otherFight = mock(EventInstanceManager.class);
        when(otherFight.getExpedition()).thenReturn(mock(Expedition.class));
        when(other.getId()).thenReturn(AgentBalrogDefinition.BATTLE_MAP);
        when(other.getEventInstance()).thenReturn(otherFight);
        when(other.getCharacterById(ANN)).thenReturn(h.game.ann);
        assertSame(orrin, dropOwner(struck(other, orrin), orrin));

        when(h.room.getCharacterById(ANN)).thenReturn(h.game.ann); // a field: no event at all
        assertSame(orrin, dropOwner(struck(h.room, orrin), orrin));

        Monster body = struck(battle, orrin);
        assertSame(h.game.ann, dropOwner(body, orrin), "the run's own fight");
        h.open.gone = true;
        assertSame(orrin, dropOwner(body, orrin), "its run is gone");
    }

    @Test
    void withTheAllySwitchOffTheAllyKeepsIt() {
        inTheFight();
        Monster body = struck(battle, ally(ORRIN));
        assertSame(h.game.ann, dropOwner(body, ally(ORRIN)));

        h.game.config.COMPANION_BOSS_ALLY_TYPES = "";

        assertSame(ally(ORRIN), dropOwner(body, ally(ORRIN)));
    }

    @Test
    void theLiveViewKnowsNoAnchorInARunItWasNotGiven() {
        assertEquals(0, CompanionBossRuns.OpenRuns.LIVE.anchorIn(null, ORRIN));
        assertEquals(0, CompanionBossRuns.OpenRuns.LIVE.anchorIn(h.expedition, ORRIN));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Orrin and Magna signed up and went into the Easy Balrog fight with Ann, who stands in it too: the battle map's
     * copy and its event, whose expedition is Ann's.
     */
    private void inTheFight() {
        h.arrived("orrin", "magna");
        for (int id : List.of(ORRIN, MAGNA)) {
            h.open.phases.put(id, Phase.REGISTERED);
            h.bossRuns.registered(seat(id));
        }
        battle = h.game.annEntersInstance(AgentBalrogDefinition.BATTLE_MAP);
        fight = h.game.ann.getEventInstance();
        when(fight.getExpedition()).thenReturn(h.expedition);
        when(battle.getCharacterById(ANN)).thenAnswer(inv -> h.game.ann.getMap() == battle ? h.game.ann : null);
        for (int id : List.of(ORRIN, MAGNA)) {
            Character ally = ally(id);
            h.open.phases.put(id, Phase.INSIDE);
            ally.changeMap(battle, new Point(0, 0));
            when(ally.getEventInstance()).thenReturn(fight);
            when(fight.getPlayerById(id)).thenReturn(ally);
            when(battle.getCharacterById(id)).thenReturn(ally);
        }
        when(fight.getPlayers()).thenReturn(List.of(ally(ORRIN), ally(MAGNA)));
    }

    /** A monster on {@code map} that {@code ally} hit for 900 and Ann for 100. */
    private Monster struck(MapleMap map, Character ally) {
        Monster monster = monster(AgentBalrogDefinition.BODY_MOB, map);
        hit(monster, ally, 900);
        hit(monster, h.game.ann, 100);
        return monster;
    }

    /** Ann's ally {@code id} as the game has it. */
    private Character ally(int id) {
        return h.game.bots.get(id);
    }

    private static Monster monster(int mobId, MapleMap map) {
        Monster monster = new Monster(mobId, new MonsterStats());
        monster.setMap(map);
        monster.setStartingHp(100_000);
        return monster;
    }

    private static void hit(Monster monster, Character attacker, int damage) {
        monster.applyFakeDamage(attacker, damage, true);
    }

    private static void stands(Character human, MapleMap map) {
        when(human.getMap()).thenReturn(map);
        when(map.getCharacterById(human.getId())).thenReturn(human);
    }

    private static Character dropOwner(Monster monster, Character candidate) {
        return LivingWorldHooks.bossDropOwner(monster, candidate);
    }

    /** A game party whose members are the given bots, all online. */
    private static Party party(int... botIds) {
        Party party = mock(Party.class);
        List<PartyCharacter> rows = new ArrayList<>();
        for (int id : botIds) {
            rows.add(member(id, true, true));
        }
        when(party.getMembers()).thenReturn(rows);
        return party;
    }

    private static PartyCharacter member(int id, boolean bot, boolean online) {
        PartyCharacter row = mock(PartyCharacter.class);
        when(row.getId()).thenReturn(id);
        when(row.isBot()).thenReturn(bot);
        when(row.isOnline()).thenReturn(online);
        return row;
    }

    private static Character human(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.isLoggedin()).thenReturn(true);
        return chr;
    }
}
