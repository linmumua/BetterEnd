package dev.mumu.betterend.trees;

import dev.mumu.betterend.trees.TreeShape.Part;
import dev.mumu.betterend.trees.TreeShape.Pos;
import dev.mumu.betterend.trees.TreeShape.Species;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry only - {@link TreeShape} names nothing outside {@code java.*} and the terrain noise, so
 * none of this needs a server. Every assertion is paired with a non-degeneracy check: a test over an
 * empty tree would otherwise pass vacuously.
 * <p>
 * The tests that talk about leaves, or about the universal log rule, are narrowed to the five wood
 * species: the four mushrooms have no {@code _leaves} block at all, amaranita has no bark or log
 * either, and mossy glowshroom's log rule has an extra branch. Their own properties are in
 * {@link MushroomShapeTest}.
 */
class TreeShapeTest {
    /** Effectively unbounded, so nothing is clipped and the documented reach is the real reach. */
    private static final int FAR = 10_000;
    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static Map<Pos, Part> tree(Species species, long seed) {
        return TreeShape.generate(species, seed, -FAR, FAR, -FAR, FAR, p -> true);
    }

    @ParameterizedTest
    @EnumSource(Species.class)
    void sameSeedGivesTheSameTree(Species species) {
        Map<Pos, Part> a = tree(species, 12345L);
        Map<Pos, Part> b = tree(species, 12345L);
        // 100, not 200: gigantic amaranita legitimately bottoms out near 120 cells (a size-5 stem
        // under a radius-3 head, GiganticAmaranitaFeature.java:51, :71) and that is the mod's own
        // smallest mushroom, not a broken one. The real volume floor is the 40-seed total below.
        assertTrue(a.size() > 100, species + " produced only " + a.size() + " blocks");
        assertEquals(a, b, species + " is not deterministic in its seed");
        // A different seed must give a different tree, or "deterministic" is trivially satisfied by
        // ignoring the seed altogether.
        assertTrue(!a.equals(tree(species, 12346L)), species + " ignores its seed");
    }

    @ParameterizedTest
    @EnumSource(Species.class)
    void everyBlockIsInsideTheDocumentedBoundingBox(Species species) {
        int trees = 0;
        int blocks = 0;
        int maxReach = 0;
        for (long seed = 0; seed < 40; seed++) {
            Map<Pos, Part> tree = tree(species, seed);
            trees++;
            blocks += tree.size();
            for (Pos p : tree.keySet()) {
                maxReach = Math.max(maxReach, Math.max(Math.abs(p.x()), Math.abs(p.z())));
                assertTrue(Math.abs(p.x()) <= species.reachXZ && Math.abs(p.z()) <= species.reachXZ,
                        species + " reached " + p + ", past reachXZ " + species.reachXZ);
                assertTrue(p.y() >= species.minY && p.y() <= species.maxY,
                        species + " reached " + p + ", outside y " + species.minY + ".." + species.maxY);
            }
        }
        assertEquals(40, trees);
        assertTrue(blocks > 40 * 200, species + " emitted only " + blocks + " blocks over 40 seeds");
        // The box has to be tight enough to mean something: a species that never gets within half of
        // its own documented reach is being measured against a box that could not fail.
        assertTrue(maxReach * 2 >= species.reachXZ,
                species + " never reached past " + maxReach + " of its documented " + species.reachXZ);
    }

    /**
     * The three species with a trunk must have wood at every height from the ground to at least the
     * bottom of their documented size range, all of it one connected piece. A trunk broken anywhere -
     * a spline rasterised with diagonal-only steps, a canopy anchored off the trunk - fails this.
     */
    @Test
    void trunksAreConnectedFromTheGroundUp() {
        assertTrunkConnected(Species.LACUGROVE, 15);   // LacugroveFeature.java:60
        assertTrunkConnected(Species.DRAGON_TREE, 10); // DragonTreeFeature.java:81
        assertTrunkConnected(Species.PYTHADENDRON, 10); // PythadendronTreeFeature.java:65
    }

    private static void assertTrunkConnected(Species species, int minTrunkHeight) {
        for (long seed = 0; seed < 20; seed++) {
            Map<Pos, Part> tree = tree(species, seed);
            Set<Pos> wood = new HashSet<>();
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (e.getValue() != Part.LEAVES) wood.add(e.getKey());
            }
            Set<Pos> start = new HashSet<>();
            for (Pos p : wood) {
                if (p.y() == 0) start.add(p);
            }
            assertTrue(!start.isEmpty(), species + " seed " + seed + " has no wood at the ground");
            Set<Pos> reached = flood(start, wood);
            for (int y = 0; y <= minTrunkHeight; y++) {
                final int level = y;
                assertTrue(reached.stream().anyMatch(p -> p.y() == level),
                        species + " seed " + seed + " has no connected trunk block at y=" + level);
            }
        }
    }

    /**
     * No floating canopy: nearly every leaf must reach wood through leaves. The per-cell
     * {@code uniform(-1.5, 1.5)} displacement the mod applies to every ball
     * (DragonTreeFeature.java:244) strands a few rim cells by design, so this is a fraction and not
     * all of them - but a canopy centred off its branch strands all of them.
     */
    @ParameterizedTest
    @EnumSource(value = Species.class, mode = Mode.INCLUDE,
            names = {"LACUGROVE", "DRAGON_TREE", "LUCERNIA", "TENANEA", "PYTHADENDRON"})
    void leavesAttachToWood(Species species) {
        long leaves = 0;
        long attached = 0;
        for (long seed = 0; seed < 20; seed++) {
            Map<Pos, Part> tree = tree(species, seed);
            Set<Pos> canopy = new HashSet<>();
            Set<Pos> wood = new HashSet<>();
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (e.getValue() == Part.LEAVES) canopy.add(e.getKey());
                else wood.add(e.getKey());
            }
            Set<Pos> all = new HashSet<>(canopy);
            all.addAll(wood);
            Set<Pos> reached = flood(wood, all);
            leaves += canopy.size();
            attached += canopy.stream().filter(reached::contains).count();
        }
        assertTrue(leaves > 5_000, species + " grew only " + leaves + " leaves over 20 trees");
        assertTrue(attached * 100 >= leaves * 90,
                species + " left " + (leaves - attached) + " of " + leaves + " leaves floating");
    }

    /**
     * Bark with wood above and below becomes log - the mod's POST lambda, DragonTreeFeature.java:322.
     * <p>
     * Jellyshroom and umbrella tree share that lambda unchanged (JellyshroomFeature.java:66-69,
     * UmbrellaTreeFeature.java:142-143) so they are in. The other two are not: amaranita inverts the
     * rule and has no bark or log at all (GiganticAmaranitaFeature.java:384-389), and mossy
     * glowshroom adds a branch that turns a log into cap-transition (:132-135), which legitimately
     * leaves a log with a non-wood neighbour. Both are covered in {@link MushroomShapeTest}.
     */
    @ParameterizedTest
    @EnumSource(value = Species.class, mode = Mode.EXCLUDE,
            names = {"GIGANTIC_AMARANITA", "MOSSY_GLOWSHROOM"})
    void trunkInteriorIsLogAndTheOutsideStaysBark(Species species) {
        Map<Pos, Part> tree = tree(species, 7L);
        long logs = tree.values().stream().filter(p -> p == Part.LOG).count();
        long bark = tree.values().stream().filter(p -> p == Part.BARK).count();
        assertTrue(logs > 0, species + " promoted no bark to log");
        assertTrue(bark > 0, species + " left no bark at all");
        for (Map.Entry<Pos, Part> e : tree.entrySet()) {
            if (e.getValue() != Part.LOG) continue;
            Pos p = e.getKey();
            // Lacugrove's root pillars are the one place the mod writes log directly rather than by
            // promotion (LacugroveFeature.java:133), so the bottom of a pillar legitimately has
            // nothing under it. They cannot reach past y=3 (:115 caps at floor(3.5 + nextDouble())).
            if (species == Species.LACUGROVE && p.y() <= 4) continue;
            assertTrue(isWood(tree, p.x(), p.y() + 1, p.z()) && isWood(tree, p.x(), p.y() - 1, p.z()),
                    species + " has a log at " + p + " without wood above and below");
        }
    }

    /**
     * Lacugrove's trunk is {@code randRange(15, 25)} tall (LacugroveFeature.java:60) and nothing else
     * it draws reaches higher, so the tallest wood block is the size draw itself.
     */
    @Test
    void lacugroveHeightMatchesTheModsRange() {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (long seed = 0; seed < 150; seed++) {
            int top = tree(Species.LACUGROVE, seed).entrySet().stream()
                    .filter(e -> e.getValue() != Part.LEAVES)
                    .mapToInt(e -> e.getKey().y())
                    .max()
                    .orElseThrow();
            assertTrue(top >= 15 && top <= 25, "lacugrove trunk top y=" + top + ", outside 15..25");
            lowest = Math.min(lowest, top);
            highest = Math.max(highest, top);
        }
        // Non-degenerate: a constant-height implementation would satisfy the range check above.
        assertTrue(lowest <= 17, "lacugrove never grew short: shortest was " + lowest);
        assertTrue(highest >= 23, "lacugrove never grew tall: tallest was " + highest);
    }

    /** The write box is a hard clip, so a tree at the corner of one writes nothing outside it. */
    @Test
    void theWriteBoxClips() {
        Map<Pos, Part> tree = TreeShape.generate(Species.DRAGON_TREE, 3L, -4, 6, -4, 6, p -> true);
        assertTrue(tree.size() > 100, "clipped dragon tree emitted only " + tree.size() + " blocks");
        for (Pos p : tree.keySet()) {
            assertTrue(p.x() >= -4 && p.x() <= 6 && p.z() >= -4 && p.z() <= 6, "wrote outside the box at " + p);
        }
    }

    /**
     * The end-stone gate is consulted by exactly the species that carry roots, and by no others.
     * <p>
     * DragonTree (:204), Lucernia (:246) and Lacugrove's root pillars (:119) all probe the ground
     * before drawing; Tenanea has no roots at all and Pythadendron never leaves the trunk top. The
     * predicate draws no randomness in either the mod or here, so the two runs share a random stream
     * and the "no rock anywhere" tree is exactly the "rock everywhere" tree minus its roots - which is
     * a far sharper claim than "smaller".
     */
    @ParameterizedTest
    @EnumSource(Species.class)
    void rootsAreGatedOnEndStone(Species species) {
        // Jellyshroom (JellyshroomFeature.java:79-80) and umbrella tree (UmbrellaTreeFeature.java:165)
        // both call makeRoots with the same END_STONES tip gate. Amaranita and mossy glowshroom have
        // no roots at all: glowshroom's root flare (:211-214) is part of its SDF and never probes the
        // ground, so like tenanea and pythadendron they must not change when the ground does.
        boolean hasRoots = species != Species.TENANEA && species != Species.PYTHADENDRON
                && species != Species.GIGANTIC_AMARANITA && species != Species.MOSSY_GLOWSHROOM;
        int shrank = 0;
        for (long seed = 0; seed < 20; seed++) {
            Map<Pos, Part> onRock = TreeShape.generate(species, seed, -FAR, FAR, -FAR, FAR, p -> true);
            Map<Pos, Part> onAir = TreeShape.generate(species, seed, -FAR, FAR, -FAR, FAR, p -> false);
            if (!hasRoots) {
                assertEquals(onRock, onAir,
                        species + " has no roots in the mod but changed when the ground did");
                continue;
            }
            assertTrue(onRock.size() >= onAir.size(), species + " grew MORE without ground to root in");
            // Positions only: a root sitting under a trunk cell promotes it from bark to log, exactly
            // as the mod's POST lambda does (DragonTreeFeature.java:322-327), so the two trees legally
            // disagree on the *part* of a shared cell.
            for (Pos p : onAir.keySet()) {
                assertTrue(onRock.containsKey(p),
                        species + " rootless tree is not a subset of the rooted one at " + p);
            }
            if (onAir.size() < onRock.size()) shrank++;
        }
        if (hasRoots) {
            assertTrue(shrank >= 15, species + " only grew roots on " + shrank + " of 20 seeds");
        }
    }

    /**
     * A canopy keeps the branch it hangs on. Every mod canopy fills with
     * {@code fillRecursiveIgnore(..., IGNORE)} and {@code IGNORE} is always
     * {@code <family>::isTreeLog} (DragonTreeFeature.java:320, LucerniaFeature.java:271), so leaves
     * never overwrite wood - only the other way round, which each {@code REPLACE} allows explicitly
     * (DragonTreeFeature.java:314-316). Measured: with that rule the deeply buried wood count is
     * 12 / 8 / 15 blocks at worst over 20 seeds; letting leaves clobber wood drops it to 1 / 3 / 3,
     * i.e. the branch fan and the goblet tips dissolve inside their own canopies.
     */
    @Test
    void canopiesDoNotEraseTheBranchTheyHangOn() {
        assertEmbeddedWood(Species.DRAGON_TREE, 8);
        assertEmbeddedWood(Species.LUCERNIA, 6);
        assertEmbeddedWood(Species.TENANEA, 8);
    }

    private static void assertEmbeddedWood(Species species, int min) {
        for (long seed = 0; seed < 20; seed++) {
            // No ground, so roots cannot pad the count: everything left is canopy skeleton.
            Map<Pos, Part> tree = TreeShape.generate(species, seed, -FAR, FAR, -FAR, FAR, p -> false);
            int buried = 0;
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (e.getValue() == Part.LEAVES) continue;
                Pos p = e.getKey();
                int leafSides = 0;
                for (int[] step : NEIGHBOURS) {
                    if (tree.get(new Pos(p.x() + step[0], p.y() + step[1], p.z() + step[2])) == Part.LEAVES) {
                        leafSides++;
                    }
                }
                if (leafSides >= 5) buried++;
            }
            assertTrue(buried >= min, species + " seed " + seed + " kept only " + buried
                    + " wood blocks inside its canopy, expected at least " + min);
        }
    }

    private static boolean isWood(Map<Pos, Part> tree, int x, int y, int z) {
        Part part = tree.get(new Pos(x, y, z));
        return part == Part.BARK || part == Part.LOG;
    }

    private static Set<Pos> flood(Set<Pos> start, Set<Pos> passable) {
        Set<Pos> seen = new HashSet<>(start);
        ArrayDeque<Pos> queue = new ArrayDeque<>(start);
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int[] step : NEIGHBOURS) {
                Pos next = new Pos(p.x() + step[0], p.y() + step[1], p.z() + step[2]);
                if (passable.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        return seen;
    }
}
