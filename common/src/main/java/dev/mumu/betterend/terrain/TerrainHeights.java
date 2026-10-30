package dev.mumu.betterend.terrain;

/**
 * The biome {@code terrainHeight} the island field needs per quart column, which is the only thing
 * {@code TerrainGenerator.getAverageDepth} (TerrainGenerator.java:247-260) reads out of the biome
 * source.
 * <p>
 * One method, and it is a SEAM rather than an abstraction: {@code terrain} must not import
 * {@code biome} (biome already imports terrain), so the two are tied together in the root package,
 * in {@code DustWastelandsGenerator.bind}. It is also the seam that lets a test name the biome case
 * it is pinning instead of asserting against whatever the placement happens to paint.
 */
@FunctionalInterface
public interface TerrainHeights {
    /**
     * The biome's terrainHeight at this quart column.
     * <p>
     * A plain {@code float} with no absent case, because there is none: the field is
     * {@code Codec.FLOAT.optionalFieldOf("terrainHeight", 0.1f)}
     * (WoverBiomeDataImpl.java:25, WoverBiomeBuilder.java:243), so every biome has a value, and the
     * {@code biome == null ? 0} arm of TerrainGenerator.java:260 is dead in the port - every quart
     * resolves to a biome, and the vanilla core resolves to {@code minecraft:the_end}, which HAS a
     * WoverBiomeData (VanillaBiomeDataProvider.java:44-47).
     */
    float at(int quartX, int quartZ);

    /**
     * Every biome at the codec default: what the mod sees on 23 of BetterEnd's 27 biomes. Only
     * dust_wastelands (1.5) and megalake / megalake_grove / sulphur_springs (0.0) override it
     * (Reference/BetterEnd/src/main/generated/data/betterend/wover/worldgen/biome_data/).
     */
    TerrainHeights DEFAULT = (quartX, quartZ) -> 0.1f;
}
