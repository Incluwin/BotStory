package server.living.bond;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Contract;
import server.living.config.Content.OfferWhen;
import server.living.config.Content.Persona;
import server.living.config.Content.Price;
import server.living.config.Content.Terms;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionExecutor;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A join whose terms are met writes the roster, so it runs on companion-ops (docs/BOT_MANAGEMENT_PLAN.md,
 * "CompanionExecutor"): the persona answers from there, and the terms are checked again there, so a second click
 * queued behind the first is refused in character and charges nothing.
 */
class ContractJoinTest {
    private static final int ANN = 10;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final ArcService arcs = mock(ArcService.class);
    private final RosterService roster = mock(RosterService.class);
    private final BondService bonds = mock(BondService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final List<Runnable> queue = DirectExecutor.queue();
    private final CompanionExecutor ops = DirectExecutor.queued(queue);
    private final Persona olwen = mock(Persona.class);
    private final Character ann = mock(Character.class);
    private int owner;

    @BeforeEach
    void wire() {
        when(world.content()).thenReturn(content);
        when(world.arcs()).thenReturn(arcs);
        when(world.roster()).thenReturn(roster);
        when(world.bonds()).thenReturn(bonds);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.executor()).thenReturn(ops);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(olwen.id()).thenReturn("olwen");
        when(olwen.name()).thenReturn("Olwen");
        when(olwen.contract()).thenReturn("olwen-terms");
        when(content.contract("olwen-terms")).thenReturn(new Contract("olwen-terms",
                new OfferWhen(BondStage.NONE, List.of(), null, null), new Price(1_000L, List.of(), null),
                new Terms(0, 0, null, List.of(), 0, 0L, 0), "olwen.yaml"));
        when(arcs.recruitable(olwen)).thenReturn(true);
        when(arcs.contractUnlocked(olwen)).thenReturn(true);
        when(store.worldFlags()).thenReturn(Set.of());
        Bond offered = new Bond(ANN, "olwen", BondStage.MET, 0, new LinkedHashSet<>(), ContractState.OFFERED, 0L,
                Long.MAX_VALUE, 0, 0L, 0, 0L, 0L);
        when(bonds.peek(ANN, "olwen")).thenReturn(offered);
        when(bonds.bond(ann, olwen)).thenReturn(offered);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getMeso()).thenReturn(5_000);
        when(roster.ownerOf("olwen")).thenAnswer(inv -> owner);
        when(roster.recruit(ann, olwen)).thenAnswer(inv -> {
            owner = ANN;
            return new RosterResult.Ok("Olwen joins your party.");
        });
    }

    @Test
    void aJoinWhoseTermsAreMetIsRecruitedOnCompanionOpsAndAnsweredFromThere() {
        assertNull(new ContractService(world).askToJoin(ann, olwen)); // nothing to say on the packet thread

        verify(roster, never()).recruit(any(), any());
        verify(ann, never()).gainMeso(anyInt(), anyBoolean());
        assertEquals(1, queue.size());
        queue.get(0).run();

        verify(roster).recruit(ann, olwen);
        verify(ann).gainMeso(-1_000, true);
        verify(residents).reply(olwen, ann, "Olwen joins your party.");
    }

    @Test
    void aSecondClickQueuedBehindTheFirstChargesNothing() {
        ContractService contracts = new ContractService(world);
        contracts.askToJoin(ann, olwen);
        contracts.askToJoin(ann, olwen); // both passed the packet thread's checks before either ran

        queue.forEach(Runnable::run);

        verify(roster, times(1)).recruit(ann, olwen);
        verify(ann, times(1)).gainMeso(anyInt(), anyBoolean());
        verify(residents).reply(olwen, ann, "i'm already with you.");
    }
}
