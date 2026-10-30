package dev.mumu.betterend.trees;

import dev.mumu.betterend.terrain.OpenSimplexNoise;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * BetterEnd's tree silhouettes as pure geometry: a seed and a write box in, a map of relative
 * positions to {@link Part} out. Nothing here imports Bukkit or CraftEngine, so the shapes are
 * unit-testable without a server and {@link TreePopulator} is only the block-id and clipping layer.
 * <p>
 * The mod builds these out of bclib's {@code SplineHelper} / {@code org.betterx.bclib.sdf.*}, which
 * ARE readable now, under {@code Reference/BCLib}. Every primitive the five
 * ported species use is a capsule, a sphere or an axis scale, and every operator is min/max/
 * translate/scale or an additive displacement -- all closed form -- so this is loops, not an SDF
 * evaluator. Mossy glowshroom is the one species that needed a real distance -- four capped cones
 * on one axis, two of them subtracted -- so SDFCappedCone is transcribed verbatim for it. The two
 * helper semantics that used to be guessed at, {@link #fillTube} and {@link #offsetParts}, are
 * read from SplineHelper now rather than inferred.
 * <p>
 * Ported: lacugrove, dragon tree, lucernia, tenanea, pythadendron -- the five whose only missing
 * blocks are {@code betterend:<family>_bark} and {@code _log} (their leaves ship,
 * B2-leaves-saplings.yml:23-50; the wood-set factory that makes bark and log runs for four other
 * families only, B1-wood-families.yml:15-29) -- plus the four mushrooms, whose block families are
 * all present in the pack today. Skipped species and why: see {@link Species}.
 */
public final class TreeShape {
    /**
     * What a cell holds. {@code BARK} and {@code LOG} are both "wood" for the log promotion pass.
     * <p>
     * Everything past {@code LEAVES} belongs to the four mushrooms. Not one of them has a
     * {@code <family>_leaves} block and three have no {@code _bark}/{@code _log} either, so the id for
     * a part is looked up per species in {@link Species#ids} rather than derived from the family name.
     */
    public enum Part {
        BARK, LOG, LEAVES,
        /** The cap solid: MossyGlowshroomFeature.java:148, GiganticAmaranitaFeature.java:143. */
        CAP,
        /** MossyGlowshroomFeature.java:133-134 - {@code MossyGlowshroomCapBlock.TRANSITION = true}. */
        CAP_TRANSITION,
        /** The glowing underside: MossyGlowshroomFeature.java:88, GiganticAmaranitaFeature.java:100. */
        HYMENOPHORE,
        /** GiganticAmaranitaFeature.java:119 - {@code EndLightBlocks.AMARANITA_LANTERN}. */
        LANTERN,
        /** GiganticAmaranitaFeature.java:65 - amaranita's trunk, the DEFAULT of its post pass. */
        STEM,
        /** GiganticAmaranitaFeature.java:83 - the loose strands, and the stem's end caps (:384-389). */
        HYPHAE,
        /** UmbrellaTreeFeature.java:62-65 - canopy and hub; two COLOR values in the mod, one pack block. */
        MEMBRANE,
        /** UmbrellaTreeFeature.java:66-67 - the fruit. */
        CLUSTER,
        FUR_DOWN, FUR_NORTH, FUR_SOUTH, FUR_EAST, FUR_WEST;

        /**
         * Whether this part may replace a cell the same tree has already written.
         * <p>
         * Only the wood parts may. Every mushroom pass after the first is the mod's
         * {@code fillRecursive} / {@code fillSplineForce} under {@code REPLACE = replaceableOrPlant}
         * (GiganticAmaranitaFeature.java:380, JellyshroomFeature.java:136), which cannot overwrite a
         * solid block the same feature has just placed, and the amaranita head's own
         * {@code canBeReplaced()} gate (GiganticAmaranitaFeature.java:99 and every sibling) is that
         * same rule. Answering it from the result map is what keeps contract 1: the world is never
         * read back.
         */
        boolean overwrites() {
            return this == BARK || this == LOG || this == LEAVES;
        }
    }

    /** A cell, relative to the tree's origin (the block the trunk stands on is {@code y == -1}). */
    public record Pos(int x, int y, int z) {
    }

    /**
     * The species this port generates, with the placement count from
     * {@code worldgen/placed_feature/<name>.json} (every one is
     * {@code count_on_every_layer(uniform(0, maxPerLayer))} + {@code biome}, nothing else) and the
     * documented bounding box of {@link TreeShape#generate}'s output.
     * <p>
     * Not ported, deliberately: helix_tree (its leaves need an 8-value COLOR property no CraftEngine
     * block can express - HelixTreeFeature.java:179, :225, :233) and dragon_helix_tree (needs
     * {@code betterend:bulb_vine} with a SHAPE property). Both are still blocked on the pack.
     */
    public enum Species {
        /** placed_feature/lacugrove.json - uniform(0,4). LacugroveFeature.java:60 size 15..25. */
        LACUGROVE("lacugrove", 4, 22, -4, 40, wood("lacugrove")),
        /** placed_feature/dragon_tree.json - uniform(0,2). DragonTreeFeature.java:81 size 10..25. */
        DRAGON_TREE("dragon_tree", 2, 31, -16, 52, wood("dragon_tree")),
        /** placed_feature/lucernia.json - uniform(0,3). LucerniaFeature.java:66 size 12..20. */
        LUCERNIA("lucernia", 3, 24, -3, 31, wood("lucernia")),
        /** placed_feature/tenanea.json - uniform(0,3). TenaneaFeature.java:62 size 7..10. */
        TENANEA("tenanea", 3, 21, -2, 26, wood("tenanea")),
        /** placed_feature/pythadendron_tree.json - uniform(0,1). PythadendronTreeFeature.java:65 size 10..20. */
        PYTHADENDRON("pythadendron", 1, 31, -2, 32, wood("pythadendron")),

        /**
         * placed_feature/jellyshroom.json - uniform(0,2), biome umbrella_jungle.
         * JellyshroomFeature.java:50 height 5..8, :56-59 cap radius {@code height * 0.7..0.9}.
         */
        JELLYSHROOM("jellyshroom", 2, 12, -3, 14, Map.of(
                Part.BARK, "jellyshroom_bark",
                Part.LOG, "jellyshroom_log",
                // JellyshroomCapBlock.COLOR (8 values, :70-75) has no pack equivalent: the shade the
                // world shows is baked into betterend:jellyshroom_cap_purple (B1-wood-families.yml:529-553).
                Part.CAP, "jellyshroom_cap_purple")),
        /**
         * placed_feature/umbrella_tree.json - uniform(0,2), biome umbrella_jungle, shared with
         * jellyshroom. UmbrellaTreeFeature.java:69 size 10..20 and :85 scales Y by a factor
         * independent of XZ, which is what makes it the tallest thing in the mod.
         */
        UMBRELLA_TREE("umbrella_tree", 2, 40, -20, 76, Map.of(
                Part.BARK, "umbrella_tree_bark",
                Part.LOG, "umbrella_tree_log",
                // UmbrellaTreeMembraneBlock.COLOR (:144-157) is baked at _0 in the pack (B1:455-470),
                // so the mod's COLOR 0 hub and COLOR 1..7 canopy are one block here.
                Part.MEMBRANE, "umbrella_tree_membrane",
                // UmbrellaTreeClusterBlock.NATURAL (:67) is loot-only in the mod and absent from the
                // pack block (B1:474-513); dropping it changes nothing a player can see.
                Part.CLUSTER, "umbrella_tree_cluster")),
        /**
         * placed_feature/gigantic_amaranita.json - uniform(0,1), the rarest of the four, in
         * dragon_graveyards (which itself carries genChance 0.1). GiganticAmaranitaFeature.java:51
         * size 5..10. {@code family} is not a wood family: no {@code amaranita_bark} or
         * {@code amaranita_log} exists, and the stem/hyphae pair replaces them.
         */
        GIGANTIC_AMARANITA("amaranita", 1, 9, -2, 21, Map.of(
                Part.STEM, "amaranita_stem",
                Part.HYPHAE, "amaranita_hyphae",
                Part.CAP, "amaranita_cap",
                Part.HYMENOPHORE, "amaranita_hymenophore",
                Part.LANTERN, "amaranita_lantern",
                Part.FUR_DOWN, "amaranita_fur[facing=down]",
                Part.FUR_NORTH, "amaranita_fur[facing=north]",
                Part.FUR_SOUTH, "amaranita_fur[facing=south]",
                Part.FUR_EAST, "amaranita_fur[facing=east]",
                Part.FUR_WEST, "amaranita_fur[facing=west]")),
        /**
         * placed_feature/mossy_glowshroom.json - uniform(0,2), biome foggy_mushroomland.
         * MossyGlowshroomFeature.java:91 height 10..25, :99 scale 0.75..1.1.
         */
        MOSSY_GLOWSHROOM("mossy_glowshroom", 2, 24, -8, 52, Map.of(
                Part.BARK, "mossy_glowshroom_bark",
                Part.LOG, "mossy_glowshroom_log",
                Part.CAP, "mossy_glowshroom_cap",
                Part.CAP_TRANSITION, "mossy_glowshroom_cap[transition=true]",
                Part.HYMENOPHORE, "mossy_glowshroom_hymenophore",
                // The pack's fur is a hanging_block with above_blocks [mossy_glowshroom_hymenophore]
                // and no facing at all (B1:386-407), so only the DOWN fur of :160-165 has a legal
                // state here; the four horizontal ones of :151-158 are dropped in glowshroomSkin().
                Part.FUR_DOWN, "mossy_glowshroom_fur"));

        /** A label for logs, and the {@code <family>_*} prefix the five wood species' ids share. */
        public final String family;
        public final int maxPerLayer;
        /** Largest {@code |x|} or {@code |z|} any emitted cell can reach with an unbounded write box. */
        public final int reachXZ;
        public final int minY;
        public final int maxY;
        /** Every part this species can emit, mapped to its CraftEngine id minus the namespace. */
        public final Map<Part, String> ids;

        Species(String family, int maxPerLayer, int reachXZ, int minY, int maxY, Map<Part, String> ids) {
            this.family = family;
            this.maxPerLayer = maxPerLayer;
            this.reachXZ = reachXZ;
            this.minY = minY;
            this.maxY = maxY;
            this.ids = ids;
        }
    }

    /** The five wood species all resolve {@code betterend:<family>_bark/_log/_leaves} and nothing else. */
    private static Map<Part, String> wood(String family) {
        return Map.of(Part.BARK, family + "_bark",
                Part.LOG, family + "_log",
                Part.LEAVES, family + "_leaves");
    }

    // --- entry point ------------------------------------------------------------------------

    /**
     * One tree, deterministic in {@code seed} alone.
     *
     * @param minX       write box low X, INCLUSIVE and relative to the origin. Canopies are shrunk to
     *                   fit the box ({@link #fitBallRadius}); anything still outside is dropped, which
     *                   is what the mod's {@code WriteZone.toBoundingBox()} does to its splines.
     * @param maxX       write box high X, inclusive, relative to the origin.
     * @param minZ       write box low Z, inclusive, relative to the origin.
     * @param maxZ       write box high Z, inclusive, relative to the origin.
     * @param onEndStone "is that block plain end stone" - the root/pillar gate. The mod reads the
     *                   world for it (DragonTreeFeature.java:145, LacugroveFeature.java:119); a Bukkit
     *                   caller must answer from {@code IslandField} instead, never from a block read.
     */
    public static Map<Pos, Part> generate(Species species, long seed,
                                          int minX, int maxX, int minZ, int maxZ,
                                          Predicate<Pos> onEndStone) {
        TreeShape shape = new TreeShape(seed, minX, maxX, minZ, maxZ, onEndStone);
        switch (species) {
            case LACUGROVE -> shape.lacugrove();
            case DRAGON_TREE -> shape.dragonTree();
            // LucerniaFeature and TenaneaFeature are the same code with different constants: the
            // byte-identical SPLINE (LucerniaFeature.java:273-280 == TenaneaFeature.java:237-244),
            // the same count/var/start angular jitter, the same ball operators and the same bulge.
            case LUCERNIA -> shape.fanTree(12, 20, 0.3F, 0.5F, 1.0F, 0.13F, true);
            case TENANEA -> shape.fanTree(7, 10, 0.45F, 1.0F, 1.5F, 0.3F, false);
            case PYTHADENDRON -> shape.pythadendron();
            case JELLYSHROOM -> shape.jellyshroom();
            case UMBRELLA_TREE -> shape.umbrellaTree();
            case GIGANTIC_AMARANITA -> shape.giganticAmaranita();
            case MOSSY_GLOWSHROOM -> shape.mossyGlowshroom();
        }
        // The log rule is not universal: amaranita inverts it (stem is the default, hyphae the end
        // cap) and mossy glowshroom extends it with a cap-transition branch and a fur pass.
        switch (species) {
            case GIGANTIC_AMARANITA -> shape.capStemsWithHyphae();
            case MOSSY_GLOWSHROOM -> shape.glowshroomSkin();
            default -> shape.promoteLogs();
        }
        return shape.blocks;
    }

    private final Map<Pos, Part> blocks = new LinkedHashMap<>();
    private final Random random;
    /** Kept as well as consumed: mossy glowshroom's cap warp needs an offset it must not draw for. */
    private final long seed;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final Predicate<Pos> onEndStone;

    private TreeShape(long seed, int minX, int maxX, int minZ, int maxZ, Predicate<Pos> onEndStone) {
        this.random = new Random(seed);
        this.seed = seed;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.onEndStone = onEndStone;
    }

    // --- species ----------------------------------------------------------------------------

    /** LacugroveFeature.java:60-142. The tall spire: one high scooped ball and a woven root mound. */
    private void lacugrove() {
        float size = randRange(15, 25);
        List<float[]> spline = makeSpline(0, 0, 0, 0, size, 0, 6);
        offsetParts(spline, 1F, 0F, 1F);
        OpenSimplexNoise noise = new OpenSimplexNoise(random.nextLong());

        // :70-71 - randRange(6,8) * ((size-15)/20 + 1), i.e. 6..12.
        float radius = randRange(6F, 8F) * ((size - 15F) / 20F + 1F);
        float[] center = spline.get(4); // :72 - 80% up the trunk.
        // :162-172 - sphere, noise*3, jitter, then subtract a copy translated down radius+2: a
        // scooped underside rather than a flat cut. LEAF_BALL_BULGE = 4.5F (:46), minRadius 2 (:160).
        leafBall((int) center[0], (int) center[1], (int) center[2],
                radius, 1F, 3F, 4.5F, 2F, true, 2F, noise);

        // :82-92 - trunk capsule chain, base radius 1.2..1.8 tapering to 0.7: the sharpest taper in
        // the mod, which is what makes it a spire.
        fillTube(spline, randRange(1.2F, 1.8F), 0.7F);
        // :94-102 - the top segment redrawn solid so the leader pokes out of the canopy.
        fillSpline(spline.subList(4, 6));

        rootPillars();
    }

    /**
     * LacugroveFeature.java:104-142 - the only terrain-modifying code in the mod's tree set: a
     * checkerboard mound of wood columns that also sinks into the end stone below (:129 accepts
     * END_STONES as replaceable; every other species has that clause commented out and it stays that
     * way here).
     */
    private void rootPillars() {
        int offset = random.nextInt(2);
        for (int i = 0; i < 100; i++) {
            // :107-110 - px/pz are integers, so floor(px + 0.5) is px.
            int px = randRange(-5, 5);
            int pz = randRange(-5, 5);
            // :111. The parity is taken in local coordinates; a world-space origin only shifts it by
            // a constant, which the uniform `offset` draw already covers.
            if (((px + pz + offset) & 1) != 0) continue;
            double distance = 3.5 - Math.sqrt((double) px * px + (double) pz * pz) * 0.5;
            if (distance <= 0) continue;
            int lowY = (int) Math.floor(-distance * 0.5);                 // :114
            int highY = (int) Math.floor(distance + random.nextDouble()); // :115
            boolean generate = false;
            for (int y = lowY; y < highY; y++) {
                if (onEndStone.test(new Pos(px, y, pz))) {
                    generate = true;
                    break;
                }
            }
            if (!generate) continue;
            int top = highY - 1;
            for (int y = top; y >= lowY; y--) {
                set(px, y, pz, y == top ? Part.BARK : Part.LOG); // :133
            }
        }
    }

    /** DragonTreeFeature.java:69-115. Jittered trunk, a radial fan of forked branches, a lens, roots. */
    private void dragonTree() {
        float size = randRange(10, 25);
        List<float[]> spline = makeSpline(0, 0, 0, 0, size, 0, 6);
        offsetParts(spline, 1F, 0F, 1F);
        float[] cap = getPos(spline, 3.5F); // :90 - parameter 3.5 of 5 segments, i.e. 70% up.
        OpenSimplexNoise noise = new OpenSimplexNoise(random.nextLong());
        float radius = size * randRange(0.5F, 0.7F); // :92 - 5.0 .. 17.5, drives the whole cap.
        makeCap((int) cap[0], (int) cap[1], (int) cap[2], radius, noise);
        makeRoots(0, 0, 0, radius, DRAGON_ROOT, 1.5F);
        // :98-104 - trunk base radius 1.2..2.3 tapering to 1.2.
        fillTube(spline, randRange(1.2F, 2.3F), 1.2F);
    }

    /** DragonTreeFeature.java:117-183. */
    private void makeCap(int cx, int cy, int cz, float radius, OpenSimplexNoise noise) {
        int count = (int) radius; // :125
        float nominal = radius * 1.15F + 2F; // :140
        float ballRadius = fitBallRadius(cx, cz, nominal, 1.5F, 4F); // LEAF_BALL_BULGE = 1.5F (:66)
        // :141-154 - shrink the fan by whatever the ball lost, and scale the ball's height offset by
        // the same factor, or the canopy hangs in the air above the shortened branches.
        float capScale = ballRadius / nominal;
        int offset = (int) (radius * capScale); // BRANCH's last point has y == 1.

        for (int i = 0; i < count; i++) {
            float angle = (float) i / (float) count * PI2;
            float scale = radius * randRange(0.85F, 1.15F) * capScale;
            for (List<float[]> part : List.of(BRANCH, SIDE1, SIDE2)) {
                List<float[]> branch = copy(part);
                rotateY(branch, angle);
                scale(branch, scale, scale, scale);
                offset(branch, cx, cy, cz);
                fillSpline(branch);
            }
        }
        // :218-244 - subtract a 5x copy translated down 5r (a flat bottom), squash to half height,
        // noise*1.5, per-cell jitter.
        leafBall(cx, cy + offset, cz, ballRadius, 0.5F, 1.5F, 1.5F, 4F, false, 0F, noise);
    }

    /**
     * The fanned root spline shared verbatim by DragonTreeFeature.java:185-216 and
     * LucerniaFeature.java:230-258 - drawn only where the tip lands on rock.
     */
    private void makeRoots(int cx, int cy, int cz, float radius, List<float[]> root, float countFactor) {
        int count = (int) (radius * countFactor);
        for (int i = 0; i < count; i++) {
            float angle = (float) i / (float) count * PI2;
            float scale = radius * randRange(0.85F, 1.15F);
            List<float[]> branch = copy(root);
            rotateY(branch, angle);
            scale(branch, scale, scale, scale);
            offset(branch, cx, cy, cz);
            float[] tip = branch.get(branch.size() - 1);
            if (onEndStone.test(new Pos((int) tip[0], (int) tip[1], (int) tip[2]))) {
                fillSpline(branch);
            }
        }
    }

    /**
     * LucerniaFeature.java:66-99 and TenaneaFeature.java:62-92 - no trunk, a radial fan of
     * goblet-curved branches straight from the ground with a flat-bottomed leaf ball on each tip.
     *
     * @param scaleMin branch length as a multiple of {@code size}: lucernia's
     *                 {@code size * randRange(0.5,1)} and tenanea's {@code size + randRange(0,
     *                 size*0.5)} are the same expression with different bounds.
     */
    private void fanTree(int sizeMin, int sizeMax, float countFactor,
                         float scaleMin, float scaleMax, float ballFactor, boolean roots) {
        float size = randRange(sizeMin, sizeMax);
        int count = (int) (size * countFactor);
        float var = PI2 / (float) (count * 3);
        float start = randRange(0F, PI2);
        for (int i = 0; i < count; i++) {
            float angle = (float) i / (float) count * PI2 + randRange(0F, var) + start;
            List<float[]> spline = copy(SPLINE);
            rotateY(spline, angle);
            float scale = size * randRange(scaleMin, scaleMax);
            scale(spline, scale, scale, scale);
            offsetParts(spline, 1F, 0F, 1F);
            fillSpline(spline);

            float[] tip = spline.get(spline.size() - 1);
            float leavesRadius = (size * ballFactor + randRange(0.8F, 1.5F)) * 1.4F;
            OpenSimplexNoise noise = new OpenSimplexNoise(random.nextLong());
            int bx = (int) tip[0];
            int by = (int) tip[1];
            int bz = (int) tip[2];
            // A 4-block bark ring one above the branch tip, written BEFORE the ball and so mostly
            // buried by it. LucerniaFeature.java:138-146 and TenaneaFeature.java:131-139 are the same
            // block, in both species - and in both the inner d2 loop writes to `p` rather than to
            // `mut`, making it a no-op. The intended ring is reproduced; the bug is not.
            for (int[] d : HORIZONTAL) set(bx + d[0], by + 1, bz + d[1], Part.BARK);
            // LEAF_BALL_BULGE = 3.5F for both (LucerniaFeature.java:50, TenaneaFeature.java:47).
            leafBall(bx, by, bz, leavesRadius, 0.75F, 2F, 3.5F, 2F, false, 0F, noise);
        }
        if (roots) {
            makeRoots(0, randRange(3, 5), 0, size * 0.35F, LUCERNIA_ROOT, 1.5F); // :99, :234
        }
    }

    /** PythadendronTreeFeature.java:65-83 - a recursive binary fork that turns 90 degrees per level. */
    private void pythadendron() {
        float size = randRange(10, 20);
        List<float[]> spline = makeSpline(0, 0, 0, 0, size, 0, 4);
        offsetParts(spline, 0.7F, 0F, 0.7F);
        float[] top = spline.get(spline.size() - 1);
        int depth = (int) Math.floor((size - 10F) * 3F / 10F + 1F); // :70 - 1..4
        float bsize = (10F - (size - 10F)) / 10F + 1.5F;            // :71 - 2.5 down to 1.5
        branch(top[0], top[1], top[2], size * bsize, randRange(0F, PI2), depth);
        fillTube(spline, 1.7F, 1.1F); // :85-90
    }

    /** PythadendronTreeFeature.java:99-172. */
    private void branch(float x, float y, float z, float size, float angle, int depth) {
        if (depth == 0) return;
        float dx = (float) Math.cos(angle) * size * 0.15F;
        float dz = (float) Math.sin(angle) * size * 0.15F;

        // ponytail: SplineHelper.powerOffset(spline, size * randRange(1,2), 4) (:124, :138) is
        // SKIPPED, so this fork is drawn flat and the crown is a plate where the mod's sweeps upward.
        // bclib is not in this checkout, and the only readable calibration for the helper is
        // BigEtherTreeFeature.java:58 - powerOffset(branch, length, 2) on a branch whose own
        // horizontal length is `length`, which reads as "lift point i by distance * (i/(n-1))^power".
        // That reading is measurably wrong here: implemented literally it takes pythadendron to
        // y=204 over 60 seeds, because `distance` is size * 1..2 (25..60) against a limb only
        // size * 0.15 (~4 blocks) long, compounded over up to four levels. No formula that lifts the
        // tip by any fixed fraction of `distance` gives a tree-sized tree, so the helper does
        // something else and a flat fork is the lesser of the two known-wrong options. The
        // 90-degree alternation below is what actually reads as pythadendron. Restore the arc when a
        // real bclib jar can be read - do not guess a scale factor.
        List<float[]> a = makeSpline(x, y, z, x + dx, y, z + dz, 5);
        offsetParts(a, 0.3F, 0F, 0.3F);
        boolean s1 = fillSpline(a);
        List<float[]> b = makeSpline(x, y, z, x - dx, y, z - dz, 5);
        offsetParts(b, 0.3F, 0F, 0.3F);
        boolean s2 = fillSpline(b);

        float[] tip1 = a.get(a.size() - 1);
        float[] tip2 = b.get(b.size() - 1);
        OpenSimplexNoise noise = new OpenSimplexNoise(random.nextInt());
        if (depth < 3) { // :152 - leaf balls only on the outer two levels
            if (s1) pythadendronBall((int) tip1[0], (int) tip1[1], (int) tip1[2], noise);
            if (s2) pythadendronBall((int) tip2[0], (int) tip2[1], (int) tip2[2], noise);
        }

        float size1 = size * randRange(0.75F, 0.95F);
        float size2 = size * randRange(0.75F, 0.95F);
        // :163-164 - every level turns a quarter circle, so successive forks alternate axes.
        float angle1 = angle + (float) Math.PI * 0.5F + randRange(-0.1F, 0.1F);
        float angle2 = angle + (float) Math.PI * 0.5F + randRange(-0.1F, 0.1F);
        if (s1) branch(tip1[0], tip1[1], tip1[2], size1, angle1, depth - 1);
        if (s2) branch(tip2[0], tip2[1], tip2[2], size2, angle2, depth - 1);
    }

    /** PythadendronTreeFeature.java:180-199 - LEAF_BALL_BULGE = 4.5F (:47), minRadius 2.5 (:185). */
    private void pythadendronBall(int x, int y, int z, OpenSimplexNoise noise) {
        float radius = randRange(4.5F, 6.5F);
        leafBall(x, y, z, radius, 0.6F, 3F, 4.5F, 2.5F, true, 0F, noise);
    }

    // --- primitives -------------------------------------------------------------------------

    /**
     * A leaf blob: an ellipsoid squashed to {@code yScale} in Y, its surface pushed out by
     * {@code noise(p * 0.2) * noiseAmp} and by a per-cell {@code uniform(-1.5, 1.5)}, with either a
     * flat bottom or a scooped one. Every one of the five species' balls is this function with
     * different constants.
     *
     * @param scoop      false cuts the bottom flat at the centre plane - the mod does that by
     *                   subtracting a 5x copy translated down {@code 5r}, whose top surface is within
     *                   {@code r/10} of {@code y == 0} (DragonTreeFeature.java:235-237). True
     *                   subtracts the shape from itself translated down by {@code radius +
     *                   scoopExtra}, which scoops the underside (LacugroveFeature.java:171-172 uses
     *                   {@code radius + 2}, PythadendronTreeFeature.java:198-199 uses {@code radius}).
     * @param scoopExtra added to the FITTED radius, which is the one the mod's subtraction reads: by
     *                   line 171 / 198 the local {@code radius} has already been through
     *                   {@code fitBallRadius}.
     */
    private void leafBall(int cx, int cy, int cz, float radius, float yScale, float noiseAmp,
                          float bulge, float minRadius, boolean scoop, float scoopExtra,
                          OpenSimplexNoise noise) {
        radius = fitBallRadius(cx, cz, radius, bulge, minRadius);
        float scoopDown = radius + scoopExtra;
        int reach = (int) Math.ceil(radius + noiseAmp + 1.5F) + 1;
        int reachY = (int) Math.ceil(radius * yScale + noiseAmp + 1.5F) + 1;
        int lowY = scoop ? -reachY : 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = lowY; dy <= reachY; dy++) {
                    // A displacement ADDS to the distance, so a positive one shrinks the shape: the
                    // effective radius is radius - displacement.
                    float jitter = random.nextFloat() * 3F - 1.5F;
                    if (!inBlob(dx, dy, dz, radius, yScale, noiseAmp, jitter, noise)) continue;
                    // The subtracted copy is evaluated without a fresh per-cell jitter draw; drawing
                    // one would make the cut depend on a second, unrelated random.
                    if (scoop && inBlob(dx, dy + scoopDown, dz, radius, yScale, noiseAmp, 0F, noise)) {
                        continue;
                    }
                    set(cx + dx, cy + dy, cz + dz, Part.LEAVES);
                }
            }
        }
        set(cx, cy, cz, Part.BARK); // every leavesBall ends by forcing bark at the centre
    }

    private static boolean inBlob(float dx, float dy, float dz, float radius, float yScale,
                                  float noiseAmp, float jitter, OpenSimplexNoise noise) {
        double displacement = noise.eval(dx * 0.2, dy * 0.2, dz * 0.2) * noiseAmp + jitter;
        double qy = dy / yScale;
        return Math.sqrt(dx * dx + qy * qy + dz * dz) <= radius - displacement;
    }

    /**
     * EndTreeHelper.java:72-82 - shrink a ball to the room its centre actually has instead of letting
     * the write bounds take a flat chord out of it. Paper's {@code CraftLimitedRegion} buffer is 16
     * (CraftLimitedRegion.java:49), which is exactly the budget the mod's {@code WriteZone} was
     * written against, so this ports over unchanged and is needed, not optional.
     */
    private float fitBallRadius(int cx, int cz, float radius, float bulge, float minRadius) {
        int headroom = Math.min(Math.min(cx - minX, maxX - cx), Math.min(cz - minZ, maxZ - cz));
        return Math.max(Math.min(radius, headroom - bulge), minRadius);
    }

    /**
     * A chain of capsules along the polyline, the radius interpolating linearly from {@code rBase} at
     * the first point to {@code rTip} at the last.
     * <p>
     * SplineHelper.buildSDF (BCLib SplineHelper.java:132-152) confirms base-to-tip: it lerps
     * {@code (i - 1) / (count - 2)} from {@code radius1} to {@code radius2}. It gives each segment ONE
     * constant radius where this tapers within the segment, which is smoother and differs by well
     * under a block. (It also divides by zero on a two-point spline -- see {@link #mossyGlowshroom}.)
     */
    private void fillTube(List<float[]> spline, float rBase, float rTip) {
        fillTube(spline, rBase, rTip, Part.BARK);
    }

    private void fillTube(List<float[]> spline, float rBase, float rTip, Part part) {
        int n = spline.size();
        for (int i = 0; i < n - 1; i++) {
            float ra = rBase + (rTip - rBase) * i / (n - 1);
            float rb = rBase + (rTip - rBase) * (i + 1) / (n - 1);
            capsule(spline.get(i), spline.get(i + 1), ra, rb, part);
        }
    }

    private void capsule(float[] a, float[] b, float ra, float rb, Part part) {
        float rMax = Math.max(ra, rb);
        int x0 = (int) Math.floor(Math.min(a[0], b[0]) - rMax);
        int x1 = (int) Math.ceil(Math.max(a[0], b[0]) + rMax);
        int y0 = (int) Math.floor(Math.min(a[1], b[1]) - rMax);
        int y1 = (int) Math.ceil(Math.max(a[1], b[1]) + rMax);
        int z0 = (int) Math.floor(Math.min(a[2], b[2]) - rMax);
        int z1 = (int) Math.ceil(Math.max(a[2], b[2]) + rMax);
        float ex = b[0] - a[0];
        float ey = b[1] - a[1];
        float ez = b[2] - a[2];
        float len2 = ex * ex + ey * ey + ez * ez;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    float px = x - a[0];
                    float py = y - a[1];
                    float pz = z - a[2];
                    float t = len2 <= 0 ? 0 : clamp((px * ex + py * ey + pz * ez) / len2);
                    float dx = px - ex * t;
                    float dy = py - ey * t;
                    float dz = pz - ez * t;
                    if (dx * dx + dy * dy + dz * dz <= sq(ra + (rb - ra) * t)) {
                        set(x, y, z, part);
                    }
                }
            }
        }
    }

    /** @return whether any cell was written - the mod's {@code fillSpline} boolean, which gates recursion. */
    private boolean fillSpline(List<float[]> spline) {
        return fillSpline(spline, Part.BARK);
    }

    private boolean fillSpline(List<float[]> spline, Part part) {
        boolean any = false;
        for (int i = 0; i < spline.size() - 1; i++) {
            any |= fillLine(spline.get(i), spline.get(i + 1), part);
        }
        return any;
    }

    /**
     * A one-block-wide line. Sampled at quarter-block steps and bridged one axis at a time, so the
     * result is 6-connected: a branch is never a string of blocks that only touch at their corners.
     */
    private boolean fillLine(float[] a, float[] b, Part part) {
        double len = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
        int steps = Math.max(1, (int) Math.ceil(len * 4));
        int x = Math.round(a[0]);
        int y = Math.round(a[1]);
        int z = Math.round(a[2]);
        boolean any = set(x, y, z, part);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            int tx = Math.round(a[0] + (b[0] - a[0]) * t);
            int ty = Math.round(a[1] + (b[1] - a[1]) * t);
            int tz = Math.round(a[2] + (b[2] - a[2]) * t);
            while (x != tx) {
                x += Integer.signum(tx - x);
                any |= set(x, y, z, part);
            }
            while (y != ty) {
                y += Integer.signum(ty - y);
                any |= set(x, y, z, part);
            }
            while (z != tz) {
                z += Integer.signum(tz - z);
                any |= set(x, y, z, part);
            }
        }
        return any;
    }

    /**
     * The mod's {@code POST} lambda, e.g. DragonTreeFeature.java:322-327: everything is drawn in bark
     * and only cells with wood both above and below become log. CraftEngine's {@code <family>_log} is
     * {@code default:block_state/pillar}, whose {@code axis} defaults to {@code y}, so its default
     * state is already the vertical log and there is no axis to set.
     */
    private void promoteLogs() {
        for (Map.Entry<Pos, Part> entry : blocks.entrySet()) {
            if (entry.getValue() != Part.BARK) continue;
            Pos p = entry.getKey();
            if (isWood(p.x(), p.y() + 1, p.z()) && isWood(p.x(), p.y() - 1, p.z())) {
                entry.setValue(Part.LOG);
            }
        }
    }

    private boolean isWood(int x, int y, int z) {
        Part part = blocks.get(new Pos(x, y, z));
        return part == Part.BARK || part == Part.LOG;
    }

    /**
     * Clips to the write box, which is what {@code WriteZone.toBoundingBox()} does for the mod.
     * <p>
     * Leaves never overwrite wood: that is the mod's {@code IGNORE} predicate, the fourth argument of
     * every canopy's {@code fillRecursiveIgnore} and always {@code <family>::isTreeLog}
     * (DragonTreeFeature.java:320, LucerniaFeature.java:271, and the same line in Lacugrove, Tenanea
     * and Pythadendron). Without it a leaf ball erases the branch it hangs on - the mod keeps a wooden
     * skeleton running through every canopy. Wood over leaves stays allowed, which is what each
     * feature's {@code REPLACE} accepts explicitly (DragonTreeFeature.java:314-316).
     */
    /**
     * The one write in the amaranita head that is NOT gated on {@code canBeReplaced}:
     * GiganticAmaranitaFeature.java:186 and :312 call {@code setWithoutUpdate} directly, so the second
     * lantern overwrites. A run of adjacent underside cells depends on it -- the side fur of cell
     * {@code (x, z)} lands exactly where the lantern of {@code (x, z + 1)} goes, and with a
     * putIfAbsent there the result is a stack of fur hanging off fur.
     */
    private void put(int x, int y, int z, Part part) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return;
        blocks.put(new Pos(x, y, z), part);
    }

    private boolean set(int x, int y, int z, Part part) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        if (part == Part.LEAVES && isWood(x, y, z)) return false;
        Pos pos = new Pos(x, y, z);
        // Losing to whatever is already there IS the mushrooms' rule, and the return value is the
        // amaranita head's canBeReplaced() gate (GiganticAmaranitaFeature.java:182): a cell it does
        // not win skips the whole lantern column under it. See Part.overwrites().
        if (!part.overwrites()) return blocks.putIfAbsent(pos, part) == null;
        blocks.put(pos, part);
        return true;
    }

    // --- mushrooms --------------------------------------------------------------------------

    /**
     * JellyshroomFeature.java:37-84. A short jittered stem with a scalloped open-bottomed cap on top and
     * a fan of roots at the base; the smallest of the four and the one that needed no new machinery.
     */
    private void jellyshroom() {
        int height = randRange(5, 8);                                      // :50 - int overload, 5..8
        float radius = height * randRange(0.15F, 0.25F);                   // :51
        List<float[]> spline = makeSpline(0, -1, 0, 0, height, 0, 3);      // :52 - starts BELOW the origin
        offsetParts(spline, 0.5F, 0F, 0.5F);                               // :53
        fillTube(spline, radius, 0.8F, Part.BARK);                         // :54

        float cap = Math.max(height * randRange(0.7F, 0.9F), 1.5F);        // :56-59
        float angle = random.nextFloat() * PI2;                            // :117
        int rays = Math.max(3, (int) randRange(cap * 0.5F, cap));          // :118-121
        float[] tip = spline.get(spline.size() - 1);                       // :62-63
        dome(tip[0], tip[1], tip[2], cap, rays, angle, 0.2F, 1F, Part.CAP);

        makeRoots(0, 2, 0, height * 0.5F, LUCERNIA_ROOT, 3.5F);            // :79-80, :94
    }

    /**
     * UmbrellaTreeFeature.java:49-189. No trunk at all: one to three curved branches straight out of the
     * ground, each carrying the same dome jellyshroom uses, with fruit hung under the canopy afterwards.
     */
    private void umbrellaTree() {
        int size = randRange(10, 20);                                      // :69 - int overload
        int count = (int) (size * 0.15F);                                  // :70 - 1, 2 or 3
        float var = PI2 / (count * 3F);                                    // :71
        float start = randRange(0F, PI2);                                  // :72
        // :77-79 - `config` is NoneFeatureConfiguration.INSTANCE and never null, so this always rolls.
        float scale = randRange(1F, 1.7F);
        float rScale = (scale - 1F) * 0.4F + 1F;                           // :92
        // px, py, pz - already multiplied by `scale`, i.e. in cell space.
        List<float[]> centers = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            float angle = i / (float) count * PI2 + randRange(0F, var) + start;  // :82
            List<float[]> spline = copy(SPLINE);                                 // :83
            float sizeXZ = (size + randRange(0F, size * 0.5F)) * 0.7F;           // :84
            // :85 - the Y factor is drawn independently and reaches 2, which is why this is the
            // tallest species in the mod: a branch is up to twice as tall as it is long.
            scale(spline, sizeXZ, sizeXZ * randRange(1F, 2F), sizeXZ);
            rotateY(spline, angle);                                              // :87
            offsetParts(spline, 0.5F, 0F, 0.5F);                                 // :88
            float[] tip = spline.get(spline.size() - 1);
            // :98-100 - the membrane centre is fixed to a cell centre in the UNSCALED frame.
            float px = (float) Math.floor(tip[0]) + 0.5F;
            float py = (float) Math.floor(tip[1]) + 0.5F;
            float pz = (float) Math.floor(tip[2]) + 0.5F;
            float radius = (size + randRange(0F, size * 0.5F)) * 0.4F;           // :96
            float memAngle = random.nextFloat() * PI2;                           // :262
            int rays = Math.max(5, (int) randRange(radius, radius * 2F));        // :263-266

            // :137-139 - the mod wraps the whole graph in SDFScale(scale) at the very end. Scaling
            // every control point and every radius by the same factor here is the same shape one pass
            // earlier, and keeps the cell loops working in cell space.
            scale(spline, scale, scale, scale);
            fillTube(spline, 1.2F * rScale * scale, 0.8F * rScale * scale, Part.BARK);  // :93

            float cx = px * scale;
            float cz = pz * scale;
            // :109-117 - the mod fits the membrane to the room its centre has rather than letting the
            // write bounds cut a chord out of it. MEMBRANE_BULGE = 1.1F (:46) is the flat wave plus the
            // smooth union's fillet. Above the floor the two agree exactly (zone.fitRadius returns
            // min(r + bulge, room) and the mod then subtracts the bulge back off). They part below it:
            // when even the smallest membrane this size can roll has no room, fitRadius returns -1 and
            // the mod draws the FULL one and lets fillRecursive slice it, where this floors at the 2.5
            // hub. That is the cheaper of the two in a corner and the hub is drawn regardless; the mod's
            // own fitBranch (:204-220), which pulls the branch in so the case rarely arises, is not
            // ported.
            float fitted = fitBallRadius(Math.round(cx), Math.round(cz), radius * scale, 1.1F * scale, 2.5F);
            dome(cx, py * scale, cz, fitted, rays, memAngle, 0.6F * scale, scale, Part.MEMBRANE);
            ball(cx, py * scale, cz, 2.5F * scale, Part.MEMBRANE);               // :269-270 - the hub
            centers.add(new float[]{cx, py * scale, cz});
        }

        makeRoots(0, 0, 0, (size * 0.5F + 3F) * scale, LUCERNIA_ROOT, 1.5F);     // :165, :230
        // :167-185 - after the roots, so the random stream matches.
        for (float[] c : centers) {
            // :174 - "is the centre solid". It always is: the 2.5 hub covers it. Asking the result map
            // is the contract-1 substitute for the mod's world read.
            Pos centre = new Pos((int) Math.floor(c[0]), (int) Math.floor(c[1]), (int) Math.floor(c[2]));
            if (!blocks.containsKey(centre)) continue;
            int fruits = (int) Math.floor(randRange(5F, 10F) * scale);           // :175
            float startAngle = random.nextFloat() * PI2;                         // :176
            for (int i = 0; i < fruits; i++) {
                float a = i / (float) fruits * PI2 + startAngle;                 // :178
                float dist = randRange(1.5F, 2.5F) * scale;                      // :179
                makeFruit(c[0] + (float) Math.sin(a) * dist, c[1] - 1F,          // :180-182
                        c[2] + (float) Math.cos(a) * dist);
            }
        }
    }

    /**
     * UmbrellaTreeFeature.java:275-287 - step down at most eight cells and hang one cluster in the first
     * empty one, if what is directly above it is membrane.
     * <p>
     * The mod also requires {@code COLOR < 2} on that membrane, which restricted fruit to the hub and
     * the innermost ring. The pack's {@code umbrella_tree_membrane} has no colour (B1:455-470), so that
     * test degenerates to "is membrane" and this tree carries more fruit than the mod's.
     */
    private void makeFruit(float px, float py, float pz) {
        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py);
        int z = (int) Math.floor(pz);
        for (int i = 0; i < 8; i++) {
            y--;
            if (blocks.containsKey(new Pos(x, y, z))) continue;
            if (blocks.get(new Pos(x, y + 1, z)) == Part.MEMBRANE) set(x, y, z, Part.CLUSTER);
            return;
        }
    }

    /**
     * The scalloped mushroom BOWL, shared byte for byte by JellyshroomFeature.makeCap (:110-125) and
     * UmbrellaTreeFeature.makeMembrane (:250-273) - only the wave's intensity and ray count differ.
     * <p>
     * The mod builds it as {@code Sphere(r) - translate(0,-4,0)Sphere(r)}, then {@code Scale3D(1,0.5,1)},
     * then {@code translate(0, 1 - r/2, 0)}, then a {@code FlatWave}. It is NOT a filled dome, and the
     * difference is the whole shape: subtracting an equal sphere whose centre is only 4 below leaves
     * the lens BETWEEN the two surfaces, so at height {@code Y} the solid runs from
     * {@code r^2 - (Y+4)^2} out to {@code r^2 - Y^2} in {@code dx^2 + dz^2}. That is a roof 4 units
     * thick on the axis (2 after the squash) thinning to nothing at the rim, over an open underside -
     * a mushroom cap. Filling it in would roughly treble the write budget and lose the silhouette.
     * The lens is non-empty only for {@code Y > -2}, which is the "cut plane" the two spheres share.
     * <p>
     * Written out, with {@code Y = 2(dy - unit + r/2)} undoing the translate and the squash and
     * {@code w} the wave, a cell is inside iff {@code len(dx,Y,dz) < r - w} and
     * {@code len(dx,Y+4unit,dz) > r + w} - the second inequality being what the subtraction contributes
     * once the wave has been added to both halves of {@code max(a, -b)}. Checked against the mod's own
     * node graph (BCLib SDFSphere/SDFSubtraction/SDFScale3D/SDFTranslate/SDFFlatWave, and SDFScale for
     * {@code unit}): identical membership on every sampled cell.
     *
     * @param unit what one unit of the mod's local frame is worth in cells. 1 for jellyshroom; for the
     *             umbrella tree it is the {@code SDFScale} the whole graph is wrapped in (:137-139), so
     *             the two bare constants inside the construction, 1 and 4, scale with everything else.
     */
    private void dome(float cx, float cy, float cz, float radius, int rays, float angle,
                      float intensity, float unit, Part part) {
        int reach = (int) Math.ceil(radius + intensity) + 1;
        // The rim bottoms out at cy + unit - radius/2 - unit, and a wave crest (w == -intensity) drops
        // it a further radius*intensity/(2*unit): solve the subtraction inequality at the surface and
        // the cut plane moves by exactly that. Scanning short of it shaves the rim off the deepest
        // scallops, so this window is deliberately a couple of cells wider than the shape.
        int lowY = (int) Math.floor(cy - radius * 0.5F - radius * intensity * 0.5F / unit) - 2;
        int highY = (int) Math.ceil(cy + unit + intensity) + 1;
        int x0 = (int) Math.floor(cx) - reach;
        int z0 = (int) Math.floor(cz) - reach;
        for (int x = x0; x <= x0 + 2 * reach; x++) {
            for (int z = z0; z <= z0 + 2 * reach; z++) {
                float dx = x - cx;
                float dz = z - cz;
                // SDFFlatWave.java:9-11 - note the argument order, atan2(x, z).
                float w = (float) Math.cos(Math.atan2(dx, dz) * rays + angle) * intensity;
                float flat = dx * dx + dz * dz;
                for (int y = lowY; y <= highY; y++) {
                    float qy = 2F * (y - cy - unit + radius * 0.5F);
                    if (flat + qy * qy >= sq(radius - w)) continue;
                    float sub = qy + 4F * unit;
                    if (flat + sub * sub <= sq(radius + w)) continue;
                    set(x, y, z, part);
                }
            }
        }
    }

    /** A plain sphere - UmbrellaTreeFeature.java:269, the opaque hub in the middle of a membrane. */
    private void ball(float cx, float cy, float cz, float radius, Part part) {
        int reach = (int) Math.ceil(radius) + 1;
        int x0 = (int) Math.floor(cx);
        int y0 = (int) Math.floor(cy);
        int z0 = (int) Math.floor(cz);
        for (int x = x0 - reach; x <= x0 + reach; x++) {
            for (int y = y0 - reach; y <= y0 + reach; y++) {
                for (int z = z0 - reach; z <= z0 + reach; z++) {
                    if (sq(x - cx) + sq(y - cy) + sq(z - cz) < radius * radius) set(x, y, z, part);
                }
            }
        }
    }

    /**
     * GiganticAmaranitaFeature.java:41-92. A short stem with three loose strands woven up it and a head
     * that contains no SDF at all - {@code makeHead} is 280 lines of literal integer loops.
     */
    private void giganticAmaranita() {
        int size = randRange(5, 10);                                       // :51 - int overload, 5..10
        List<float[]> spline = makeSpline(0, 0, 0, 0, size, 0, 5);         // :52
        offsetParts(spline, 0.7F, 0F, 0.7F);                               // :53
        float[] tip = spline.get(spline.size() - 1);
        // :69-71 - the head is written first and the stem cannot overwrite it, exactly as in the mod
        // where makeHead runs before fillRecursive and REPLACE rejects the solid blocks it just placed.
        // The casts are the mod's: (int) truncates toward zero, it is not a floor.
        amaranitaHead((int) (tip[0] + 0.5F), (int) (tip[1] + 1.5F), (int) (tip[2] + 0.5F),
                (int) Math.floor(size / 1.6F));
        fillTube(spline, size * 0.17F, 0.2F, Part.STEM);                   // :60-66
        for (int i = 0; i < 3; i++) {                                      // :77-88
            List<float[]> strand = copy(spline);
            offsetParts(strand, 0.2F, 0F, 0.2F);
            fillSpline(strand, Part.HYPHAE);
        }
        // :75 passes IGNORE = EndWoodBlocks.DRAGON_TREE::isTreeLog (:382), which can never match an
        // amaranita block: it is a copy-paste bug and fillRecursiveIgnore is a plain fillRecursive.
        // Not ported.
    }

    /**
     * GiganticAmaranitaFeature.java:94-377, the two branches that are reachable.
     * {@code radius = floor(size / 1.6)} with {@code size} in 5..10 (:51, :71) is 3, 4, 5 or 6, so the
     * {@code radius < 2} branch (:96-158) is dead code and is not transcribed.
     */
    private void amaranitaHead(int cx, int cy, int cz, int radius) {
        if (radius < 4) {
            cx -= 1;                                                       // :160
            cz -= 1;
            for (int i = -2; i < 2; i++) {                                 // :161-178
                set(cx - i, cy, cz - 2, Part.HYMENOPHORE);                 // NORTH 2, WEST i
                set(cx - i, cy, cz + 3, Part.HYMENOPHORE);                 // SOUTH 3, WEST i
                set(cx + 3, cy, cz - i, Part.HYMENOPHORE);                 // EAST 3, NORTH i
                set(cx - 2, cy, cz - i, Part.HYMENOPHORE);                 // WEST 2, NORTH i
            }
            amaranitaUnderside(cx, cy, cz, -1, 3, true);                   // :179-214
            int h = radius - 1;                                            // :216
            for (int y = 0; y < h; y++) {                                  // :217-228
                amaranitaCapLayer(cx, cy + y + 1, cz, -1, 3, false, true);
            }
            amaranitaCapLayer(cx, cy + h + 1, cz, -1, 3, true, true);      // :230-239
            return;
        }
        for (int i = -2; i < 3; i++) {                                     // :241-293
            set(cx + i, cy, cz - 3, Part.HYMENOPHORE);                     // NORTH 3, EAST i
            set(cx + i, cy + 1, cz - 3, Part.HYMENOPHORE);                 // ... then UP
            set(cx + i, cy + 1, cz - 4, Part.HYMENOPHORE);                 // ... then NORTH again
            set(cx + i, cy, cz + 3, Part.HYMENOPHORE);                     // SOUTH 3, EAST i
            set(cx + i, cy + 1, cz + 3, Part.HYMENOPHORE);
            set(cx + i, cy + 1, cz + 4, Part.HYMENOPHORE);
            set(cx + 3, cy, cz - i, Part.HYMENOPHORE);                     // EAST 3, NORTH i
            set(cx + 3, cy + 1, cz - i, Part.HYMENOPHORE);
            set(cx + 4, cy + 1, cz - i, Part.HYMENOPHORE);
            set(cx - 3, cy, cz - i, Part.HYMENOPHORE);                     // WEST 3, NORTH i
            set(cx - 3, cy + 1, cz - i, Part.HYMENOPHORE);
            set(cx - 4, cy + 1, cz - i, Part.HYMENOPHORE);
        }
        // :295-303 - HORIZONTAL is {NORTH, EAST, SOUTH, WEST} (BlocksHelper.java:217-219), so the four
        // consecutive pairs are the four corners at (+-3, +1, +-3).
        for (int[] corner : new int[][]{{3, -3}, {3, 3}, {-3, 3}, {-3, -3}}) {
            set(cx + corner[0], cy + 1, cz + corner[1], Part.HYMENOPHORE);
        }
        amaranitaUnderside(cx, cy, cz, -2, 3, false);                      // :305-340
        for (int y = 0; y < 3; y++) {                                      // :342-353
            amaranitaCapLayer(cx, cy + y + 1, cz, -2, 3, false, false);
        }
        int h = radius + 1;                                                // :355-375
        for (int y = 4; y < h; y++) {
            // Note this Y is pos.getY() + y, NOT + y + 1 as in the three solid layers above.
            for (int x = -2; x < 3; x++) {
                for (int z = -2; z < 3; z++) {
                    boolean keep = y < 6
                            ? mask(x, false) || mask(z, false)
                            : (x == 0 || z == 0) && Math.abs(x) < 2 && Math.abs(z) < 2;
                    if (keep) set(cx + x, cy + y, cz + z, Part.CAP);
                }
            }
        }
    }

    /**
     * The lantern-and-fur underside, GiganticAmaranitaFeature.java:179-214 (radius 3) and :305-340
     * (radius 4+). The two are the same code with two differences that must NOT be tidied away: the
     * span, and the mask operator - see {@link #mask}.
     */
    private void amaranitaUnderside(int cx, int cy, int cz, int lo, int hi, boolean shift) {
        for (int x = lo; x < hi; x++) {
            for (int z = lo; z < hi; z++) {
                int y = cy;
                if (!set(cx + x, y, cz + z, Part.LANTERN)) continue;       // :182 canBeReplaced
                y--;                                                       // :184
                if (mask(x, shift) || mask(z, shift)) {                    // :185
                    put(cx + x, y, cz + z, Part.LANTERN);                  // :186 - NOT gated
                    boolean axisX = x < 0 || x > 1;                        // :187
                    int step = axisX ? (x < 0 ? -1 : 1) : (z < 0 ? -1 : 1);// :188
                    // Direction.fromAxisAndDirection: X- is WEST, X+ EAST, Z- NORTH, Z+ SOUTH.
                    Part fur = axisX
                            ? (step < 0 ? Part.FUR_WEST : Part.FUR_EAST)
                            : (step < 0 ? Part.FUR_NORTH : Part.FUR_SOUTH);
                    if (axisX) set(cx + x + step, y, cz + z, fur);         // :189-201
                    else set(cx + x, y, cz + z + step, fur);
                    y--;                                                   // :202
                }
                set(cx + x, y, cz + z, Part.FUR_DOWN);                     // :204-211
            }
        }
    }

    /** One square layer of cap, optionally with the corners taken off. */
    private void amaranitaCapLayer(int cx, int y, int cz, int lo, int hi, boolean masked, boolean shift) {
        for (int x = lo; x < hi; x++) {
            for (int z = lo; z < hi; z++) {
                if (!masked || mask(x, shift) || mask(z, shift)) set(cx + x, y, cz + z, Part.CAP);
            }
        }
    }

    /**
     * The corner mask. The radius-3 branch writes {@code (x >> 1) == 0}
     * (GiganticAmaranitaFeature.java:185, :235) and the radius-4+ branch {@code (x / 2) == 0} (:311,
     * :363). They are genuinely different shapes - {@code -1 >> 1} is {@code -1} but {@code -1 / 2} is
     * {@code 0} - so the shift keeps only 0 and 1 while the division also keeps -1. Do not unify them.
     */
    private static boolean mask(int v, boolean shift) {
        return shift ? (v >> 1) == 0 : (v / 2) == 0;
    }

    /**
     * MossyGlowshroomFeature.java:74-172. A jittered trunk, a scalloped nested double cone of a cap with
     * a glowing cone tucked under it, and a squashed five-lobed sphere of bark for a root flare.
     */
    private void mossyGlowshroom() {
        float height = randRange(10F, 25F);                                // :91
        // :92 is floor(height / 4), which is 2 whenever height < 12 - and bclib's buildSDF then
        // divides by count - 2 == 0 (SplineHelper.java:155-158), so every capsule radius is NaN, every
        // distance is NaN, `NaN < 0` is false, and the feature silently places nothing while still
        // reporting success. ponytail: 3 is the smallest count that is not that bug; what the mod MEANT
        // to draw for height in [10, 12) is NOT DETERMINABLE, because it never draws one.
        int count = Math.max(3, (int) Math.floor(height / 4F));
        List<float[]> spline = makeSpline(0, 0, 0, 0, height, 0, count);   // :93
        offsetParts(spline, 1F, 0F, 1F);                                   // :94
        float[] tip = spline.get(spline.size() - 1);                       // :98
        float scale = randRange(0.75F, 1.1F);                              // :99
        // :119-123 - shrink the whole mushroom until its cap fits the room its stalk has, rather than
        // letting the write bounds take a straight chord out of a circle.
        int hx = (int) Math.floor(tip[0] * scale);
        int hz = (int) Math.floor(tip[2] * scale);
        int room = Math.min(Math.min(hx - minX, maxX - hx), Math.min(hz - minZ, maxZ - hz));
        scale = Math.max(Math.min(scale, room / CAP_REACH), MIN_SCALE);
        float rootAngle = random.nextFloat() * PI2;                        // :127

        // The mod is one SmoothUnion of trunk, head and roots and lets the smaller distance choose the
        // block (SDFBinary.java:24-33). Drawing the three in turn differs only where they touch. The
        // roots are BARK, which overwrites (Part.overwrites), so where the root flare wraps the foot of
        // the trunk it wins outright instead of losing to the deeper trunk distance - the bottom few
        // cells of the stalk read as bark, not log. The cap is CAP, which does not overwrite, so the
        // trunk keeps the seam; the post pass turns those cells into cap-transition or bark anyway.
        List<float[]> drawn = copy(spline);
        scale(drawn, scale, scale, scale);
        fillTube(drawn, 2.1F * scale, 1.5F * scale, Part.LOG);             // :95
        glowshroomRoots(scale, rootAngle);                                 // :211-214
        glowshroomCap(tip, scale);                                         // :174-209
    }

    /** MossyGlowshroomFeature.java:211-214 - Sphere(4), squashed to 0.7 in Y, with five radial lobes. */
    private void glowshroomRoots(float scale, float angle) {
        int reach = (int) Math.ceil((4F + 1.5F) * scale) + 1;
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                float qx = x / scale;
                float qz = z / scale;
                // The wave sits outside the Scale3D, so it reads the unsquashed angle; atan2 does not
                // care about the outer SDFScale either.
                float w = (float) Math.cos(Math.atan2(qx, qz) * 5 + angle) * 1.5F;
                for (int y = -reach; y <= reach; y++) {
                    float qy = y / scale / 0.7F;
                    if (Math.sqrt(qx * qx + qy * qy + qz * qz) - 4F + w >= 0) continue;
                    set(x, y, z, Part.BARK);
                }
            }
        }
    }

    /**
     * MossyGlowshroomFeature.java:174-209, evaluated cell by cell.
     * <p>
     * The graph is four capped cones on one axis. {@code posedCone2} is {@code cone2} lifted to
     * {@code y} 2..8; {@code posedCone3} is a 2x copy lifted to 6..18 and SUBTRACTED from it, which is
     * what hollows the disc into a bowl - above y=6 what is left of {@code posedCone2} is an annulus.
     * {@code innerCone} is that same bowl 1.2x wider and 1.25 higher, {@code cone1} a small boss where
     * the cap meets the stalk, and {@code glowCone} the hymenophore, the same construction 12.5 wide,
     * tucked underneath. All of that composes exactly on distances, so this is the mod's real shape and
     * not a re-imagining; only the two {@code SDFSmoothUnion}s are dropped, as everywhere else in this
     * file, costing about a block of fillet at the seams.
     * <p>
     * The whole cap is then domain-warped (:199-206): the rim sags by {@code 0.15 * dist} and wobbles by
     * {@code +-0.3 * dist} on a noise field. The mod seeds that noise 1234 and offsets it by the tree's
     * WORLD x/z, so neighbouring caps sag in sympathy. ponytail: {@link TreeShape} is deliberately
     * world-position-free, so the offset comes from the tree's own seed instead. Per-tree variety is
     * identical; the correlation between neighbours is lost, which nothing in the game reads.
     */
    private void glowshroomCap(float[] tip, float scale) {
        OpenSimplexNoise noise = new OpenSimplexNoise(1234);               // :198 - a FIXED seed
        float noiseX = (seed & 0xFFFFL) * 0.5F;
        float noiseZ = ((seed >>> 16) & 0xFFFFL) * 0.5F;
        float cy = tip[1] + 2.5F;                                          // :208-209
        int reach = (int) Math.ceil(CAP_REACH * scale) + 1;
        int cx = (int) Math.floor(tip[0] * scale);
        int cz = (int) Math.floor(tip[2] * scale);
        // The warp can move a cell by up to 0.45 * CAP_REACH in Y either way, so the column has to be
        // walked well past the shape's own nine blocks of depth.
        int lowY = (int) Math.floor((cy - 13F) * scale);
        int highY = (int) Math.ceil((cy + 19F) * scale);
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                float qx = x / scale - tip[0];
                float qz = z / scale - tip[2];
                float dist = (float) Math.sqrt(qx * qx + qz * qz);
                if (dist > CAP_REACH) continue;
                double warp = noise.eval(qx * 0.1 + noiseX, qz * 0.1 + noiseZ) * dist * 0.3 - dist * 0.15;
                float wave = (float) Math.cos(Math.atan2(qx, qz) * 12) * 1.3F;   // :181
                for (int y = lowY; y <= highY; y++) {
                    float qy = (float) (y / scale - cy + warp);
                    if (qy < -4F || qy > 10F) continue;                    // nothing lives outside that
                    float cap = Math.min(cappedCone(qx, qy, qz, 2.5F, 1.5F, 2.5F),   // :175
                            Math.min(upCone(qx, qy, qz) + wave,                       // :180-181
                                    upCone(qx / 1.2F, qy - 1.25F, qz / 1.2F)));       // :187-188
                    float glow = Math.max(cappedCone(qx, qy - 4.25F, qz, 3F, 2F, GLOW_RADIUS),
                            -posedCone3(qx, qy, qz));                                 // :191-194
                    if (cap >= 0 && glow >= 0) continue;
                    set(x, y, z, glow < cap ? Part.HYMENOPHORE : Part.CAP);
                }
            }
        }
    }

    /** MossyGlowshroomFeature.java:176-180 - cone2 lifted to y 2..8, with the 2x copy cut out of it. */
    private static float upCone(float x, float y, float z) {
        return Math.max(cappedCone(x, y - 5F, z, 3F, 2.5F, CAP_RADIUS), -posedCone3(x, y, z));
    }

    /** MossyGlowshroomFeature.java:178-179 - SDFScale is {@code src(p / s) * s} (SDFScale.java:13-15). */
    private static float posedCone3(float x, float y, float z) {
        return cappedCone(x * 0.5F, (y - 12F) * 0.5F, z * 0.5F, 3F, 2.5F, CAP_RADIUS) * 2F;
    }

    /**
     * SDFCappedCone.getDistance, transcribed (BCLib SDFCappedCone.java:27-43): a truncated cone spanning
     * {@code y} in {@code [-height, +height]}, {@code radius1} at the bottom and {@code radius2} at the
     * top. iq's exact distance, so it composes under min / max / scale the way the mod's graph needs.
     */
    private static float cappedCone(float x, float y, float z, float height, float radius1, float radius2) {
        float qx = (float) Math.sqrt(x * x + z * z);
        float k2x = radius2 - radius1;
        float k2y = 2F * height;
        float cax = qx - Math.min(qx, (y < 0F) ? radius1 : radius2);
        float cay = Math.abs(y) - height;
        float mlt = clamp(((radius2 - qx) * k2x + (height - y) * k2y) / (k2x * k2x + k2y * k2y));
        float cbx = qx - radius2 + k2x * mlt;
        float cby = y - height + k2y * mlt;
        float s = (cbx < 0F && cay < 0F) ? -1F : 1F;
        return s * (float) Math.sqrt(Math.min(cax * cax + cay * cay, cbx * cbx + cby * cby));
    }

    /** The cap's nominal outer radius - MossyGlowshroomFeature.java:50, cone2's radius2. */
    private static final float CAP_RADIUS = 13F;
    /** MossyGlowshroomFeature.java:51. */
    private static final float GLOW_RADIUS = 12.5F;
    /** MossyGlowshroomFeature.java:66 - {@code CAP_RADIUS * 1.2 + 2.3}, the reach of one unit of scale. */
    private static final float CAP_REACH = CAP_RADIUS * 1.2F + 2.3F;
    /** MossyGlowshroomFeature.java:71 - a floor for the fitted scale, not a case that normally fires. */
    private static final float MIN_SCALE = 0.5F;

    // --- per-species post passes ------------------------------------------------------------

    /**
     * GiganticAmaranitaFeature.java:384-389 - {@link #promoteLogs} inverted. Stem is the default and
     * hyphae is what caps each end of it, where the log rule makes bark the default and log the core.
     */
    private void capStemsWithHyphae() {
        Map<Pos, Part> before = Map.copyOf(blocks);
        for (Map.Entry<Pos, Part> entry : blocks.entrySet()) {
            if (entry.getValue() != Part.STEM) continue;
            Pos p = entry.getKey();
            if (before.get(new Pos(p.x(), p.y() + 1, p.z())) != Part.STEM
                    || before.get(new Pos(p.x(), p.y() - 1, p.z())) != Part.STEM) {
                entry.setValue(Part.HYPHAE);
            }
        }
    }

    /**
     * MossyGlowshroomFeature.java:130-167 - the log rule with two extra branches, and the fur.
     * <p>
     * The mod runs this inside the flood fill, so its {@code random.nextBoolean()} (:132) follows the
     * fill order; here it follows insertion order. Both are arbitrary and neither is observable beyond
     * "about half the logs under the cap become cap-transition".
     * <p>
     * The mod also sorts by Y and rewrites in place (SDF.java:94-100), so a cell reads the ALREADY
     * rewritten state below it and the untouched state above. This reads a snapshot of both. The only
     * cells where that can differ are the one or two at the trunk/cap seam whose neighbour below the mod
     * had just turned into cap-transition or bark, and the difference there is which variant of the same
     * block they get - not whether one is placed.
     * <p>
     * The four horizontal furs of :151-158 are NOT ported: the pack's {@code mossy_glowshroom_fur} is a
     * {@code hanging_block} with {@code above_blocks: [betterend:mossy_glowshroom_hymenophore]} and no
     * facing property at all (B1-wood-families.yml:386-407), so the only fur state that exists is the
     * downward one of :160-165.
     */
    private void glowshroomSkin() {
        Map<Pos, Part> before = Map.copyOf(blocks);
        List<Pos> fur = new ArrayList<>();
        for (Map.Entry<Pos, Part> entry : blocks.entrySet()) {
            Pos p = entry.getKey();
            Part above = before.get(new Pos(p.x(), p.y() + 1, p.z()));
            Part below = before.get(new Pos(p.x(), p.y() - 1, p.z()));
            switch (entry.getValue()) {
                case LOG -> {                                              // :131 - isTreeLog
                    if (random.nextBoolean() && above == Part.CAP) {       // :132-135
                        entry.setValue(Part.CAP_TRANSITION);
                    } else if (!isWood(above) || !isWood(below)) {         // :136-139
                        entry.setValue(Part.BARK);
                    }
                }
                case CAP -> {                                              // :141
                    if (isWood(below)) entry.setValue(Part.CAP_TRANSITION);// :142-145
                }
                case HYMENOPHORE -> {                                      // :150, :160-165
                    if (below != Part.HYMENOPHORE) fur.add(new Pos(p.x(), p.y() - 1, p.z()));
                }
                default -> {
                }
            }
        }
        // PosInfo.setBlockPos writes into a separate map that is applied only where the target is still
        // replaceable (BCLib PosInfo.java:113-117), which is exactly what Part.overwrites() gives us.
        for (Pos p : fur) set(p.x(), p.y(), p.z(), Part.FUR_DOWN);
    }

    private static boolean isWood(Part part) {
        return part == Part.BARK || part == Part.LOG;
    }

    // --- spline helpers ---------------------------------------------------------------------

    private static final float PI2 = (float) (Math.PI * 2);
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static List<float[]> makeSpline(float x1, float y1, float z1,
                                            float x2, float y2, float z2, int points) {
        List<float[]> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            float t = (float) i / (points - 1);
            out.add(new float[]{x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t});
        }
        return out;
    }

    /**
     * SplineHelper.offsetParts (BCLib SplineHelper.java:111-120): {@code pos + nextGaussian() * d},
     * with index 0 pinned, which is what keeps a trunk standing on the block it was placed on.
     * <p>
     * This was {@code randRange(-d, d)} while bclib was unreadable. A gaussian is unbounded and piles
     * up near zero where a uniform is bounded and flat, so correcting it moves all five wood species
     * as well as the four mushrooms -- which is why their documented reach moved by a block or two.
     * All three components are always drawn, even where {@code dy} is 0, or the random stream
     * desyncs from the mod's.
     */
    private void offsetParts(List<float[]> spline, float dx, float dy, float dz) {
        for (int i = 1; i < spline.size(); i++) {
            float[] p = spline.get(i);
            p[0] += (float) random.nextGaussian() * dx;
            p[1] += (float) random.nextGaussian() * dy;
            p[2] += (float) random.nextGaussian() * dz;
        }
    }

    private static void rotateY(List<float[]> spline, float angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        for (float[] p : spline) {
            float x = p[0] * cos - p[2] * sin;
            p[2] = p[0] * sin + p[2] * cos;
            p[0] = x;
        }
    }

    private static void scale(List<float[]> spline, float sx, float sy, float sz) {
        for (float[] p : spline) {
            p[0] *= sx;
            p[1] *= sy;
            p[2] *= sz;
        }
    }

    private static void offset(List<float[]> spline, float dx, float dy, float dz) {
        for (float[] p : spline) {
            p[0] += dx;
            p[1] += dy;
            p[2] += dz;
        }
    }

    private static List<float[]> copy(List<float[]> spline) {
        List<float[]> out = new ArrayList<>(spline.size());
        for (float[] p : spline) out.add(p.clone());
        return out;
    }

    /** {@code SplineHelper.getPos(spline, t)} - t is a segment index, the fraction interpolated. */
    private static float[] getPos(List<float[]> spline, float t) {
        int i = Math.min((int) t, spline.size() - 2);
        float f = t - i;
        float[] a = spline.get(i);
        float[] b = spline.get(i + 1);
        return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
    }

    /** MHelper.randRange(int, int, RandomSource) [bclib@1.21] - inclusive on both ends. */
    private int randRange(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private float randRange(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static float sq(float v) {
        return v * v;
    }

    // --- shared control polylines -----------------------------------------------------------

    /** DragonTreeFeature.java:329-335 - an upward-outward arc, one unit out and one unit up. */
    private static final List<float[]> BRANCH = List.of(
            new float[]{0, 0, 0},
            new float[]{0.1F, 0.3F, 0},
            new float[]{0.4F, 0.6F, 0},
            new float[]{0.8F, 0.8F, 0},
            new float[]{1, 1, 0}
    );
    /** DragonTreeFeature.java:336-347 - the fork: BRANCH's last three, rotated +-0.5 rad about its node. */
    private static final List<float[]> SIDE1 = fork(0.5F);
    private static final List<float[]> SIDE2 = fork(-0.5F);

    private static List<float[]> fork(float angle) {
        List<float[]> side = copy(BRANCH.subList(2, 5));
        offset(side, -0.4F, -0.6F, 0);
        rotateY(side, angle);
        offset(side, 0.4F, 0.6F, 0);
        return side;
    }

    /** DragonTreeFeature.java:349-356. */
    private static final List<float[]> DRAGON_ROOT = root(new float[][]{
            {0F, 1F, 0}, {0.1F, 0.7F, 0}, {0.3F, 0.3F, 0}, {0.7F, 0.05F, 0}, {0.8F, -0.2F, 0}
    });
    /** LucerniaFeature.java:282-288 - the same polyline without the leading point. */
    private static final List<float[]> LUCERNIA_ROOT = root(new float[][]{
            {0.1F, 0.7F, 0}, {0.3F, 0.3F, 0}, {0.7F, 0.05F, 0}, {0.8F, -0.2F, 0}
    });

    private static List<float[]> root(float[][] points) {
        List<float[]> out = new ArrayList<>(points.length);
        for (float[] p : points) out.add(new float[]{p[0], p[1] - 0.45F, p[2]});
        return out;
    }

    /**
     * LucerniaFeature.java:273-280 == TenaneaFeature.java:237-244 == UmbrellaTreeFeature.java:290-297,
     * byte for byte: a goblet curve that leans out half a unit and then straightens back up.
     */
    private static final List<float[]> SPLINE = List.of(
            new float[]{0.00F, 0.00F, 0.00F},
            new float[]{0.10F, 0.35F, 0.00F},
            new float[]{0.20F, 0.50F, 0.00F},
            new float[]{0.30F, 0.55F, 0.00F},
            new float[]{0.42F, 0.70F, 0.00F},
            new float[]{0.50F, 1.00F, 0.00F}
    );
}
