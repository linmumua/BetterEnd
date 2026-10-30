package dev.mumu.betterend.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one YAML entry point for BetterEnd.
 * <p>
 * Every config the plugin owns (config.yml, messages.yml) is read through here instead of Bukkit's
 * {@code YamlConfiguration}, and the read is deliberately hostile to the tricks that make an
 * untrusted YAML file dangerous:
 * <ul>
 *   <li>{@code setAllowDuplicateKeys(false)} -- a duplicate key is an error, not a silent "last one
 *       wins". Two {@code layers:} blocks in config.yml must never quietly drop the first.</li>
 *   <li>{@code setAllowRecursiveKeys(false)} and an alias ceiling -- a billion-laughs or
 *       self-referential <em>key</em> anchor cannot make the loader allocate without bound.</li>
 *   <li>{@code setNestingDepthLimit} and {@code setCodePointLimit} -- a deep or large document is
 *       rejected at a fixed cost, so a bad file cannot exhaust the heap before we ever see it.</li>
 * </ul>
 * A self-referential <em>value</em> alias (e.g. {@code a: &x [*x]}) is a separate hazard that
 * {@code setAllowRecursiveKeys} does NOT cover: the loader happily builds a cycle, and walking it
 * would recurse forever. {@link #normalise} therefore tracks node identity and refuses a cycle rather
 * than trusting the loader option.
 * <p>
 * Everything that leaves this class is a plain {@code Map<String,Object>}/{@code List}/{@code String}
 * /number/boolean tree -- {@link SafeConstructor} instantiates no arbitrary classes, so a
 * {@code !!java.net.URL} tag in the file is refused rather than executed.
 * <p>
 * Plain Java, so it unit-tests without a server; snakeyaml arrives with paper-api, which the server
 * ships anyway.
 */
public final class SafeYaml {
    /** Bound on {@code *anchor} references expanded per document. */
    public static final int MAX_ALIASES = 32;
    /** Bound on nesting; real configs are five deep at most. */
    public static final int MAX_DEPTH = 32;
    /** Bound on document size, in code points. 4 MiB of YAML is orders of magnitude past any config. */
    public static final int MAX_CODE_POINTS = 4 * 1024 * 1024;

    private SafeYaml() {
    }

    /** A loader that refuses the shapes above. One per call: a {@link Yaml} keeps parse state. */
    public static Yaml loader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_CODE_POINTS);
        return new Yaml(new SafeConstructor(options));
    }

    /**
     * Reads one YAML document into a normalised tree.
     *
     * @param input the stream to read; not closed here, the caller owns it
     * @return the root mapping, or an empty map for an empty document; never null
     * @throws IllegalArgumentException if the root is not a mapping, or a value alias is recursive
     */
    public static Map<String, Object> load(InputStream input) {
        return asRoot(loader().load(input));
    }

    /** Reads a YAML document already in memory; the same rules and the same result type. */
    public static Map<String, Object> load(String document) {
        return asRoot(loader().load(document));
    }

    /**
     * Reads a file, closing the stream. Preferred over {@link #load(InputStream)} for files, so a
     * caller cannot leak the handle by forgetting the try-with-resources.
     */
    public static Map<String, Object> load(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return load(input);
        }
    }

    private static Map<String, Object> asRoot(Object root) {
        if (root == null) {
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("YAML 根节点必须是映射（mapping），实际是 " + root.getClass().getSimpleName());
        }
        return normalise(map);
    }

    /** Returns a mapping with String keys and normalised values, refusing a cyclic alias graph. */
    static Map<String, Object> normalise(Map<?, ?> map) {
        return normalise(map, new IdentityHashMap<>());
    }

    private static Map<String, Object> normalise(Map<?, ?> map, IdentityHashMap<Object, Boolean> inProgress) {
        // Identity, not equals: two structurally identical sibling maps are fine, the SAME map reached
        // twice down one chain is a cycle.
        if (inProgress.put(map, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("YAML 中存在递归别名引用（value alias cycle），已拒绝解析。");
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), normaliseValue(entry.getValue(), inProgress));
            }
            return out;
        } finally {
            inProgress.remove(map);
        }
    }

    private static Object normaliseValue(Object value, IdentityHashMap<Object, Boolean> inProgress) {
        if (value instanceof Map<?, ?> nested) {
            return normalise(nested, inProgress);
        }
        if (value instanceof List<?> list) {
            if (inProgress.put(list, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("YAML 中存在递归别名引用（value alias cycle），已拒绝解析。");
            }
            try {
                List<Object> out = new ArrayList<>(list.size());
                for (Object element : list) {
                    out.add(normaliseValue(element, inProgress));
                }
                return out;
            } finally {
                inProgress.remove(list);
            }
        }
        return value;
    }

    /**
     * Flattens a nested mapping into dotted keys, the shape {@link BetterEndConfig#load} reads.
     * A section (a nested map) produces no key of its own, only its leaves, so an empty section is
     * indistinguishable from an absent one -- which is what the loader wants.
     */
    public static Map<String, Object> flatten(Map<String, Object> root) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flattenInto(root, "", flat);
        return flat;
    }

    private static void flattenInto(Map<String, Object> node, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flattenInto(normalise(child), key, out);
            } else {
                out.put(key, entry.getValue());
            }
        }
    }

    /**
     * Renders a normalised tree back to YAML in a stable, human-readable form: block style (never the
     * flow {@code {a: 1}} shorthand, which depends on content), two-space indent, non-ASCII kept as
     * literal UTF-8 so Chinese text stays readable. Comments are not preserved -- see {@link ConfigStore}.
     */
    public static String dump(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setIndent(2);
        return new Yaml(options).dump(root);
    }
}
