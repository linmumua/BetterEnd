package dev.mumu.betterend.flora;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import dev.mumu.betterend.flora.Flora.Prop;
import dev.mumu.betterend.flora.Flora.Row;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every block state {@link Flora#ROWS} can write, resolved through CraftEngine exactly once, here.
 * <p>
 * Contract 5, the same shape {@code TreePopulator.resolve:264-272} uses: a row whose id, property
 * or property value the pack does not define is dropped for the life of the populator with a
 * single log line naming what was missing. Never substituted with another block, never thrown -
 * an unresolved plant must cost that one row, not the world.
 * <p>
 * Immutable after construction and read-only from every worldgen worker.
 */
public final class FloraPalette {
    private static final Logger LOG = Logger.getLogger(FloraPalette.class.getName());

    /** Keyed {@code "<id>#<value>"}, with {@code value} the empty string for a plain block. */
    private final Map<String, BlockData> states = new HashMap<>();
    private final List<Row> live = new ArrayList<>();

    public FloraPalette() {
        this(Flora.ROWS);
    }

    FloraPalette(List<Row> rows) {
        Map<String, BlockDefinition> definitions = new HashMap<>();
        for (Row row : rows) {
            String missing;
            try {
                missing = resolveRow(row, definitions);
            } catch (RuntimeException | LinkageError e) {
                // Contract 5 covers the whole resolution, not just the id lookup: a pack whose
                // block state cannot be built must cost that one row, not the world. Anything
                // thrown here would otherwise escape getDefaultPopulators and refuse the world.
                LOG.log(Level.WARNING, "BetterEnd: flora row " + row.name()
                        + " disabled, CraftEngine could not build its states", e);
                missing = "a usable block state";
            }
            if (missing == null) {
                live.add(row);
            } else {
                LOG.log(Level.INFO, "BetterEnd: flora row {0} disabled, CraftEngine has no {1}",
                        new Object[]{row.name(), missing});
            }
        }
    }

    /** @return null if the whole row resolved, else the first thing the pack does not define. */
    private String resolveRow(Row row, Map<String, BlockDefinition> definitions) {
        for (int i = 0; i < row.ids().size(); i++) {
            String id = "betterend:" + row.ids().get(i);
            BlockDefinition definition =
                    definitions.computeIfAbsent(id, key -> CraftEngineBlocks.byId(Key.of(key)));
            if (definition == null) {
                return id;
            }
            Prop prop = row.props().get(i);
            if (prop == Prop.NONE) {
                states.putIfAbsent(id + "#", data(definition.defaultState()));
                continue;
            }
            Property<?> property = definition.getProperty(prop.property());
            if (property == null) {
                return id + " property " + prop.property();
            }
            for (String value : prop.stateValues()) {
                Optional<?> parsed = property.optional(value);
                if (parsed.isEmpty()) {
                    return id + " " + prop.property() + "=" + value;
                }
                states.putIfAbsent(id + "#" + value, data(with(definition, property, parsed.get())));
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ImmutableBlockState with(BlockDefinition definition, Property<?> property,
                                            Object value) {
        return ImmutableBlockState.with(definition.defaultState(), (Property) property, value);
    }

    private static BlockData data(ImmutableBlockState state) {
        return CraftEngineBlocks.getBukkitBlockData(state);
    }

    /** The rows whose every id, property and value the pack defines, in table order. */
    public List<Row> live() {
        return live;
    }

    /** Never null for a row in {@link #live()}; the planner only ever emits declared values. */
    public BlockData get(Row row, int idIndex, String value) {
        return states.get("betterend:" + row.ids().get(idIndex) + "#" + (value == null ? "" : value));
    }

    /**
     * Does the pack define this block id? The same question
     * {@code DustWastelandsGenerator}'s constructor asks before calling
     * {@code BiomeSurface.resolve}, so the flora layer's surface oracle degrades identically to
     * the generator that wrote the surface.
     */
    public boolean defined(String id) {
        if (id.startsWith("minecraft:")) {
            return Material.matchMaterial(id) != null;
        }
        try {
            return CraftEngineBlocks.byId(Key.of(id)) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
