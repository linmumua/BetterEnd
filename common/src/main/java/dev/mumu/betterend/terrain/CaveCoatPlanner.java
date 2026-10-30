package dev.mumu.betterend.terrain;

import dev.mumu.betterend.biome.BiomePlacement;
import dev.mumu.betterend.biome.BiomeSurface;
import dev.mumu.betterend.biome.CaveCoat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Works out which blocks a chunk's cave faces should become: the mod's
 * {@code CaveSurfaceCoatFeature}, as a pure function of the terrain.
 * <p>
 * Without it the caves {@link CaveCarver} and {@link TunnelCarver} dig are bare end stone in every
 * biome -- no jadestone banding in the jade caves, no moss in the lush ones.
 *
 * <h2>Why this port is simpler than the mod's, and more correct</h2>
 * The mod's feature spends most of its length on one problem it cannot otherwise solve: nothing in a
 * block state distinguishes a carved cavern from the sky above an island or the void beside it,
 * because the End's aquifer hands carved positions back as plain {@code AIR}, not {@code CAVE_AIR}.
 * So it classifies air by shape -- walking each column bottom-up and counting a run as cave only
 * when rock closes it at both ends inside a deliberately over-wide band -- and accepts, in its own
 * words, that a cavern breaching an island's underside stops being coated at the breach.
 * <p>
 * This port never reads a block. {@link IslandField} says where rock is and the carve plans say what
 * was removed from it, so the three states are exact:
 * <pre>
 *   density &lt;= 0            -&gt; OPEN  (sky or void; never painted, and never painted against)
 *   density &gt;  0 &amp;&amp; carved   -&gt; CAVE  (a face to coat)
 *   density &gt;  0 &amp;&amp; !carved  -&gt; ROCK  (paintable, if the biome fills it with plain end stone)
 * </pre>
 * The swept band can then be the carve band rather than the mod's wider classification band, and a
 * cavern that breaches an island is coated correctly right up to the breach.
 * <p>
 * Pure and Bukkit-free, so the index arithmetic and the shell transform are unit-tested without a
 * server. Deterministic in {@code (placement, field, plans, chunk)} alone.
 */
public final class CaveCoatPlanner {
    /** Cell is rock this pass may not paint: another biome's filler, or outside a cave column. */
    private static final byte SOLID = 0;
    /** Cell is rock the coat may paint. */
    private static final byte ROCK = 1;
    /** Cell is air a carver removed: a cave face is any ROCK touching one of these. */
    private static final byte CAVE = 2;
    /** Cell is air that was never rock: sky above an island, or the void beside it. */
    private static final byte OPEN = 3;

    /**
     * How far outside the chunk the window is READ, never written. A cave face on the chunk border
     * has its air in the next chunk over; without the margin neither chunk's sweep would see that
     * face and every border would keep a one-block seam of bare end stone.
     */
    private static final int MARGIN = 1;
    private static final int SPAN = 16 + 2 * MARGIN;
    /**
     * The top of the swept band: one above the carvers' shared ceiling of 48, so a cave block at
     * that ceiling still has its rock neighbour inside the window.
     */
    public static final int BAND_TOP = 49;
    private static final int HEIGHT = BAND_TOP + 1;

    /** One block to repaint, and what to repaint it as. */
    public record Paint(int x, int y, int z, String id) {
    }

    /**
     * One chunk's answer: which of its own blocks the carvers removed, and which of the survivors
     * the coat repaints.
     * <p>
     * The two travel together because they come from the same classification. Asking for them
     * separately would mean evaluating {@code carvedAt} twice over the same cells -- and that, not
     * the density field, is the expensive half of generating a cave chunk.
     */
    public record Sweep(byte[] cells, List<Paint> paints) {
        /** True where a carver removed this block. Chunk-local; {@code dx} and {@code dz} are 0..15. */
        public boolean carved(int dx, int y, int dz) {
            return cells[index(dx, y, dz)] == CAVE;
        }
    }

    /**
     * Whether the biome fills this position with plain end stone -- the mod's
     * {@code replaceable: minecraft:end_stone}. Supplied by the generator, which is the only thing
     * that knows each biome's resolved filler.
     */
    @FunctionalInterface
    public interface EndStone {
        boolean at(int x, int y, int z);
    }

    private CaveCoatPlanner() {
    }

    /**
     * Classifies the chunk and works out its coat: the carved blocks, then the repaints in
     * application order -- the wall shell first, then floors, then ceilings, so a biome defining
     * several keeps the mod's precedence and the shell is not reset by the faces painted over it.
     * <p>
     * Returns null -- cheaply, before touching the density field -- for a chunk no carver reaches,
     * which is most of the world.
     * <p>
     * A chunk whose cave biome paints nothing (three of the six do not) still gets its carve
     * classification; only the frontier, shell and paint steps are skipped.
     */
    public static Sweep sweep(int chunkX, int chunkZ,
                              BiomePlacement placement, IslandField field,
                              CaveCarver.Plan caves, TunnelCarver.Plan tunnels, EndStone endStone) {
        if (caves.isEmpty() && tunnels.isEmpty()) {
            return null;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;

        // 1) Which columns can be painted at all, and by what. Cheap, and it is what decides whether
        //    the frontier and shell steps run: a chunk of empty_end_cave is carved but never coated.
        BiomeSurface[] biomes = new BiomeSurface[SPAN * SPAN];
        boolean anyPaintable = false;
        for (int dx = -MARGIN; dx < 16 + MARGIN; dx++) {
            for (int dz = -MARGIN; dz < 16 + MARGIN; dz++) {
                BiomeSurface biome = placement.caveBiomeAt(minX + dx, minZ + dz);
                if (biome != null && !CaveCoat.paintsAnything(biome)) {
                    biome = null;
                }
                biomes[plane(dx, dz)] = biome;
                anyPaintable |= biome != null && dx >= 0 && dx < 16 && dz >= 0 && dz < 16;
            }
        }

        // 2) Classify the window. Air is classified in EVERY column, margin included -- a cave face
        //    is a face whichever side of a biome border its air sits on -- but only rock in a
        //    paintable column becomes ROCK, which is what stops a jade cave smearing jadestone into
        //    the empty cave next door.
        byte[] cells = new byte[SPAN * SPAN * HEIGHT];
        // The margin is only ever needed to see a cave face across a chunk border, so a chunk that
        // paints nothing reads its own 16x16 and no more.
        int edge = anyPaintable ? MARGIN : 0;
        for (int dx = -edge; dx < 16 + edge; dx++) {
            for (int dz = -edge; dz < 16 + edge; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                boolean paintable = biomes[plane(dx, dz)] != null;
                // The same topY call the carve pass makes: the coat must not disagree with the
                // actual carve about where a column's surface is.
                int top = field.topY(x, z);
                double[] density = field.densityColumn(x, z, 0, BAND_TOP);
                for (int y = 0; y <= BAND_TOP; y++) {
                    int idx = index(dx, y, dz);
                    if (density[y] <= 0) {
                        cells[idx] = OPEN;
                    } else if (caves.carvedAt(x, y, z, top) || tunnels.carvedAt(x, y, z, top)) {
                        cells[idx] = CAVE;
                    } else {
                        cells[idx] = paintable && endStone.at(x, y, z) ? ROCK : SOLID;
                    }
                }
            }
        }

        if (!anyPaintable) {
            return new Sweep(cells, List.of());
        }

        // 3) The cave faces, split by which face they are. A block bounding several cave blocks is
        //    seeded once.
        List<Integer> frontier = new ArrayList<>();
        List<Integer> floors = new ArrayList<>();
        List<Integer> ceilings = new ArrayList<>();
        boolean[] seen = new boolean[cells.length];
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int y = 0; y <= BAND_TOP; y++) {
                    int idx = index(dx, y, dz);
                    // A block that also faces open air is the outside of an island, not merely a
                    // cave face. Painting it would show jadestone on the island's skin.
                    if (cells[idx] != ROCK || touchesOpen(cells, dx, y, dz)) {
                        continue;
                    }
                    boolean bounds = false;
                    if (isCave(cells, dx, y + 1, dz)) {
                        floors.add(idx);
                        bounds = true;
                    }
                    if (isCave(cells, dx, y - 1, dz)) {
                        ceilings.add(idx);
                        bounds = true;
                    }
                    if (!bounds) {
                        bounds = isCave(cells, dx + 1, y, dz) || isCave(cells, dx - 1, y, dz)
                                || isCave(cells, dx, y, dz + 1) || isCave(cells, dx, y, dz - 1);
                    }
                    if (bounds) {
                        seen[idx] = true;
                        frontier.add(idx);
                    }
                }
            }
        }
        if (frontier.isEmpty()) {
            return new Sweep(cells, List.of());
        }

        List<Paint> out = new ArrayList<>();
        shell(out, biomes, cells, seen, frontier, minX, minZ);
        faces(out, biomes, floors, minX, minZ, true);
        faces(out, biomes, ceilings, minX, minZ, false);
        return new Sweep(cells, out);
    }

    /**
     * A breadth-first distance transform inward from every cave face, painting the wall material
     * {@link CaveCoat#SHELL_DEPTH} layers deep. This is the "5 layers thick" shell; the mod's own
     * notes record that its first attempt -- spheres sampled from floor positions -- left everything
     * above the lowest few blocks of a 12-to-38-block-tall cavern bare, which is how the jade caves
     * came out as end-stone caves with a jade floor.
     */
    private static void shell(List<Paint> out, BiomeSurface[] biomes, byte[] cells, boolean[] seen,
                              List<Integer> frontier, int minX, int minZ) {
        Deque<Integer> current = new ArrayDeque<>(frontier);
        for (int depth = 1; depth <= CaveCoat.SHELL_DEPTH && !current.isEmpty(); depth++) {
            Deque<Integer> next = new ArrayDeque<>();
            for (int idx : current) {
                int y = idx % HEIGHT;
                int rest = idx / HEIGHT;
                int dz = rest % SPAN - MARGIN;
                int dx = rest / SPAN - MARGIN;
                // Deeper layers can reach the outside of an island wherever the rock between a cave
                // and the sky is thinner than the shell. Those cells are skipped -- but the shell
                // still spreads THROUGH them, or it would stop dead at every thin spot.
                if (!touchesOpen(cells, dx, y, dz)) {
                    BiomeSurface biome = biomes[plane(dx, dz)];
                    String wall = biome == null ? null : CaveCoat.wall(biome, minX + dx, y, minZ + dz);
                    if (wall != null) {
                        out.add(new Paint(minX + dx, y, minZ + dz, wall));
                    }
                }
                if (depth == CaveCoat.SHELL_DEPTH) {
                    continue;
                }
                expand(cells, seen, next, dx + 1, y, dz);
                expand(cells, seen, next, dx - 1, y, dz);
                expand(cells, seen, next, dx, y + 1, dz);
                expand(cells, seen, next, dx, y - 1, dz);
                expand(cells, seen, next, dx, y, dz + 1);
                expand(cells, seen, next, dx, y, dz - 1);
            }
            current = next;
        }
    }

    private static void faces(List<Paint> out, BiomeSurface[] biomes, List<Integer> faces,
                              int minX, int minZ, boolean floor) {
        for (int idx : faces) {
            int y = idx % HEIGHT;
            int rest = idx / HEIGHT;
            int dz = rest % SPAN - MARGIN;
            int dx = rest / SPAN - MARGIN;
            BiomeSurface biome = biomes[plane(dx, dz)];
            if (biome == null) {
                continue;
            }
            String id = floor ? CaveCoat.floor(biome) : CaveCoat.ceiling(biome);
            if (id != null) {
                out.add(new Paint(minX + dx, y, minZ + dz, id));
            }
        }
    }

    /**
     * Marks a paintable, not-yet-visited cell as the next shell layer. Bounded to the chunk, not to
     * the read window: the margin exists to be looked at, never to be written.
     */
    private static void expand(byte[] cells, boolean[] seen, Deque<Integer> next, int dx, int y, int dz) {
        if (dx < 0 || dx > 15 || dz < 0 || dz > 15 || y < 0 || y > BAND_TOP) {
            return;
        }
        int idx = index(dx, y, dz);
        if (seen[idx] || cells[idx] != ROCK) {
            return;
        }
        seen[idx] = true;
        next.add(idx);
    }

    private static boolean isCave(byte[] cells, int dx, int y, int dz) {
        return inWindow(dx, y, dz) && cells[index(dx, y, dz)] == CAVE;
    }

    /**
     * Whether a cell has sky or void against any of its six sides. Such a block is part of the
     * outside of the world and is never painted, however close the cave behind it comes -- which,
     * together with only ever painting rock the biome fills with plain end stone, is what makes
     * "this pass never alters a surface visible from outside a cave" true by construction rather
     * than by luck of the geometry.
     */
    private static boolean touchesOpen(byte[] cells, int dx, int y, int dz) {
        return isOpen(cells, dx + 1, y, dz) || isOpen(cells, dx - 1, y, dz)
                || isOpen(cells, dx, y + 1, dz) || isOpen(cells, dx, y - 1, dz)
                || isOpen(cells, dx, y, dz + 1) || isOpen(cells, dx, y, dz - 1);
    }

    private static boolean isOpen(byte[] cells, int dx, int y, int dz) {
        return inWindow(dx, y, dz) && cells[index(dx, y, dz)] == OPEN;
    }

    private static boolean inWindow(int dx, int y, int dz) {
        return dx >= -MARGIN && dx < 16 + MARGIN && dz >= -MARGIN && dz < 16 + MARGIN
                && y >= 0 && y <= BAND_TOP;
    }

    private static int plane(int dx, int dz) {
        return (dx + MARGIN) * SPAN + (dz + MARGIN);
    }

    private static int index(int dx, int y, int dz) {
        return plane(dx, dz) * HEIGHT + y;
    }
}
