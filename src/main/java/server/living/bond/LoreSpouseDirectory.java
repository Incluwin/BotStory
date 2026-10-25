package server.living.bond;

import client.Character;
import server.TimerManager;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Spouse;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.Population;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link LoreCouples.Spouses} from content: a persona's {@code spouse} (another persona's character, provisioned when
 * it was never met, or its citizen spouse by name), and a citizen spouse's persona.
 */
final class LoreSpouseDirectory implements LoreCouples.Spouses {
    /** Long enough for the survivor to be standing in its map when it says it. */
    private static final long PARTED_LINE_DELAY_MS = 15_000L;
    private static final String PARTED_FALLBACK = "{name} and i aren't together anymore. i'd rather not talk about it.";

    private final LivingWorld world;

    LoreSpouseDirectory(LivingWorld world) {
        this.world = world;
    }

    @Override
    public int spouseOf(int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        if (persona != null && !LivingContent.isHire(persona.id())) {
            Spouse spouse = persona.spouse();
            if (spouse == null) {
                return 0;
            }
            if (spouse.citizen()) {
                Population population = Population.get();
                Citizen citizen = population == null ? null : population.citizenByName(spouse.name());
                return citizen == null ? -1 : citizen.characterId();
            }
            return characterOf(world.content().persona(spouse.persona()));
        }
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(characterId);
        if (citizen == null || !citizen.has(Citizen.FLAG_LORE_SPOUSE)) {
            return 0;
        }
        for (Persona candidate : world.content().personas().values()) {
            Spouse spouse = candidate.spouse();
            if (spouse != null && spouse.citizen() && citizen.name().equalsIgnoreCase(spouse.name())) {
                return characterOf(candidate);
            }
        }
        return 0;
    }

    /** The persona's character, made now when it was never met; -1 when it cannot be. */
    private int characterOf(Persona persona) {
        if (persona == null) {
            return 0;
        }
        int id = world.residents().ensureCharacter(persona);
        return id > 0 ? id : -1;
    }

    @Override
    public boolean bot(int characterId) {
        if (world.residents().personaOfCharacterId(characterId) != null) {
            return true;
        }
        Population population = Population.get();
        return population != null && population.isCitizen(characterId);
    }

    /** A persona says it once it stands in its map ({@code spouse.parted}, {name} = the former spouse). */
    @Override
    public void parted(Character survivor, String formerName) {
        Persona persona = world.residents().personaOfCharacterId(survivor.getId());
        if (persona == null || LivingContent.isHire(persona.id()) || formerName == null || formerName.isEmpty()) {
            return;
        }
        List<String> lines = persona.lines("spouse.parted");
        String line = (lines.isEmpty() ? PARTED_FALLBACK : lines.get(ThreadLocalRandom.current().nextInt(lines.size())))
                .replace("{name}", formerName);
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            if (survivor.getMap() != null && survivor.isLoggedinWorld()) {
                AgentDialogueTransportRuntime.sayMapNow(survivor, line);
            }
        }, PARTED_LINE_DELAY_MS);
    }
}
