import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Offline codec probe for the DnT End Castle datapack.
 *
 * Performs ONLY public static registration bootstrapping
 * ({@code SharedConstants.tryDetectVersion()} + {@code Bootstrap.bootStrap()}); it never
 * starts a server, a world, a network service or a plugin. Each case is a JSON document
 * run through a real Minecraft Codec with plain JsonOps.
 *
 * Protocol (all files UTF-8):
 *   args[0] = request JSON path
 *   args[1] = result  JSON path
 *
 * request : {"cases":[{"id":String,"codec":String,"json":String}]}
 * result  : {"bootstrapped":bool,"bootstrapError":String?,"codecs":{name:bool},
 *            "results":[{"id":String,"status":"ok"|"error"|"exception"|"nocodec",
 *                        "message":String?}]}
 */
public final class DntCodecProbe {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        // Keep diagnostics readable: force UTF-8 stdout before Bootstrap captures System.out.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        if (args.length < 2) {
            System.err.println("usage: DntCodecProbe <request.json> <result.json>");
            System.exit(2);
        }
        Path requestPath = Paths.get(args[0]);
        Path resultPath = Paths.get(args[1]);

        JsonObject request = JsonParser
                .parseString(new String(Files.readAllBytes(requestPath), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray cases = request.has("cases") ? request.getAsJsonArray("cases") : new JsonArray();

        JsonObject out = new JsonObject();

        String bootstrapError = null;
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable t) {
            bootstrapError = describe(t);
        }
        out.addProperty("bootstrapped", bootstrapError == null);
        if (bootstrapError != null) {
            out.addProperty("bootstrapError", bootstrapError);
        }

        Map<String, Codec<?>> codecs = new LinkedHashMap<>();
        JsonObject codecStatus = new JsonObject();
        loadCodec(codecs, codecStatus, "LOOT_FUNCTION_TYPED",
                () -> net.minecraft.world.level.storage.loot.functions.LootItemFunctions.TYPED_CODEC);
        loadCodec(codecs, codecStatus, "CONTEXT_INT",
                () -> net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders.DIRECT_CODEC);
        loadCodec(codecs, codecStatus, "CONTEXT_FLOAT",
                () -> net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProviders.DIRECT_CODEC);
        loadCodec(codecs, codecStatus, "DATA_COMPONENT_PATCH",
                () -> net.minecraft.core.component.DataComponentPatch.CODEC);
        loadCodec(codecs, codecStatus, "LOOT_TABLE_DIRECT",
                () -> net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC);
        // --- worldgen (jigsaw) codecs: used by the echoes pack gate -----------------------------
        // StructureTemplatePool.DIRECT_CODEC: elements[].element -> StructurePoolElement.CODEC,
        // elements[].weight, fallback. Its `fallback`/`processors` holders need a registry view,
        // which plain JsonOps does NOT provide, so this case is only meaningful when the document
        // keeps holders out of the way (see the echoes gate: it gates the element/processor
        // sub-documents through the registry-free codecs below instead).
        loadCodec(codecs, codecStatus, "TEMPLATE_POOL_DIRECT",
                () -> net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.DIRECT_CODEC);
        // StructureProcessorType.DIRECT_CODEC: the inline processor-list form, i.e. exactly the
        // {"processors":[...]} object a template_pool element carries. Dispatches on
        // `processor_type` through BuiltInRegistries.STRUCTURE_PROCESSOR (built-in, no registry
        // ops needed), so this decodes BlockIgnoreProcessor.blocks -> BlockState.CODEC for real.
        loadCodec(codecs, codecStatus, "PROCESSOR_LIST_DIRECT",
                () -> net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC);
        // StructurePoolElement.CODEC: one `element` object (single_pool_element / list / feature ...).
        loadCodec(codecs, codecStatus, "POOL_ELEMENT_DIRECT",
                () -> net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement.CODEC);
        // Structure.DIRECT_CODEC: one worldgen/structure document, dispatched on `type`.
        loadCodec(codecs, codecStatus, "STRUCTURE_DIRECT",
                () -> net.minecraft.world.level.levelgen.structure.Structure.DIRECT_CODEC);
        // StructureSet.DIRECT_CODEC: one worldgen/structure_set document.
        loadCodec(codecs, codecStatus, "STRUCTURE_SET_DIRECT",
                () -> net.minecraft.world.level.levelgen.structure.StructureSet.DIRECT_CODEC);
        // BlockState.CODEC: the exact codec BlockIgnoreProcessor.blocks elements are validated with.
        loadCodec(codecs, codecStatus, "BLOCK_STATE",
                () -> net.minecraft.world.level.block.state.BlockState.CODEC);
        // StructurePlacement.CODEC: the structure_set `placement` object the echoes generator rewrites
        // (spacing/separation/salt). Dispatches through BuiltInRegistries.STRUCTURE_PLACEMENT, so it
        // decodes without a registry view.
        loadCodec(codecs, codecStatus, "STRUCTURE_PLACEMENT",
                () -> net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC);
        // HeightProvider.CODEC: the structure `start_height` value. Either(VerticalAnchor, dispatch on
        // BuiltInRegistries.HEIGHT_PROVIDER_TYPE), so the inline {"absolute": 0} form decodes offline.
        loadCodec(codecs, codecStatus, "HEIGHT_PROVIDER",
                () -> net.minecraft.world.level.levelgen.heightproviders.HeightProvider.CODEC);
        out.add("codecs", codecStatus);

        JsonArray results = new JsonArray();
        for (JsonElement element : cases) {
            JsonObject c = element.getAsJsonObject();
            String id = c.get("id").getAsString();
            String codecName = c.get("codec").getAsString();
            JsonObject r = new JsonObject();
            r.addProperty("id", id);

            Codec<?> codec = codecs.get(codecName);
            if (codec == null) {
                r.addProperty("status", "nocodec");
                r.addProperty("message", "codec not available: " + codecName);
                results.add(r);
                continue;
            }
            try {
                JsonElement input = JsonParser.parseString(c.get("json").getAsString());
                DataResult<?> dr = codec.parse(JsonOps.INSTANCE, input);
                Optional<?> ok = dr.result();
                if (ok.isPresent()) {
                    r.addProperty("status", "ok");
                } else {
                    r.addProperty("status", "error");
                    r.addProperty("message", dr.error().isPresent()
                            ? dr.error().get().message() : "unknown error");
                }
            } catch (Throwable t) {
                r.addProperty("status", "exception");
                r.addProperty("message", describe(t));
            }
            results.add(r);
        }
        out.add("results", results);

        Files.write(resultPath, GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
    }

    private interface CodecSupplier {
        Codec<?> get();
    }

    private static void loadCodec(Map<String, Codec<?>> codecs, JsonObject status,
                                  String name, CodecSupplier supplier) {
        try {
            Codec<?> codec = supplier.get();
            codecs.put(name, codec);
            status.addProperty(name, true);
        } catch (Throwable t) {
            status.addProperty(name, false);
            status.addProperty(name + "_error", describe(t));
        }
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        String msg = t.getMessage();
        if (msg != null) {
            sb.append(": ").append(msg);
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(" <- ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        return sb.toString();
    }
}
