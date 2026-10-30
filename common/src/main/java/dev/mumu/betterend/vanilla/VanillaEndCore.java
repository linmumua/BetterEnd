package dev.mumu.betterend.vanilla;

/**
 * The disc around the world origin that stays vanilla so the vanilla End furniture -- the obsidian
 * pillars, their end crystals, the exit portal and the dragon fight that reads them -- keeps
 * working.
 *
 * <p><b>This plugin never places that furniture.</b> Vanilla does, and it has to, because
 * {@code EnderDragonFight.getSpikeCrystals()} finds the dragon's healing crystals by scanning the
 * bounding boxes of {@code EndSpikeFeature.getSpikesForLevel(level)} -- a seed-derived spike list
 * with no Bukkit or Paper accessor (Paper-26.1.2
 * {@code paper-server/patches/sources/net/minecraft/world/level/dimension/end/EnderDragonFight.java.patch:63-66}).
 * Pillars placed by a plugin land at coordinates that list does not contain, so the crystals fall
 * outside every {@code spike.getTopBoundingBox()}, the dragon never heals, and SUMMONING_PILLARS
 * beams at empty air. That failure looks fixed, which makes it worse than the current visible
 * breakage. The whole restoration is therefore two settings owned by other files, and this class
 * only holds the constant they must agree on:
 *
 * <ul>
 *   <li>{@code DustWastelandsGenerator.shouldGenerateDecorations()} must return {@code true} --
 *       {@code EndSpikeFeature} is a placed feature of the {@code minecraft:the_end} biome and runs
 *       in the vanilla decoration pass, which that flag gates
 *       ({@code CustomChunkGenerator.java:306}).</li>
 *   <li>The BiomeProvider must return {@code Biome.THE_END} for every block in
 *       {@link #inCoreQuart}, because a chunk only carries the feature list of the biomes actually
 *       painted in it.</li>
 * </ul>
 *
 * <p>Both are independently sufficient to break the pillars, so both are required.
 *
 * <p><b>This is now the ONLY vanilla region.</b> It replaces the second one,
 * {@code BiomePlacement.inCentralRing} (a 1024-block disc), which used to hand the surface path
 * plain end stone as far out as r=1024 and flipped biome id, surface, dust cap, speckle, filler,
 * fog and the tree gate along a chunk-quantized rim that cut straight across real islands. That rim
 * was the visible seam players reported. BetterEnd biomes and surfaces now paint everything outside
 * this disc, and this one remaining boundary is hard because it lies in void -- a hard edge across
 * empty space has no ground to show it, which is the mod's own design
 * ({@code EndLandBiomeDecider.java:47-55} is an equals() on a tag, no falloff).
 *
 * <p>Pure functions of block coordinates, no state, so it is deterministic and safe to call from
 * the concurrent worldgen threads.
 */
public final class VanillaEndCore {

    /**
     * {@code EndSpikeFeature.java:53 SPIKE_DISTANCE = 42} -- SETTLED, from a CFR decompile of the
     * running {@code paper-26.2.jar}. The ten centres it produces are seed-independent and are
     * enumerated by {@link EndSpikes#forSeed}; nothing is placed at this radius, it only sizes
     * {@link #CORE_RADIUS} with margin.
     */
    public static final int SPIKE_RING_RADIUS = 42;

    /**
     * SETTLED, and it was WRONG at 3. {@code EndSpikeFeature.java:186} is {@code radius = 2 + size/3}
     * over {@code size} 0..9, so the real range is 2..5 and radius-4 and radius-5 spikes exist on
     * every seed. Raising it only tightens the margins the tests assert; nothing sizes an array by
     * it.
     */
    public static final int SPIKE_MAX_RADIUS = 5;

    /**
     * Radius, in blocks, of the disc painted {@code minecraft:the_end} unconditionally.
     *
     * <p>A circle, matching the mod's own inner-void cull, which is a circle in block space
     * ({@code IslandLayer.java:93-94}, ported at {@code IslandField.java:449}). That makes the
     * margin a single number comparable directly against the spike footprint and the island reach.
     *
     * <p>Deliberately NOT conditioned on terrain. Reusing an island-density probe makes each pillar
     * depend on {@code BiomePlacement.isLand}, whose column scan steps 4 blocks and can skip a thin
     * slab; one quart column reading void under one spike centre silently drops that pillar, and a
     * nine-pillar ring looks deliberate while the dragon heals from nine crystals. One {@code long}
     * multiply removes the whole class of failure.
     *
     * <p>384 is bounded on both sides by measurement, with wide margins either way:
     * <ul>
     *   <li>Floor ~96: the boundary must clear the pinned central island so it sits over void. Its
     *       radius is {@code 0.5 (unit SDF) * 1.3 (fixed scale, IslandField:461) * 100 (medium
     *       layer scale)} ~= 65, measured 78.9-85.6 across five seeds once radial noise is on.</li>
     *   <li>Ceiling ~874: land resumes where islands whose centres cleared
     *       {@code IslandField.INNER_VOID_RADIUS_SQUARED} (1024 blocks) spill inward -- big layer
     *       scale 200 x island scale <= 1.5 -> <= 150 blocks of reach. Zero land samples were found
     *       anywhere in r in (100, 900) on every seed probed.</li>
     *   <li>Spike footprint 47.3 = {@code sqrt((42+5)^2 + 5^2)}. 384 / 47.3 ~= 8.1x. The ring
     *       radius is no longer inferred ({@link EndSpikes}), so this margin is now free headroom
     *       rather than insurance, and it is what lets the disc stay unchanged at 384.</li>
     * </ul>
     *
     * <p>The spike chunks (-3..2 on both axes) are WHOLLY inside the disc -- every one of their
     * 9216 blocks passes {@link #inCoreQuart} -- so they resolve to {@code the_end} at every
     * position within them. Vanilla's biome-check position for a placed feature (feature origin vs
     * spike centre vs chunk origin) is not readable in this checkout, and whole-chunk coverage over
     * the spike chunks makes it not matter. Note the disc is a circle, so chunks out near the rim
     * are NOT wholly inside it (chunk 23's corner sits at r=542); only the spike chunks need to be.
     *
     * <p>ponytail: this is the calibration knob, and it has two knobs above it. An owner who raises
     * {@code generator.layers.medium.scale} past ~590 grows the spawn island past this boundary and
     * puts ground on the seam; the fix is to raise this number, not to add a blend. And the "no
     * ground on the boundary" property holds because the mod's central-island flag owns BOTH the
     * pinned island and the inner-void cull ({@code IslandField:449,461}) and is not configurable
     * here -- it is always on, as the mod ships it. Turning it off would fill the whole origin with
     * ordinary islands, and this boundary -- like the 1024-block one it replaced, and like any
     * boundary at any radius -- would then cut across real land (measured: ~1600 solid columns
     * straddle r=384 on each of three seeds). Nothing can fix that but not drawing a boundary,
     * which the pillars forbid.
     */
    public static final int CORE_RADIUS = 384;

    private static final long CORE_RADIUS_SQUARED = (long) CORE_RADIUS * CORE_RADIUS;

    private VanillaEndCore() {
    }

    /** True where the biome provider must return {@code Biome.THE_END}, from block coordinates. */
    public static boolean inCore(int x, int z) {
        return (long) x * x + (long) z * z <= CORE_RADIUS_SQUARED;
    }

    /**
     * {@link #inCore} for the quart CONTAINING this block. <b>Every consumer uses this one.</b>
     *
     * <p>Biomes are only ever asked at quart origins ({@code CustomWorldChunkManager.java:32-34},
     * QuartPos.toBlock), while the surface path is called per block column. A raw per-block
     * {@link #inCore} would let up to 3 block columns inside a cut quart write a surface that
     * contradicts the biome F3 names. Quantizing every caller to the quart origin makes the
     * provider's answer and the surface path's answer the same evaluation by construction.
     *
     * <p>{@code & ~3} is the floor multiple of 4 for negatives too -- exactly {@code quart << 2},
     * and exactly what {@code BiomePlacement.quartAt} does. {@code / 4 * 4} rounds toward zero and
     * would get the -x/-z half of the ring wrong.
     */
    public static boolean inCoreQuart(int x, int z) {
        return inCore(x & ~3, z & ~3);
    }

    /**
     * Lowest and highest chunk coordinate touched by {@link #inCore}, inclusive on both ends.
     * Derived rather than written down, because it is quoted to server owners as the chunks to
     * delete when migrating a world generated by the broken build, and an off-by-one there leaves
     * a pillar in a stale chunk.
     */
    public static int coreChunkMin() {
        return -CORE_RADIUS >> 4;
    }

    /** @see #coreChunkMin() */
    public static int coreChunkMax() {
        return CORE_RADIUS >> 4;
    }
}
