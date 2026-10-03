package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.looting.AgentLootPolicy.Mode;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchLedger;
import server.living.roster.Commitments;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionSettings;
import server.living.roster.CompanionSettings.Support;
import server.living.roster.CompanionSettingsApplier;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The P3b pages of the companion card (docs/BOT_MANAGEMENT_PLAN.md "P3b: Companion settings", "Player experience >
 * Companion card"): "Fighting style..." (its own place in the formation, or with the others, and its support toggles)
 * and "Supplies & loot..." (what it carries, the potion percentages in the number box, what it picks up, boss drops,
 * and coin for its supplies). Each shows the setting now (as CompanionSettingsApplier keeps it: no roster row is read
 * again to render), is on the card and the bench card, and has Back to the card; a change goes to CompanionManager on
 * companion-ops and is answered in the companion's words on top of the page again. Support is offered only as the
 * companion's job has it to give; the coin box starts at 0, which gives nothing. A click after its row changed opens
 * the card with "things changed" instead; a number outside the box, End Chat and a companion no longer in the roster
 * change nothing; settings never wait for a run. Parting ways shows the purse that stays with the companion.
 */
class CompanionSettingsScreensTest {
    private static final int ANN = 10;
    private static final int HADRIK_PORTRAIT = 9970001;
    private static final int ROSTER_BOOK = 2430011;
    private static final byte MENU = 4;
    private static final byte NUMBER = 3;
    private static final List<String> STYLE = List.of("With the others - now", "Stay close", "Give me room", "Spread out",
            "Keep to my left", "Keep to my right", "Party buffs: on", "Buff potions: off", "Back"); // a Fighter: no heals

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    /** Every roster verb goes through ("Ok"); {@link #own} is what settings() answers. */
    private final CompanionSettingsApplier applier = mock(CompanionSettingsApplier.class);
    private final CompanionManager manager = mock(CompanionManager.class,
            inv -> inv.getMethod().getReturnType() == RosterResult.class ? new RosterResult.Ok("")
                    : Mockito.RETURNS_DEFAULTS.answer(inv));
    private final Commitments commitments = mock(Commitments.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Persona hadrik = persona("hadrik", "Hadrik");
    private final Persona mira = persona("mira", "Mira");
    private final RosterEntry hadrikRow = row("hadrik", RosterState.ACTIVE);
    private final RosterEntry miraRow = row("mira", RosterState.BENCHED);
    private final Character hadrikBot = mock(Character.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final Map<Integer, String> places = Map.of(105040300, "Ant Tunnel I");
    private List<String> potions = List.of("38 Red Potions", "20 Blue Potions");
    /** Hadrik's settings as the manager keeps them: the defaults (70% / 50%, everything, boss drops left). */
    private CompanionSettings own = new CompanionSettings(70, 50, Mode.ALL, true, true, true, false, null, false);

    private MockedStatic<LivingWorld> livingWorld;
    private CompanionMenus menus;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(owner.haveItem(ROSTER_BOOK)).thenReturn(true);
        when(owner.getMeso()).thenReturn(12_000);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(roster.settings()).thenReturn(applier);
        when(applier.settings(eq(ANN), anyString())).thenAnswer(inv -> own); // what the pages render from
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(new LivingWorldConfig());
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.slots()).thenReturn(slots);
        when(world.manager()).thenReturn(manager);
        when(world.bench()).thenReturn(mock(BenchLedger.class));
        when(world.commitments()).thenReturn(commitments);
        when(slots.occupied(owner)).thenReturn(1);
        when(content.portraitNpcId("hadrik")).thenReturn(HADRIK_PORTRAIT);
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(roster.companion(hadrikRow)).thenReturn(hadrikBot);
        when(roster.canSeat(any(), any())).thenReturn(true);
        MapleMap perion = mock(MapleMap.class);
        when(perion.getMapName()).thenReturn("Perion");
        when(hadrikBot.getLevel()).thenReturn(31);
        when(hadrikBot.getJob()).thenReturn(Job.FIGHTER);
        when(hadrikBot.getMap()).thenReturn(perion);
        when(owner.getMap()).thenReturn(perion);
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        when(hadrikBot.getMeso()).thenReturn(12_300);
        when(residents.characterIdOf("hadrik")).thenReturn(900);
        when(residents.characterIdOf("mira")).thenReturn(901);
        when(store.characterLevelAndJob(901)).thenReturn(new int[] {28, Job.HUNTER.getId()});
        when(store.characterMeso(901)).thenReturn(4_500L);
        when(manager.settings(owner, hadrik)).thenAnswer(inv -> rows.stream()
                .anyMatch(entry -> entry.personaId().equals("hadrik")) ? own : null);
        when(manager.settings(owner, mira)).thenAnswer(inv -> rows.contains(miraRow) ? own : null);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> places.getOrDefault(mapId, "map " + mapId));
        menus.cards().catalog = new CompanionCardScreens.Catalog() {
            @Override
            public Map<Integer, Integer> fields(int level, int nearMapId) {
                return Map.of();
            }

            @Override
            public boolean isField(int mapId) {
                return true;
            }

            @Override
            public Map<Integer, Integer> drops(int mapId) {
                return Map.of();
            }

            @Override
            public Map<Integer, Integer> questNeeds(Character player) {
                return Map.of();
            }

            @Override
            public String potions(Character bot) {
                return "38 Red Potions";
            }

            @Override
            public List<String> allPotions(Character bot) {
                return bot == hadrikBot ? potions : List.of();
            }
        };
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        livingWorld.close();
    }

    // ---- fighting style ------------------------------------------------------------------------

    @Test
    void fightingStyleShowsItsPlaceAndItsSupportAsTheyAreNowWithBackToTheCard() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        pick("Fighting style...");

        Talk page = lastTalk();
        assertEquals(HADRIK_PORTRAIT, page.npcId(), "the companion speaks on it");
        assertEquals(MENU, page.msgType());
        assertTrue(page.text().startsWith("how should i fight?\r\n\r\n#eWhere I stand:#n\r\n#L"), page.text());
        // a blank line first: the v83 client draws a plain line right after an option over that option
        assertTrue(page.text().contains("#l\r\n\r\n#eSupport:#n\r\n#L"), page.text());
        assertEquals(STYLE, page.labels());
        pick("Back");
        assertTrue(lastTalk().text().startsWith("#eHadrik#n"), "Back is the card again");
    }

    @Test
    void itsOwnFormationAndATurnedOffToggleReadAsTheyAre() {
        own = own.withFormation("left").withSupport(Support.BUFFS, false).withSupport(Support.BUFF_POTIONS, true);
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        pick("Fighting style...");

        assertEquals(List.of("With the others", "Stay close", "Give me room", "Spread out", "Keep to my left - now",
                "Keep to my right", "Party buffs: off", "Buff potions: on", "Back"), lastTalk().labels());
    }

    @Test
    void theSupportOfferedIsWhatItsJobHasToGive() {
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, miraRow); // a Hunter: no party buff before Sharp Eyes, no heals

        pick("Fighting style...");

        assertEquals(List.of("With the others - now", "Stay close", "Give me room", "Spread out", "Keep to my left",
                "Keep to my right", "Buff potions: off", "Back"), lastTalk().labels());

        LivingTalk.forget(owner);
        when(hadrikBot.getJob()).thenReturn(Job.CLERIC);
        menus.card(owner, hadrikRow);
        pick("Fighting style...");
        assertTrue(lastTalk().labels().containsAll(List.of("Party buffs: on", "Party heals: on", "Buff potions: off")),
                lastTalk().labels().toString());
        verify(manager, never()).settings(any(), any()); // rendered from the applier's memory, no roster row read
    }

    @Test
    void aFormationIsSavedAndAnsweredInItsOwnWordsOnTheSamePage() {
        rows.add(hadrikRow);
        when(manager.setFormation(owner, hadrik, "left")).thenAnswer(inv -> {
            own = own.withFormation("left");
            return new RosterResult.Ok("You tell Hadrik: keep to my left.");
        });
        menus.card(owner, hadrikRow);
        pick("Fighting style...");

        pick("Keep to my left");

        verify(manager).setFormation(owner, hadrik, "left");
        Talk again = lastTalk();
        assertEquals(HADRIK_PORTRAIT, again.npcId());
        assertTrue(again.text().startsWith("Hadrik: ok, keeping to your left.\r\nhow should i fight?"), again.text());
        assertTrue(again.labels().contains("Keep to my left - now"), again.labels().toString());
        assertEquals("Back", again.labels().get(again.labels().size() - 1));

        pick("With the others");

        verify(manager).setFormation(owner, hadrik, null);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, back with the others.\r\n"), lastTalk().text());
    }

    @Test
    void aSupportToggleTurnsItsSettingNowTheOtherWay() {
        rows.add(hadrikRow);
        when(manager.setSupport(any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            own = own.withSupport(inv.getArgument(2), inv.getArgument(3));
            return new RosterResult.Ok("");
        });
        menus.card(owner, hadrikRow);
        pick("Fighting style...");

        pick("Party buffs: on");

        verify(manager).setSupport(owner, hadrik, Support.BUFFS, false);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, no more buffs from me.\r\n"), lastTalk().text());
        assertTrue(lastTalk().labels().contains("Party buffs: off"), lastTalk().labels().toString());

        pick("Buff potions: off");

        verify(manager).setSupport(owner, hadrik, Support.BUFF_POTIONS, true);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll drink my buff potions.\r\n"), lastTalk().text());
    }

    @Test
    void aChangeItCannotTakeSaysWhyOnThePage() {
        rows.add(hadrikRow);
        when(manager.setSupport(any(), any(), any(), anyBoolean()))
                .thenReturn(new RosterResult.Failed("Hadrik is not out with you right now."));
        menus.card(owner, hadrikRow);
        pick("Fighting style...");

        pick("Party buffs: on");

        assertTrue(lastTalk().text().startsWith("Hadrik is not out with you right now.\r\nhow should i fight?"),
                lastTalk().text());
    }

    // ---- supplies & loot -----------------------------------------------------------------------

    @Test
    void suppliesShowWhatItCarriesAndEachSettingNowWithBackToTheCard() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        pick("Supplies & loot...");

        Talk page = lastTalk();
        assertEquals(HADRIK_PORTRAIT, page.npcId());
        assertTrue(page.text().startsWith("i carry 12,300 meso, 38 Red Potions and 20 Blue Potions.\r\n\r\n#L"),
                page.text());
        // a blank line first: the v83 client draws a plain line right after an option over that option
        assertTrue(page.text().contains("#l\r\n\r\n#eWhat I pick up:#n\r\n#L"), page.text());
        assertEquals(List.of("Drink an HP potion below 70%...", "Drink an MP potion below 50%...", "Everything - now",
                "Only what I hit", "Meso and your quest items", "Nothing", "Leave boss drops to you outside boss runs: on",
                "Here's some coin for supplies...", "I brought you something...", "Back"), page.labels());
        pick("Back");
        assertTrue(lastTalk().text().startsWith("#eHadrik#n"), "Back is the card again");
    }

    @Test
    void oneWithNoPotionsSaysSo() {
        potions = List.of();
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        pick("Supplies & loot...");

        assertTrue(lastTalk().text().startsWith("i carry 12,300 meso and no potions.\r\n"), lastTalk().text());
    }

    @Test
    void theBenchCardHasBothPagesAndACampedCompanionsPurseIsItsCharacterRow() {
        rows.add(miraRow);
        menus.card(owner, miraRow);
        assertTrue(lastTalk().labels().containsAll(List.of("Fighting style...", "Supplies & loot...")),
                lastTalk().labels().toString());

        pick("Supplies & loot...");

        assertTrue(lastTalk().text().startsWith("i have 4,500 meso. i'll count my potions when i'm out with you.\r\n"),
                lastTalk().text());
        LivingTalk.forget(owner);
        when(store.characterMeso(901)).thenReturn(-1L); // no such row, or the read failed
        menus.card(owner, miraRow);
        pick("Supplies & loot...");
        assertTrue(lastTalk().text().startsWith("i'll count my meso and potions when i'm out with you.\r\n"),
                lastTalk().text());
        pick("Back");
        assertTrue(lastTalk().text().startsWith("#eMira#n"), "Back is the bench card");
    }

    @Test
    void thePotionBoxesStartAtTheSettingNowAndSaveTheOtherPercentageAsItIs() {
        rows.add(hadrikRow);
        when(manager.setPotionThresholds(any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            own = own.withPotions(inv.getArgument(2), inv.getArgument(3));
            return new RosterResult.Ok("");
        });
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        pick("Drink an HP potion below 70%...");

        Talk box = lastTalk();
        assertEquals(NUMBER, box.msgType());
        assertEquals(HADRIK_PORTRAIT, box.npcId());
        assertEquals("drink an HP potion when my HP falls below what %? (10-90)", box.text());
        assertEquals(List.of(70, CompanionSettings.MIN_POTION_PERCENT, CompanionSettings.MAX_POTION_PERCENT),
                numberBounds(lastTalkPacket()));
        answer(NUMBER, 1, 40);

        verify(manager).setPotionThresholds(owner, hadrik, 40, 50);
        Talk again = lastTalk();
        assertTrue(again.text().startsWith("Hadrik: ok, i'll drink one below 40% HP.\r\ni carry"), again.text());
        assertTrue(again.labels().contains("Drink an HP potion below 40%..."), again.labels().toString());

        pick("Drink an MP potion below 50%...");
        assertEquals(List.of(50, 10, 90), numberBounds(lastTalkPacket()));
        own = own.withPotions(35, 50); // changed another way while the box was open
        answer(NUMBER, 1, 30);

        verify(manager).setPotionThresholds(owner, hadrik, 35, 30);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll drink one below 30% MP.\r\n"), lastTalk().text());
    }

    @Test
    void aPercentageOutsideTheBoxOrEndChatSavesNothing() {
        rows.add(hadrikRow);
        for (Integer reply : new Integer[] {95, 9, null}) {
            menus.card(owner, hadrikRow);
            pick("Supplies & loot...");
            pick("Drink an HP potion below 70%...");

            answer(NUMBER, reply == null ? 0 : 1, reply);

            assertFalse(LivingTalk.active(client), "the box closed: " + reply);
            assertTrue(NpcTalks.isEnableActions(sent().get(sent().size() - 1)), "the client's actions came back");
        }
        verify(manager, never()).setPotionThresholds(any(), any(), anyInt(), anyInt());
    }

    @Test
    void aLootPickIsSavedAndMarkedAsItsSettingNow() {
        rows.add(hadrikRow);
        when(manager.setLootPolicy(any(), any(), any())).thenAnswer(inv -> {
            own = own.withLoot(inv.getArgument(2));
            return new RosterResult.Ok("");
        });
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        pick("Only what I hit");

        verify(manager).setLootPolicy(owner, hadrik, Mode.OWN_KILLS);
        Talk again = lastTalk();
        assertTrue(again.text().startsWith("Hadrik: ok, only what i hit.\r\ni carry"), again.text());
        assertTrue(again.labels().containsAll(List.of("Everything", "Only what I hit - now")), again.labels().toString());

        pick("Meso and your quest items");
        verify(manager).setLootPolicy(owner, hadrik, Mode.QUEST_ONLY);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, only meso and what your quests need.\r\n"), lastTalk().text());

        pick("Nothing");
        verify(manager).setLootPolicy(owner, hadrik, Mode.NONE);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll leave it all to you.\r\n"), lastTalk().text());
    }

    @Test
    void leaveBossDropsToYouTurnsTheOtherWay() {
        rows.add(hadrikRow);
        when(manager.setLeaveBossDrops(any(), any(), anyBoolean())).thenAnswer(inv -> {
            own = own.withLeaveBossDrops(inv.getArgument(2));
            return new RosterResult.Ok("");
        });
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        pick("Leave boss drops to you outside boss runs: on");

        verify(manager).setLeaveBossDrops(owner, hadrik, false);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll pick up boss drops too.\r\n"), lastTalk().text());
        pick("Leave boss drops to you outside boss runs: off");
        verify(manager).setLeaveBossDrops(owner, hadrik, true);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, boss drops are yours.\r\n"), lastTalk().text());
    }

    @Test
    void coinForSuppliesGoesThroughTheBoxUpToWhatYouHave() {
        rows.add(hadrikRow);
        when(manager.giveCoin(owner, hadrik, 2_500L)).thenReturn(new RosterResult.Ok("You give Hadrik 2,500 meso."));
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        pick("Here's some coin for supplies...");

        Talk box = lastTalk();
        assertEquals(NUMBER, box.msgType());
        assertEquals("how much? you have 12,000 meso. (0: never mind)", box.text());
        assertEquals(List.of(0, 0, 12_000), numberBounds(lastTalkPacket()), "from nothing, never more than Ann has");
        answer(NUMBER, 1, 2_500);

        verify(manager).giveCoin(owner, hadrik, 2_500L);
        assertTrue(lastTalk().text().startsWith("You give Hadrik 2,500 meso.\r\ni carry"), lastTalk().text());
    }

    @Test
    void aFewMesoStillStartTheBoxAtNothingAndMoreThanTheBoxGivesNothing() {
        rows.add(hadrikRow);
        when(owner.getMeso()).thenReturn(300);
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");
        pick("Here's some coin for supplies...");
        assertEquals(List.of(0, 0, 300), numberBounds(lastTalkPacket()), "Enter on it never empties a small purse");

        answer(NUMBER, 1, 301);

        assertFalse(LivingTalk.active(client));
        verify(manager, never()).giveCoin(any(), any(), anyLong());
    }

    @Test
    void enterOnTheBoxsNothingGivesNothingAndOpensThePageAgain() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");
        pick("Here's some coin for supplies...");

        answer(NUMBER, 1, 0);

        verify(manager, never()).giveCoin(any(), any(), anyLong());
        assertEquals(MENU, lastTalk().msgType(), "back on the page, not out of the conversation");
        assertTrue(lastTalk().text().startsWith("i carry 12,300 meso"), lastTalk().text());
    }

    @Test
    void partingWaysShowsThePurseThatStaysWithIt() {
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, miraRow);

        pick("We should part ways");

        assertTrue(lastTalk().text().contains("\r\nPurse: 4,500 meso (stays with Mira)"), lastTalk().text());
        LivingTalk.forget(owner);
        when(store.characterMeso(901)).thenReturn(0L);
        menus.card(owner, miraRow);
        pick("We should part ways");
        assertFalse(lastTalk().text().contains("Purse:"), "an empty purse says nothing");
    }

    @Test
    void withAnEmptyPurseThereIsNoCoinToGive() {
        rows.add(hadrikRow);
        when(owner.getMeso()).thenReturn(0);
        menus.card(owner, hadrikRow);

        pick("Supplies & loot...");

        assertFalse(lastTalk().labels().contains("Here's some coin for supplies..."), lastTalk().labels().toString());
        assertTrue(lastTalk().text().contains("\r\n(you have no meso to give)\r\n"), lastTalk().text());
    }

    @Test
    void coinSpentBeforeTheBoxOpensShowsThePageAgainWithoutIt() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");
        when(owner.getMeso()).thenReturn(0); // spent at a shop meanwhile

        pick("Here's some coin for supplies...");

        assertEquals(MENU, lastTalk().msgType(), "no empty box");
        assertTrue(lastTalk().text().contains("(you have no meso to give)"), lastTalk().text());
        verify(manager, never()).giveCoin(any(), any(), anyLong());
    }

    // ---- stale clicks, runs, ownership ---------------------------------------------------------

    @Test
    void aClickAfterItsRowChangedOpensTheCardInsteadOfActing() {
        CompanionViews views = cachedViews();
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        views.invalidate(ANN, "mira"); // Mira's row changed: Hadrik's page is still right
        pick("Nothing");
        verify(manager).setLootPolicy(owner, hadrik, Mode.NONE);

        views.invalidate(ANN, "hadrik"); // his own row changed: the click re-renders the card instead
        pick("Only what I hit");
        verify(manager, never()).setLootPolicy(owner, hadrik, Mode.OWN_KILLS);
        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n#eHadrik#n"), lastTalk().text());

        pick("Fighting style...");
        views.invalidate(ANN, "hadrik");
        pick("Stay close");
        verify(manager, never()).setFormation(any(), any(), any());
        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n#eHadrik#n"), lastTalk().text());
    }

    @Test
    void aStaleClickOnABoxOptionOpensNoBox() {
        CompanionViews views = cachedViews();
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        views.invalidate(ANN, "hadrik");
        pick("Here's some coin for supplies...");

        assertEquals(MENU, lastTalk().msgType());
        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n#eHadrik#n"), lastTalk().text());
    }

    @Test
    void whileTiedToARunItsSettingsStillChange() {
        rows.add(hadrikRow);
        when(commitments.isCommitted(900)).thenReturn(true);
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        pick("Nothing");

        verify(manager).setLootPolicy(owner, hadrik, Mode.NONE);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll leave it all to you.\r\n"), lastTalk().text());
    }

    @Test
    void aChangeDoneWhileAnotherWindowWaitsIsPinkText() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");
        pick("Nothing");
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened meanwhile").show();
        int talks = talks().size();

        queue.get(0).run();

        verify(manager).setLootPolicy(owner, hadrik, Mode.NONE);
        verify(owner).dropMessage(5, "Hadrik: ok, i'll leave it all to you.");
        assertEquals(talks, talks().size(), "nothing over the window awaiting its reply");
    }

    @Test
    void aPageOfOneNoLongerInYourRosterIsNeverOpened() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        rows.clear(); // released from chat while the card was open (no cache to stamp against)
        pick("Supplies & loot...");
        assertEquals("Hadrik is not in your roster.", lastTalk().text());

        LivingTalk.forget(owner);
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        rows.clear();
        pick("Fighting style...");
        assertEquals("Hadrik is not in your roster.", lastTalk().text());
        verify(manager, never()).setFormation(any(), any(), any());
    }

    @Test
    void aChangeToOneReleasedMeanwhileEndsInTheNotes() {
        rows.add(hadrikRow);
        when(manager.setLootPolicy(any(), any(), any())).thenAnswer(inv -> {
            rows.clear(); // parted ways on companion-ops before this change ran
            return new RosterResult.NotOwned();
        });
        menus.card(owner, hadrikRow);
        pick("Supplies & loot...");

        pick("Nothing");

        assertEquals("Hadrik is not in your roster.", lastTalk().text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker(), "no page of one that is gone");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private CompanionViews cachedViews() {
        CompanionViews views = new CompanionViews(world, mapId -> places.getOrDefault(mapId, "map " + mapId),
                itemId -> "item " + itemId);
        when(world.views()).thenReturn(views);
        when(store.roster(ANN)).thenAnswer(inv -> List.copyOf(rows));
        return views;
    }

    private void pick(String label) {
        answer(MENU, 1, lastTalk().selection(label));
    }

    private void answer(byte lastMsg, int action, Integer selection) {
        assertTrue(LivingTalk.respond(client, lastMsg, (byte) action, Packets.buildInPacket(out -> {
            if (selection != null) {
                out.writeInt(selection);
            }
        })));
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    private List<Talk> talks() {
        return NpcTalks.talks(sent());
    }

    private Talk lastTalk() {
        List<Talk> talks = talks();
        return talks.get(talks.size() - 1);
    }

    private Packet lastTalkPacket() {
        List<Packet> talks = sent().stream().filter(NpcTalks::isTalk).toList();
        return talks.get(talks.size() - 1);
    }

    /** A number box's {default, min, max}, after its text. */
    private static List<Integer> numberBounds(Packet packet) {
        byte[] bytes = packet.getBytes();
        int length = (bytes[9] & 0xFF) | (bytes[10] & 0xFF) << 8;
        ByteBuffer tail = ByteBuffer.wrap(bytes, 11 + length, bytes.length - 11 - length).order(ByteOrder.LITTLE_ENDIAN);
        return List.of(tail.getInt(), tail.getInt(), tail.getInt());
    }

    private Persona persona(String id, String name) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(content.persona(id)).thenReturn(persona);
        return persona;
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, 105040300, 0L, 0L, 0L, 0L, 0L, 0L, 0, null);
    }
}
