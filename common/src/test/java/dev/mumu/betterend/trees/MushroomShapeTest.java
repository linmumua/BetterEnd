package dev.mumu.betterend.trees;

import dev.mumu.betterend.trees.TreeShape.Part;
import dev.mumu.betterend.trees.TreeShape.Pos;
import dev.mumu.betterend.trees.TreeShape.Species;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four mushrooms. Geometry only, exactly like {@link TreeShapeTest} - and a sibling rather than
 * more cases in it, because the properties that matter here are different ones: a mushroom has no
 * leaves, three of the four have no {@code _bark}/{@code _log}, and two of them place blocks
 * ({@code fur}, {@code cluster}) whose only legal state depends on what sits next to them.
 */
class MushroomShapeTest {
    /** Effectively unbounded, so nothing is clipped and the documented reach is the real reach. */
    private static final int FAR = 10_000;
    /** The room a populator is actually guaranteed - CraftLimitedRegion.java:49, :66-73. */
    private static final int BOX_LOW = -16;
    private static final int BOX_HIGH = 31;
    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final Set<Part> FUR = EnumSet.of(
            Part.FUR_DOWN, Part.FUR_NORTH, Part.FUR_SOUTH, Part.FUR_EAST, Part.FUR_WEST);

    /**
     * {@code new Random(n).nextInt(4)} returns the same value for every small {@code n}, so walking
     * seeds 0, 1, 2... draws the same first few values every time: over 150 consecutive seeds
     * jellyshroom rolls a height of 7 and nothing else. {@link TreePopulator} feeds each tree
     * {@code rng.nextLong()} from an already-mixed stream, so this mixer is what reproduces the
     * distribution the world will actually see. Any test that reads a species' RANGE has to use it.
     */
    private static long seed(long i) {
        long z = i * -7046029254386353131L + -4658895280553007687L;
        z = (z ^ (z >>> 30)) * -4658895280553007687L;
        z = (z ^ (z >>> 27)) * -7723592293110705685L;
        return z ^ (z >>> 31);
    }

    private static Map<Pos, Part> tree(Species species, long i) {
        return TreeShape.generate(species, seed(i), -FAR, FAR, -FAR, FAR, p -> true);
    }

    /**
     * Every part a species declares an id for has to be one it can actually draw, or
     * {@link TreePopulator} is resolving a block nothing will ever place - and, worse, disabling the
     * whole species if that id ever goes missing from the pack. This is the check that would have
     * caught a mis-transcribed branch of amaranita's head: five of its ten parts come from a single
     * masked loop each.
     */
    @ParameterizedTest
    @EnumSource(value = Species.class,
            names = {"JELLYSHROOM", "UMBRELLA_TREE", "GIGANTIC_AMARANITA", "MOSSY_GLOWSHROOM"})
    void everyDeclaredPartIsActuallyDrawn(Species species) {
        Map<Part, Integer> seen = new EnumMap<>(Part.class);
        for (long i = 0; i < 60; i++) {
            for (Part part : tree(species, i).values()) seen.merge(part, 1, Integer::sum);
        }
        for (Part part : species.ids.keySet()) {
            assertTrue(seen.getOrDefault(part, 0) > 0,
                    species + " declares " + species.ids.get(part) + " but never draws " + part);
        }
        // And nothing it draws may be missing an id, or TreePopulator silently skips those cells.
        for (Part part : seen.keySet()) {
            assertTrue(species.ids.containsKey(part),
                    species + " drew " + part + " with no CraftEngine id declared for it");
        }
    }

    /**
     * A mushroom is one piece. The mod flood-fills its SDF from the origin
     * (BCLib SDF.java:54-120), so a detached lobe cannot exist there at all; this port scans a box
     * instead, which is what makes the check worth having. A cap centred off its stalk, or a dome
     * whose flat bottom is mis-derived, strands the whole canopy and drops this to near zero.
     * <p>
     * Not 100%: the flat wave scallops the rim of every dome and the mod's own noise strands the odd
     * cell there. Measured over 300 seeds: 0.9999 / 0.9999 / 1.0000 / 0.9930.
     */
    @ParameterizedTest
    @EnumSource(value = Species.class,
            names = {"JELLYSHROOM", "UMBRELLA_TREE", "GIGANTIC_AMARANITA", "MOSSY_GLOWSHROOM"})
    void theWholeMushroomHangsTogether(Species species) {
        long cells = 0;
        long joined = 0;
        for (long i = 0; i < 30; i++) {
            Map<Pos, Part> tree = tree(species, i);
            assertTrue(tree.size() > 90, species + " seed " + i + " grew only " + tree.size() + " cells");
            Pos lowest = null;
            for (Pos p : tree.keySet()) {
                if (lowest == null || p.y() < lowest.y()) lowest = p;
            }
            cells += tree.size();
            joined += flood(Set.of(lowest), tree.keySet()).size();
        }
        assertTrue(cells > 30 * 90, species + " grew only " + cells + " cells over 30 seeds");
        assertTrue(joined * 1000 >= cells * 985,
                species + " left " + (cells - joined) + " of " + cells + " cells detached from the base");
    }

    /**
     * The three species that have a stalk must have one, unbroken, from the ground to its own top. A
     * spline rasterised with diagonal-only steps, or a head anchored off its stem, breaks this.
     * <p>
     * The stalk does not reach the size roll: the head is drawn first and the stem cannot overwrite it
     * (GiganticAmaranitaFeature.java:69-75, and the same for the others), so amaranita's lantern column
     * buries the top two cells of its own stem. The height ranges themselves are asserted in
     * {@link #headHeightMatchesTheModsRange}, where they are exactly derivable.
     */
    @Test
    void stalksRunFromTheGroundToTheHead() {
        // JellyshroomFeature.java:52 starts the spline at y = -1, one below the origin.
        assertStalkConnected(Species.JELLYSHROOM, EnumSet.of(Part.BARK, Part.LOG), -1, 5);
        // GiganticAmaranitaFeature.java:52 starts at 0; the smallest size is 5 and the head takes two.
        assertStalkConnected(Species.GIGANTIC_AMARANITA, EnumSet.of(Part.STEM, Part.HYPHAE), 0, 3);
        // MossyGlowshroomFeature.java:93 starts at 0; height 10 times the 0.75 minimum scale is 7.
        assertStalkConnected(Species.MOSSY_GLOWSHROOM,
                EnumSet.of(Part.BARK, Part.LOG, Part.CAP_TRANSITION), 0, 7);
    }

    private static void assertStalkConnected(Species species, Set<Part> stalk, int base, int minTop) {
        for (long i = 0; i < 40; i++) {
            Map<Pos, Part> tree = tree(species, i);
            Set<Pos> wood = new HashSet<>();
            Set<Pos> start = new HashSet<>();
            int top = Integer.MIN_VALUE;
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (!stalk.contains(e.getValue())) continue;
                wood.add(e.getKey());
                if (e.getKey().y() == base) start.add(e.getKey());
                top = Math.max(top, e.getKey().y());
            }
            assertTrue(!start.isEmpty(), species + " seed " + i + " has no stalk at y=" + base);
            assertTrue(top >= minTop,
                    species + " seed " + i + " stalk stops at y=" + top + ", below " + minTop);
            Set<Pos> reached = flood(start, wood);
            // Up to minTop, not to top: the head is drawn first and the stalk cannot overwrite it
            // (see the javadoc), so the top cell or two of a stem can be a lone strand poking out
            // beside a lantern column that has swallowed the rest of that height.
            for (int y = base; y <= minTop; y++) {
                final int level = y;
                assertTrue(reached.stream().anyMatch(p -> p.y() == level),
                        species + " seed " + i + " stalk is broken at y=" + level);
            }
        }
    }

    /**
     * Height, against the mod's own roll. Every one of these is exact arithmetic on a documented
     * constant, not an observed number, and every one of them is also checked to actually vary - a
     * fixed-height implementation would satisfy a range on its own.
     */
    @Test
    void headHeightMatchesTheModsRange() {
        // JellyshroomFeature.java:50 - randRange(5, 8), and :53 gives offsetParts dy == 0, so the stem
        // tip lands on exactly that Y and the cap (which cannot overwrite wood) leaves it there.
        assertTopRange(Species.JELLYSHROOM, EnumSet.of(Part.BARK, Part.LOG), 5, 8, 5, 8);
        // GiganticAmaranitaFeature.java:51 size 5..10, :69-71 head origin at size + 1 and radius
        // floor(size / 1.6). Sizes 5 and 6 take the radius-3 branch, whose cap tops out at
        // origin + (radius - 1) + 1 = size + 4, i.e. 9 and 10; sizes 7..10 take the radius-4+ branch,
        // whose taper tops out at origin + (radius + 1) - 1 = size + 1 + radius, i.e. 12, 14, 15, 17.
        assertTopRange(Species.GIGANTIC_AMARANITA, EnumSet.of(Part.CAP), 9, 17, 10, 15);
        // MossyGlowshroomFeature.java:91 height 10..25 and :99 scale 0.75..1.1, and the scale is applied
        // to the trunk as well as the cap (:130), so the trunk top is the product of the two.
        assertTopRange(Species.MOSSY_GLOWSHROOM,
                EnumSet.of(Part.BARK, Part.LOG, Part.CAP_TRANSITION), 7, 28, 12, 24);
        // UmbrellaTreeFeature.java:69 size 10..20, :84-85 a branch is sizeXZ = size * 0.7..1.05 long and
        // randRange(1, 2) times that tall, :78 all of it scaled by 1..1.7. SPLINE's last point has
        // y == 1 (:296), so the tallest branch tip is 20 * 1.05 * 2 * 1.7 = 71 and the shortest a bare
        // 10 * 0.7 * 1 * 1 = 7.
        assertTopRange(Species.UMBRELLA_TREE, EnumSet.of(Part.BARK, Part.LOG), 7, 72, 14, 45);
    }

    private static void assertTopRange(Species species, Set<Part> parts, int min, int max,
                                       int mustReachDown, int mustReachUp) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (long i = 0; i < 60; i++) {
            Map<Pos, Part> tree = tree(species, i);
            int top = Integer.MIN_VALUE;
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (parts.contains(e.getValue())) top = Math.max(top, e.getKey().y());
            }
            assertTrue(top != Integer.MIN_VALUE, species + " seed " + i + " drew none of " + parts);
            assertTrue(top >= min && top <= max,
                    species + " seed " + i + " topped out at y=" + top + ", outside " + min + ".." + max);
            lowest = Math.min(lowest, top);
            highest = Math.max(highest, top);
        }
        assertTrue(lowest <= mustReachDown,
                species + " never grew short: shortest top was " + lowest);
        assertTrue(highest >= mustReachUp,
                species + " never grew tall: tallest top was " + highest);
    }

    /**
     * The head sits ON the stalk. Flooding from the stalk alone has to reach essentially every cap,
     * hymenophore, lantern and membrane cell - which is a sharper claim than
     * {@link #theWholeMushroomHangsTogether}, because a head placed one block clear of its stalk is
     * still internally connected and still reaches the ground through nothing at all.
     */
    @ParameterizedTest
    @EnumSource(value = Species.class,
            names = {"JELLYSHROOM", "GIGANTIC_AMARANITA", "MOSSY_GLOWSHROOM"})
    void theHeadIsAttachedToTheStalk(Species species) {
        Set<Part> stalk = EnumSet.of(Part.BARK, Part.LOG, Part.STEM, Part.HYPHAE);
        long head = 0;
        long joined = 0;
        for (long i = 0; i < 30; i++) {
            Map<Pos, Part> tree = tree(species, i);
            Set<Pos> start = new HashSet<>();
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (stalk.contains(e.getValue())) start.add(e.getKey());
            }
            Set<Pos> reached = flood(start, tree.keySet());
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (stalk.contains(e.getValue())) continue;
                head++;
                if (reached.contains(e.getKey())) joined++;
            }
        }
        assertTrue(head > 3_000, species + " grew only " + head + " head cells over 30 seeds");
        assertTrue(joined * 1000 >= head * 985,
                species + " left " + (head - joined) + " of " + head + " head cells off the stalk");
    }

    /**
     * No floating fur. The pack's {@code mossy_glowshroom_fur} is a {@code hanging_block} with
     * {@code above_blocks: [betterend:mossy_glowshroom_hymenophore]} (B1-wood-families.yml:386-393):
     * a fur with anything else above it is a state the server will pop off on the first block update,
     * so this is not a cosmetic assertion. Amaranita's fur is a
     * {@code directional_attached_block} (B5-flora-b.yml:1339-1341) and needs a solid neighbour in the
     * direction it faces.
     */
    @Test
    void furAlwaysHasSomethingToHangFrom() {
        int glowshroomFur = 0;
        for (long i = 0; i < 30; i++) {
            Map<Pos, Part> tree = tree(Species.MOSSY_GLOWSHROOM, i);
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (e.getValue() != Part.FUR_DOWN) continue;
                glowshroomFur++;
                Pos p = e.getKey();
                assertEquals(Part.HYMENOPHORE, tree.get(new Pos(p.x(), p.y() + 1, p.z())),
                        "glowshroom fur at " + p + " has no hymenophore above it");
            }
        }
        assertTrue(glowshroomFur > 1_000, "glowshroom grew only " + glowshroomFur + " fur over 30 seeds");

        int amaranitaFur = 0;
        for (long i = 0; i < 30; i++) {
            Map<Pos, Part> tree = tree(Species.GIGANTIC_AMARANITA, i);
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (!FUR.contains(e.getValue())) continue;
                amaranitaFur++;
                Pos p = e.getKey();
                Pos support = switch (e.getValue()) {
                    case FUR_DOWN -> new Pos(p.x(), p.y() + 1, p.z());
                    case FUR_NORTH -> new Pos(p.x(), p.y(), p.z() + 1);
                    case FUR_SOUTH -> new Pos(p.x(), p.y(), p.z() - 1);
                    case FUR_EAST -> new Pos(p.x() - 1, p.y(), p.z());
                    default -> new Pos(p.x() + 1, p.y(), p.z());
                };
                Part attached = tree.get(support);
                assertTrue(attached != null && !FUR.contains(attached),
                        "amaranita " + e.getValue() + " at " + p + " hangs off " + attached);
            }
        }
        assertTrue(amaranitaFur > 500, "amaranita grew only " + amaranitaFur + " fur over 30 seeds");
    }

    /**
     * Amaranita's head takes no randomness at all: {@code makeHead} is a pure function of
     * {@code radius = floor(size / 1.6)} (GiganticAmaranitaFeature.java:71), so a size 5..10 mushroom
     * has exactly six possible heads and every block count in them is arithmetic. Asserting the six
     * profiles pins the whole 280-line transcription at once - any change to a span, a mask or the
     * axis test moves at least one number.
     * <p>
     * radius 3 (sizes 5, 6, :159-239): cap is two solid 4x4 layers plus a masked top, 32 + 12 = 44;
     * hymenophore is 4 sides x 4 values of i, 16; lanterns are 16 on the top row plus the 12 the
     * {@code (x >> 1) == 0 || (z >> 1) == 0} mask keeps, 28; one downward fur per cell, 16.
     * <p>
     * radius 4+ (sizes 7..10, :240-376): hymenophore is 4 sides x 5 values of i x 3 cells plus 4
     * corners, 64; lanterns are 25 plus the 21 that {@code (x / 2) == 0 || (z / 2) == 0} keeps (it
     * drops only the four corners), 46; 25 downward furs; caps are three solid 5x5 layers plus a taper
     * that grows with the radius, 75 + 21 / 42 / 47.
     * <p>
     * The side fur is where the two masks and the axis test show: 2/2/2/2 north/south/east/west for
     * radius 3 but 2/2/3/3 for radius 4+, because {@code axis = x < 0 || x > 1 ? X : Z} (:187, :313)
     * is not symmetric about the middle of {@code [-2, 2]} - it sends {@code x == -1} down the X
     * branch, into a column the loop has already lanterned, and {@code x == 2} down it into one it has
     * not. Unifying {@code >>} with {@code /} would take radius 3's lantern count from 28 to 31.
     */
    @Test
    void amaranitasHeadIsExactlyOneOfTheModsSix() {
        Set<String> allowed = Set.of(
                "CAP=44 HYM=16 LAN=28 FUR=16 N=2 S=2 E=2 W=2",   // size 5 and 6, radius 3
                "CAP=96 HYM=64 LAN=46 FUR=25 N=2 S=2 E=3 W=3",   // size 7, radius 4
                "CAP=117 HYM=64 LAN=46 FUR=25 N=2 S=2 E=3 W=3",  // sizes 8 and 9, radius 5
                "CAP=122 HYM=64 LAN=46 FUR=25 N=2 S=2 E=3 W=3"); // size 10, radius 6
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < 120; i++) {
            Map<Part, Integer> count = new EnumMap<>(Part.class);
            for (Part part : tree(Species.GIGANTIC_AMARANITA, i).values()) count.merge(part, 1, Integer::sum);
            String profile = "CAP=" + count.getOrDefault(Part.CAP, 0)
                    + " HYM=" + count.getOrDefault(Part.HYMENOPHORE, 0)
                    + " LAN=" + count.getOrDefault(Part.LANTERN, 0)
                    + " FUR=" + count.getOrDefault(Part.FUR_DOWN, 0)
                    + " N=" + count.getOrDefault(Part.FUR_NORTH, 0)
                    + " S=" + count.getOrDefault(Part.FUR_SOUTH, 0)
                    + " E=" + count.getOrDefault(Part.FUR_EAST, 0)
                    + " W=" + count.getOrDefault(Part.FUR_WEST, 0);
            assertTrue(allowed.contains(profile),
                    "amaranita seed " + i + " grew a head the mod cannot: " + profile);
            seen.add(profile);
        }
        // Non-degenerate: a head stuck on one radius would satisfy the check above.
        assertEquals(allowed, seen, "amaranita never grew every one of the mod's four head profiles");
    }

    /**
     * Umbrella fruit hangs under the canopy and nowhere else - UmbrellaTreeFeature.java:280-282 walks
     * down to the first empty cell and only plants if what is above it is membrane.
     */
    @Test
    void everyClusterHangsUnderMembrane() {
        int clusters = 0;
        for (long i = 0; i < 30; i++) {
            Map<Pos, Part> tree = tree(Species.UMBRELLA_TREE, i);
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (e.getValue() != Part.CLUSTER) continue;
                clusters++;
                Pos p = e.getKey();
                assertEquals(Part.MEMBRANE, tree.get(new Pos(p.x(), p.y() + 1, p.z())),
                        "umbrella cluster at " + p + " is not hanging from membrane");
            }
        }
        assertTrue(clusters > 200, "umbrella grew only " + clusters + " clusters over 30 seeds");
    }

    /**
     * How WIDE the canopy is, against the mod's own radius constants. Nothing else in this class reads
     * a radius: every other assertion here is about connectivity, height, part coverage or volume, and
     * a cap of the wrong radius satisfies all of them. Verified by mutation - dropping mossy
     * glowshroom's {@code CAP_RADIUS} from 13 to 9, or halving umbrella's membrane radius, passes every
     * other test in this file and fails only this one.
     * <p>
     * Bounds are arithmetic on the mod's constants plus two cells of rasterisation slack: the flat wave
     * scallops the rim, and at the widest ring the cap is a thin shell, so the outermost cells of an
     * exactly-r circle are not always hit.
     */
    @Test
    void canopyWidthMatchesTheModsRadii() {
        // MossyGlowshroomFeature.java:176 cone2 radius2 = CAP_RADIUS = 13 (:50), widened to 13 * 1.2 by
        // innerCone's Scale3D (:188) and shrunk again by the posedCone3 subtraction (:180); the flat
        // wave's 1.3 (:181) never reaches past that. All of it times scale = 0.75..1.1 (:99).
        assertCanopyWidth(Species.MOSSY_GLOWSHROOM, EnumSet.of(Part.CAP, Part.CAP_TRANSITION),
                (int) (2 * 13 * 0.75F) - 1, (int) (2 * 13 * 1.2F * 1.1F) + 2,
                (int) (2 * 13 * 1.2F * 0.75F) + 2, (int) (2 * 13 * 1.1F));
        // JellyshroomFeature.java:56-59 - radius = height * 0.7..0.9 with height 5..8 (:50), floored at
        // 1.5, and the wave adds 0.2 (:122).
        assertCanopyWidth(Species.JELLYSHROOM, EnumSet.of(Part.CAP),
                (int) (2 * (5 * 0.7F + 0.2F)) - 2, (int) (2 * (8 * 0.9F + 0.2F)) + 2,
                (int) (2 * (5 * 0.7F + 0.2F)) + 3, (int) (2 * 8 * 0.9F) - 2);
        // UmbrellaTreeFeature.java:96 - radius = (size + 0..size*0.5) * 0.4 with size 10..20 (:69), all
        // of it times scale = 1..1.7 (:78), plus the wave's 0.6 (:267). Several domes on a fan of
        // branches share one bounding box, so only the LOWER end pins a single membrane: the narrowest
        // canopy any roll can produce is one branch carrying the smallest dome.
        assertCanopyWidth(Species.UMBRELLA_TREE, EnumSet.of(Part.MEMBRANE),
                (int) (2 * (10 * 0.4F + 0.6F)) - 2,
                (int) (2 * (0.5F * 21 * 1.7F + 20 * 0.6F * 1.7F + 0.6F * 1.7F)) + 2,
                (int) (2 * (10 * 0.4F + 0.6F)) + 3, (int) (2 * 20 * 0.6F));
    }

    /**
     * @param min       no canopy may be narrower than this
     * @param max       nor wider
     * @param mustBeThin some seed must come within this, or the low end of the radius roll is dead code
     * @param mustBeWide and some seed must reach this, or the high end is
     */
    private static void assertCanopyWidth(Species species, Set<Part> canopy,
                                          int min, int max, int mustBeThin, int mustBeWide) {
        int narrowest = Integer.MAX_VALUE;
        int widest = Integer.MIN_VALUE;
        for (long i = 0; i < 200; i++) {
            Map<Pos, Part> tree = tree(species, i);
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
            int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (Map.Entry<Pos, Part> e : tree.entrySet()) {
                if (!canopy.contains(e.getValue())) continue;
                x0 = Math.min(x0, e.getKey().x());
                x1 = Math.max(x1, e.getKey().x());
                z0 = Math.min(z0, e.getKey().z());
                z1 = Math.max(z1, e.getKey().z());
            }
            assertTrue(x1 >= x0, species + " seed " + i + " drew no " + canopy);
            int span = Math.max(x1 - x0 + 1, z1 - z0 + 1);
            assertTrue(span >= min && span <= max,
                    species + " seed " + i + " canopy is " + span + " wide, outside " + min + ".." + max);
            narrowest = Math.min(narrowest, span);
            widest = Math.max(widest, span);
        }
        assertTrue(narrowest <= mustBeThin,
                species + " never grew a small canopy: narrowest was " + narrowest);
        assertTrue(widest >= mustBeWide, species + " never grew a big one: widest was " + widest);
    }

    /**
     * The write budget, which is a real constraint and not a style note: {@link TreePopulator} already
     * reports a worst chunk of 17,668 writes, and umbrella_jungle is the one host that carries two of
     * these species at once (up to 2 umbrella trees and 2 jellyshrooms per layer).
     * <p>
     * Measured over 300 seeds inside the box a populator is actually guaranteed: umbrella 6,757,
     * glowshroom 2,488, jellyshroom 422, amaranita 324. The ceilings below are those with room to
     * spare. What holds umbrella down is {@code fitBallRadius} on every membrane, which is the mod's
     * own {@code zone.fitRadius} (UmbrellaTreeFeature.java:109-117): without it a dome of base radius
     * 20 is roughly 7,500 cells on its own and there can be three of them.
     */
    @Test
    void noMushroomBlowsTheChunkWriteBudget() {
        assertWriteCeiling(Species.UMBRELLA_TREE, 8_000);
        assertWriteCeiling(Species.MOSSY_GLOWSHROOM, 3_500);
        assertWriteCeiling(Species.JELLYSHROOM, 700);
        assertWriteCeiling(Species.GIGANTIC_AMARANITA, 500);
    }

    private static void assertWriteCeiling(Species species, int ceiling) {
        int worst = 0;
        for (long i = 0; i < 120; i++) {
            worst = Math.max(worst, TreeShape.generate(species, seed(i),
                    BOX_LOW, BOX_HIGH, BOX_LOW, BOX_HIGH, p -> true).size());
        }
        assertTrue(worst <= ceiling, species + " wrote " + worst + " cells into one region, over "
                + ceiling);
        // Non-degenerate: a species that writes nothing would pass any ceiling.
        assertTrue(worst * 4 >= ceiling, species + " only ever wrote " + worst + " cells, so the "
                + ceiling + " ceiling could not fail");
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
