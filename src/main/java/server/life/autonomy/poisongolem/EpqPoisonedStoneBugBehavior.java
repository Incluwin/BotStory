package server.life.autonomy.poisongolem;

import client.Character;
import server.life.Monster;
import server.life.autonomy.BossActorBehavior;
import server.life.autonomy.GenericWzMobBehavior;
import server.life.autonomy.ServerMobActionCatalog;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Server movement/combat for the Stage 2 bug while an Agent lures it to the pond. */
public final class EpqPoisonedStoneBugBehavior implements BossActorBehavior {
    public static final int MOB_ID = 9_300_173;
    private final GenericWzMobBehavior delegate = new GenericWzMobBehavior(MOB_ID);

    @Override public int mobId() { return MOB_ID; }
    @Override public boolean forceServerAuthority() { return true; }
    @Override public boolean usesServerMobPhysics() { return true; }
    @Override public boolean usesPrimaryAggroTargetOnly() { return true; }

    @Override
    public Optional<SelectedAction> select(Monster monster, List<Character> targets,
                                           ServerMobActionCatalog.MonsterActions actions,
                                           RandomGenerator random) {
        return delegate.select(monster, targets, actions, random);
    }
}
