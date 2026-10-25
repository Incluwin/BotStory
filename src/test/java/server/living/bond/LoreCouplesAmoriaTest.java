package server.living.bond;

import client.Character;
import client.Ring;
import client.inventory.Equip;
import constants.id.ItemId;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Why lore couples exist (docs/BOT_LORE_COUPLES_PLAN.md): the stock Amorian Challenge takes six members only when every
 * one is married (AmoriaPQ.js onlyMarriedPlayers, getEligibleParty), and opens the couples' vault when everyone's
 * partner is on the team (isTeamAllCouple). Two lore couples married by {@link LoreCouples} at their load, beside an owner
 * wed to their companion the stock way, pass both with the shipped script; one unmarried companion fails the party.
 */
class LoreCouplesAmoriaTest {
    private static final int RECRUIT_MAP = 670010100;

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @Test
    void twoLoreCouplesAndAnOwnerWedToTheirCompanionPassTheMarriedRuleAndOpenTheVault() throws Exception {
        List<Character> team = team();

        Object eligible = amoria().invokeFunction("getEligibleParty", party(team));

        assertEquals(6, ((PartyCharacter[]) eligible).length, "all six may enter");
        assertTrue((Boolean) amoria().invokeFunction("isTeamAllCouple", instanceOf(team)), "the couples' vault opens");
    }

    @Test
    void oneUnmarriedCompanionKeepsTheWholePartyOut() throws Exception {
        List<Character> team = team();
        team.set(5, LoreCouplesTest.bot(20, "Edric", 0)); // no spouse in his content

        Object eligible = amoria().invokeFunction("getEligibleParty", party(team));

        assertEquals(0, ((PartyCharacter[]) eligible).length);
    }

    /** Owner and companion wed at the chapel (stock rings), then Jasper + Saskia and Ansel + Vesna as lore couples. */
    private static List<Character> team() {
        LoreCouplesTest.FakeLedger ledger = new LoreCouplesTest.FakeLedger();
        LoreCouplesTest.FakeSpouses spouses = new LoreCouplesTest.FakeSpouses();
        LoreCouples couples = new LoreCouples(ledger, spouses, id -> Equip.restored(id, (short) 0));
        spouses.pair(1, 2);
        spouses.pair(3, 4);
        ledger.existing.addAll(List.of(1, 2, 3, 4));
        List<Character> team = new ArrayList<>();
        team.add(wedAtChapel(LoreCouplesTest.bot(10, "Owner", 0), 11));
        team.add(wedAtChapel(LoreCouplesTest.bot(11, "Ilse", 1), 10));
        team.add(LoreCouplesTest.bot(1, "Jasper", 0));
        team.add(LoreCouplesTest.bot(2, "Saskia", 1));
        team.add(LoreCouplesTest.bot(3, "Ansel", 0));
        team.add(LoreCouplesTest.bot(4, "Vesna", 1));
        for (Character member : team.subList(2, 6)) {
            couples.loaded(member);
            assertTrue(member.isMarried(), member.getName());
        }
        return team;
    }

    /** What RingActionHandler.giveMarriageRings leaves on a character wed at the altar. */
    private static Character wedAtChapel(Character chr, int partner) {
        chr.addPlayerRing(new Ring(9000 + chr.getId(), 9000 + partner, partner, ItemId.WEDDING_RING_MOONSTONE, "x"));
        chr.setPartnerId(partner);
        return chr;
    }

    private static List<PartyCharacter> party(List<Character> team) {
        List<PartyCharacter> party = new ArrayList<>();
        for (Character member : team) {
            PartyCharacter seat = mock(PartyCharacter.class);
            when(seat.getPlayer()).thenReturn(member);
            when(seat.getMapId()).thenReturn(RECRUIT_MAP);
            when(seat.getLevel()).thenReturn(70);
            when(seat.isLeader()).thenReturn(member == team.get(0));
            party.add(seat);
        }
        return party;
    }

    private static EventInstanceManager instanceOf(List<Character> team) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getPlayers()).thenReturn(team);
        for (Character member : team) {
            when(eim.getPlayerById(member.getId())).thenReturn(member);
        }
        return eim;
    }

    private static Invocable amoria() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        bindings.put("em", mock(EventManager.class, RETURNS_DEEP_STUBS));
        engine.eval(Files.readString(Path.of("scripts/event/AmoriaPQ.js"), StandardCharsets.US_ASCII));
        return (Invocable) engine;
    }
}
