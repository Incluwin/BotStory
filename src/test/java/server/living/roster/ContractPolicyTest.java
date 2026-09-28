package server.living.roster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.bond.ContractService.Mode;
import server.living.config.Content;
import server.living.config.Content.BondStage;
import server.living.config.Content.Contract;
import server.living.config.Content.LeaveRule;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ROSTER_CONTRACT_MODE (decision 3, docs/BOT_MANAGEMENT_PLAN.md "Contract policy + deferral"): every mode against
 * every authored end rule, with the companion committed to a run or not. A rule the mode keeps ends the contract
 * through the release path at once, or, committed, once the run is over after one "after this run" line; a rule the
 * mode drops leaves the companion on the roster without a word. Also: the clock counts for companions on their way
 * or waiting outside a run, and a chat bench warns in character only where a bench can end the contract.
 */
class ContractPolicyTest {
    private static final String RUN = "kpq-7";
    private static final int HADRIK = 501;

    /** The authored end rules ContractService applies (DISMISS and arcs end a contract explicitly in every mode). */
    enum Rule {
        CONTRACT_END("contract ended"),
        OUTLEVELLED("outlevelled"),
        LEADER_DEATH_3X("leader died three times"),
        BENCH_TOLERANCE("walked out");

        final String reason;

        Rule(String reason) {
            this.reason = reason;
        }
    }

    /** A mode, and the owner's bond stage with the companion under it. */
    enum Setting {
        AUTHORED(Mode.AUTHORED, BondStage.TRUSTED, true),
        PERMANENT_ON_ROSTER(Mode.PERMANENT_ON_ROSTER, BondStage.TRUSTED, false),
        PERMANENT_AT_SWORN_BEFORE_SWORN(Mode.PERMANENT_AT_SWORN, BondStage.TRUSTED, true),
        PERMANENT_AT_SWORN_AT_SWORN(Mode.PERMANENT_AT_SWORN, BondStage.SWORN, false);

        final Mode mode;
        final BondStage stage;
        final boolean authoredTermsHold;

        Setting(Mode mode, BondStage stage, boolean authoredTermsHold) {
            this.mode = mode;
            this.stage = stage;
            this.authoredTermsHold = authoredTermsHold;
        }
    }

    static Stream<Arguments> everyModeRuleAndCommitment() {
        List<Arguments> cases = new ArrayList<>();
        for (Setting setting : Setting.values()) {
            for (Rule rule : Rule.values()) {
                cases.add(Arguments.of(setting, rule, false));
                cases.add(Arguments.of(setting, rule, true));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} committed={2}")
    @MethodSource("everyModeRuleAndCommitment")
    void eachModeKeepsOrDropsEachAuthoredEndRule(Setting setting, Rule rule, boolean committed) {
        try (Game game = new Game(setting, rule == Rule.BENCH_TOLERANCE ? 0 : -1)) {
            Persona hadrik = game.hadrik(rule == Rule.BENCH_TOLERANCE ? RosterState.BENCHED : RosterState.ACTIVE);
            if (committed) {
                assertTrue(game.h.commitments.commit(RUN, LifecycleHarness.ANN, HADRIK));
            }

            game.due(rule);
            game.due(rule); // due again the next minute (or the next death): said once, never twice

            if (!setting.authoredTermsHold) {
                assertNotNull(game.h.row(hadrik), "kept on the roster");
                assertEquals(List.of(), game.said, "nothing to say");
                return;
            }
            if (!committed) {
                assertNull(game.h.row(hadrik), "released at once");
                assertEquals(List.of(LifecycleHarness.ANN + " hadrik roster.release " + rule.reason),
                        game.h.store.events("roster.release"));
                assertEquals(1, game.said.size(), game.said.toString()); // its goodbye
                return;
            }
            assertNotNull(game.h.row(hadrik), "the walk-out waits for the run");
            assertEquals(List.of(ContractService.AFTER_THIS_RUN_LINE), game.said);
            assertEquals(List.of(), game.h.store.events("roster.release"));

            game.h.commitments.releaseKey(RUN); // the run is over

            assertNull(game.h.row(hadrik), "it goes by the release path once the run is over");
            assertEquals(List.of(LifecycleHarness.ANN + " hadrik roster.release " + rule.reason),
                    game.h.store.events("roster.release"));
            assertEquals(2, game.said.size(), game.said.toString()); // then its goodbye
        }
    }

    @Test
    void theDefaultModeIsPermanentOnRoster() {
        assertEquals("PERMANENT_ON_ROSTER", new config.LivingWorldConfig().ROSTER_CONTRACT_MODE);
        assertEquals(Mode.PERMANENT_ON_ROSTER, Mode.of("PERMANENT_ON_ROSTER"));
        assertEquals(Mode.PERMANENT_ON_ROSTER, Mode.of("nonsense"));
        assertEquals(Mode.PERMANENT_AT_SWORN, Mode.of("permanent_at_sworn"));
    }

    @Test
    void anUnknownModeIsRefusedByTheConfig() {
        config.LivingWorldConfig config = new config.LivingWorldConfig();
        config.ROSTER_CONTRACT_MODE = "FOREVER";
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, config::validate);
        config.ROSTER_CONTRACT_MODE = "AUTHORED";
        config.validate();
    }

    @Test
    void configYamlCarriesTheKeyInAscii() throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("config.yaml"));
        String yaml = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    ROSTER_CONTRACT_MODE: PERMANENT_ON_ROSTER\n"), "config.yaml living_world carries the key");
        for (byte b : bytes) {
            assertTrue(b >= 0, "config.yaml stays US-ASCII");
        }
    }

    @Test
    void anArcEndsAContractExplicitlyInEveryMode() {
        for (Setting setting : Setting.values()) {
            try (Game game = new Game(setting, -1)) {
                Persona hadrik = game.hadrik(RosterState.ACTIVE);

                game.contracts.end(game.h.ann, hadrik, "arc: the last voyage");

                assertNull(game.h.row(hadrik), setting.name());
            }
        }
    }

    @Test
    void companionsOnTheWayOrWaitingOutsideARunKeepTheirContractClock() {
        for (RosterState state : List.of(RosterState.TRAVELLING, RosterState.HOLD)) {
            try (Game game = new Game(Setting.AUTHORED, -1)) {
                Persona hadrik = game.hadrik(state);
                game.bond(3_600);

                game.contracts.tick();

                assertEquals(3_660, game.savedPartySeconds.get(game.savedPartySeconds.size() - 1), state.name());
                assertNotNull(game.h.row(hadrik));
                game.bond(6 * 3_600 - 30);

                game.contracts.tick();

                assertNull(game.h.row(hadrik), state + ": its travel hours are up");
            }
        }
    }

    @Test
    void aChatBenchWarnsInCharacterOnlyWhereABenchCanEndTheContract() {
        for (Setting setting : Setting.values()) {
            try (Game game = new Game(setting, 0)) {
                Persona hadrik = game.hadrik(RosterState.ACTIVE);

                game.h.roster().bench(game.h.ann, hadrik);

                assertEquals(RosterState.BENCHED, game.h.row(hadrik).state());
                assertEquals(setting.authoredTermsHold ? List.of("an hour at camp with nothing to do and i walk.")
                        : List.of("alright. i'll keep busy."), game.said, setting.name());
            }
        }
    }

    @Test
    void aPatientCompanionIsBenchedWithoutAWarning() {
        try (Game game = new Game(Setting.AUTHORED, 3)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);

            game.h.roster().bench(game.h.ann, hadrik);

            assertEquals(List.of("alright. i'll keep busy."), game.said);
            assertNull(game.contracts.benchWalkOutAfter(LifecycleHarness.ANN, hadrik));
        }
    }

    @Test
    void aCompanionTurnedBackOnItsWayWhispersTheWarning() {
        try (Game game = new Game(Setting.AUTHORED, 1)) {
            Persona hadrik = game.hadrik(RosterState.TRAVELLING);

            game.h.roster().bench(game.h.ann, hadrik);

            assertEquals(List.of("a day at camp with nothing to do and i walk."), game.whispered);
        }
    }

    /** Ann and Hadrik (a companion under an authored contract) in the lifecycle harness, with the real ContractService. */
    static final class Game implements AutoCloseable {
        final LifecycleHarness h = new LifecycleHarness();
        final BondService bonds = mock(BondService.class);
        final ContractService contracts;
        final List<String> said = new ArrayList<>();
        final List<String> whispered = new ArrayList<>();
        final List<Integer> savedPartySeconds = new ArrayList<>();
        private final Setting setting;
        private final int benchToleranceDays;
        private Persona hadrik;

        Game(Setting setting, int benchToleranceDays) {
            this(setting, benchToleranceDays, null);
        }

        /** {@code ops}: where the commitment listener hands its work (companion-ops); null runs it at once. */
        Game(Setting setting, int benchToleranceDays, List<Runnable> ops) {
            this.setting = setting;
            this.benchToleranceDays = benchToleranceDays;
            h.config.ROSTER_CONTRACT_MODE = setting.mode.name();
            when(h.world.bonds()).thenReturn(bonds);
            contracts = new ContractService(h.world);
            when(h.world.contracts()).thenReturn(contracts);
            // LivingWorld hands this to companion-ops
            h.commitments.addListener(ops == null ? contracts::commitmentEnded : ended -> ops.add(() -> contracts.commitmentEnded(ended)));
            doAnswer(inv -> said.add(inv.getArgument(2))).when(h.residents).reply(any(), eq(h.ann), anyString());
            doAnswer(inv -> whispered.add(inv.getArgument(2))).when(h.residents).whisper(any(), eq(h.ann), anyString());
            doAnswer(inv -> savedPartySeconds.add(((Bond) inv.getArgument(0)).partySeconds())).when(bonds).save(any());
        }

        /** Hadrik on Ann's roster in {@code state} (loaded beside her unless benched), under a six-hour contract. */
        Persona hadrik(RosterState state) {
            Persona base = h.companion("hadrik", "Hadrik", HADRIK, state, LifecycleHarness.LITH);
            hadrik = new Persona(base.id(), base.name(), base.title(), base.job(), base.level(), base.strength(),
                    base.loadout(), base.personality(), base.dialogueStyle(), base.homeTown(), base.routine(), true,
                    "last-voyage", base.questLines(), base.arc(), base.relationships(), base.rumors(), base.dialogue(),
                    benchToleranceDays, base.paceGap(), base.maxLag(), base.giftItemIds(), base.portraitNpcId(), base.look(),
                    base.file());
            when(h.content.persona("hadrik")).thenReturn(hadrik);
            when(h.content.contract("last-voyage")).thenReturn(new Contract("last-voyage",
                    new Content.OfferWhen(BondStage.TRUSTED, List.of(), null, null), new Content.Price(0L, List.of(), null),
                    new Content.Terms(6, 10, Content.LootShare.OWN_DROPS, List.of(LeaveRule.DISMISS,
                            LeaveRule.LEADER_OFFLINE_20M, LeaveRule.CONTRACT_END, LeaveRule.LEADER_DEATH_3X), 12, 0L, 15), "test"));
            h.roster();
            if (state != RosterState.BENCHED) {
                h.outWithAnn(base);
                if (state == RosterState.HOLD) {
                    h.members.remove((Integer) HADRIK); // waiting outside the run, out of the party
                }
            }
            bond(60);
            return hadrik;
        }

        /** Ann's bond with Hadrik: an active contract with this much travel on its clock. */
        void bond(int partySeconds) {
            when(bonds.peek(LifecycleHarness.ANN, "hadrik")).thenReturn(new Bond(LifecycleHarness.ANN, "hadrik",
                    setting.stage, 100, new LinkedHashSet<>(), ContractState.ACTIVE, 1_000L, 0L, 0, 0L, partySeconds, 0L, 1_000L));
        }

        /** Makes {@code rule} come due, then lets the contract clock (or the death hook) look. */
        void due(Rule rule) {
            switch (rule) {
                case CONTRACT_END -> {
                    bond(6 * 3_600 - 30);
                    contracts.tick();
                }
                case OUTLEVELLED -> {
                    when(h.ann.getLevel()).thenReturn(40);
                    when(h.bot(hadrik).getLevel()).thenReturn(25);
                    contracts.tick();
                }
                case LEADER_DEATH_3X -> {
                    contracts.onOwnerDied(h.ann);
                    contracts.onOwnerDied(h.ann);
                    contracts.onOwnerDied(h.ann);
                }
                case BENCH_TOLERANCE -> contracts.tick(); // benched at 1_000 ms, so well past the hour
            }
        }

        @Override
        public void close() {
            h.close();
        }
    }

    @Test
    void theGameSetupStartsWithNothingDue() {
        try (Game game = new Game(Setting.AUTHORED, -1)) {
            Persona hadrik = game.hadrik(RosterState.ACTIVE);

            game.contracts.tick();

            assertNotNull(game.h.row(hadrik));
            assertFalse(game.said.stream().anyMatch(line -> line.contains("heading home")));
        }
    }
}
