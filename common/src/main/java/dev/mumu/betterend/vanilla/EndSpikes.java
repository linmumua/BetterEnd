package dev.mumu.betterend.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The ten vanilla obsidian spikes, derived from the world seed instead of read back out of the
 * world.
 *
 * <p>This is a line-for-line port of {@code EndSpikeFeature.SpikeCacheLoader.load} and
 * {@code EndSpikeFeature.getSpikesForLevel}, read from a CFR decompile of the running
 * {@code paper-26.2.jar} ({@code EndSpikeFeature.java:60-63} and {@code :179-192}); it settles what
 * {@link VanillaEndCore}'s javadoc used to call NOT DETERMINABLE. Nothing places spikes -- vanilla
 * still does, for the reasons in {@link VanillaEndCore} -- this only says WHERE they are, so a
 * decoration pass can find them with zero block reads.
 *
 * <p>Every hop is plain {@link Random}. {@code SingleThreadedRandomSource} (:38-51) is the identical
 * 48-bit LCG -- its {@code next(bits)} uses {@code >>} where {@link Random} uses {@code >>>}, which
 * is inert because the seed is masked to 48 bits and is therefore never negative -- and
 * {@code BitRandomSource.nextInt(int)/nextLong()} (:21-41) are byte-identical to {@link Random}'s.
 * So no NMS type is needed and this class stays testable with no server.
 *
 * <p>Pure and stateless: safe to call from any worldgen thread, on Paper and on Folia.
 */
public final class EndSpikes {

    /** {@code EndSpikeFeature.java:52}. */
    public static final int COUNT = 10;
    /** {@code EndSpikeFeature.java:53 SPIKE_DISTANCE}. Confirms {@link VanillaEndCore#SPIKE_RING_RADIUS}. */
    public static final int DISTANCE = 42;

    /**
     * One spike. Mirrors {@code EndSpikeFeature.EndSpike} minus the bounding box, which only the
     * dragon fight needs.
     *
     * @param size the shuffled 0..9 draw the other three fields derive from; kept because the
     *             guarded rule and the radius/height pairing are all functions of it, which is what
     *             makes the test able to cross-check them against each other
     */
    public record Spike(int centerX, int centerZ, int radius, int height, boolean guarded, int size) {
    }

    private EndSpikes() {
    }

    /**
     * The ten spikes for a world seed, in vanilla's own order.
     *
     * <p>The centres are seed-INDEPENDENT ({@code EndSpikeFeature.java:183-184} takes no random
     * input): {@code (42,0) (33,24) (12,39) (-13,39) (-34,24) (-42,-1) (-34,-25) (-13,-40) (12,-40)
     * (33,-25)}. Only which size lands on which centre moves with the seed.
     *
     * <p>ponytail: a datapack that supplies a non-empty {@code EndSpikeConfiguration.getSpikes()}
     * overrides this list ({@code EndSpikeFeature.java:72-75}) and there is no way to see that from
     * a populator. The failure is benign -- the decoration writes into air that nothing reads -- so
     * it is accepted rather than detected.
     */
    public static List<Spike> forSeed(long worldSeed) {
        // EndSpikeFeature.java:61-62 -- the cache key, not the world seed itself.
        long key = new Random(worldSeed).nextLong() & 0xFFFFL;
        int[] sizes = shuffled(key);
        List<Spike> spikes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            // :183-184. 2*(-PI + (PI/10)*i) == -2PI + (PI/5)*i, so this is just i*36 degrees.
            int x = (int) Math.floor(42.0 * Math.cos(2.0 * (-Math.PI + 0.3141592653589793 * i)));
            int z = (int) Math.floor(42.0 * Math.sin(2.0 * (-Math.PI + 0.3141592653589793 * i)));
            int size = sizes[i];
            // :186-188.
            spikes.add(new Spike(x, z, 2 + size / 3, 76 + size * 3, size == 1 || size == 2, size));
        }
        return spikes;
    }

    /** {@code Util.toShuffledList(IntStream, RandomSource)}, {@code Util.java:1015-1023}. */
    private static int[] shuffled(long key) {
        int[] values = new int[COUNT];
        for (int i = 0; i < COUNT; i++) values[i] = i;
        Random random = new Random(key);
        for (int i = COUNT; i > 1; i--) {
            int swapTo = random.nextInt(i);
            int previous = values[swapTo];
            values[swapTo] = values[i - 1];
            values[i - 1] = previous;
        }
        return values;
    }
}
