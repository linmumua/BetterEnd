package dev.mumu.betterend.vanilla;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令适配层的纯逻辑：端口未接线时不提供 token、不宣称写入；只有服务给出非空 token 才提示 confirm。
 *
 * <p>真正的清理判定与写世界都在 {@link EndPodiumRemoval}（central 的服务）里，这里只钉命令层的
 * 「不 pretend write」边界，不假装覆盖服务行为。
 */
class EndLayoutUpgradeCommandTest {

    /** 未接线端口：预览给不出 token，执行一律 accepted=false 并说明「一格未写」。 */
    @Test
    void unwiredServiceNeverOffersATokenAndNeverWrites() {
        EndLayoutUpgradeCommand.Service service =
                EndLayoutUpgradeCommand.Service.notWired("remove", "中央旧楼清理服务尚未接线");

        AtomicReference<EndLayoutUpgradeCommand.Preview> preview = new AtomicReference<>();
        service.preview(null, preview::set);

        assertNull(preview.get().token(), "未接线时不能给出可执行 token");
        assertFalse(preview.get().executable(), "没有 token 就不该提示 confirm");
        assertEquals("UNWIRED", preview.get().status());
        assertTrue(preview.get().lines().stream().anyMatch(line -> line.contains("尚未接线")),
                preview.get().lines().toString());
        assertTrue(preview.get().lines().stream().anyMatch(line -> line.contains("不会写世界")),
                preview.get().lines().toString());

        AtomicReference<EndLayoutUpgradeCommand.Result> result = new AtomicReference<>();
        service.apply(null, "any-token", result::set);

        assertFalse(result.get().accepted(), "未接线时执行必须被拒绝");
        assertTrue(result.get().detail().contains("一格未写"), result.get().detail());
        assertTrue(result.get().zeroWrite(), "未接线端口绝不写，必须能声称零写");
        assertEquals(0, result.get().written());
        assertNull(result.get().backup());
    }

    /**
     * 「写阶段失败」不能被当成零写：5 参构造默认 {@code zeroWrite=false}，只有预检拒绝才显式置 true。
     * 这条在命令层决定提示「一格未写」还是「部分方块可能已变更」。
     */
    @Test
    void writeFailureIsNeverReportedAsZeroWrite() {
        EndLayoutUpgradeCommand.Result writeFailed =
                new EndLayoutUpgradeCommand.Result(false, "WRITE_FAILED —— 第 3 桶失败", null, 2048, 512);
        assertFalse(writeFailed.accepted());
        assertFalse(writeFailed.zeroWrite(), "已经进入写阶段的失败不能声称零写");

        EndLayoutUpgradeCommand.Result tokenRejected =
                new EndLayoutUpgradeCommand.Result(false, "TOKEN_REJECTED", null, 0, 0, true);
        assertTrue(tokenRejected.zeroWrite(), "预检阶段的拒绝才是零写");
    }

    /** 只有非空 token 才算「可执行」；空白 token 与 null 一样不可执行。 */
    @Test
    void previewIsExecutableOnlyWithANonBlankToken() {
        assertFalse(new EndLayoutUpgradeCommand.Preview("OK", null, List.of(), null).executable());
        assertFalse(new EndLayoutUpgradeCommand.Preview("OK", "", List.of(), null).executable());
        assertFalse(new EndLayoutUpgradeCommand.Preview("OK", "   ", List.of(), null).executable());
        assertTrue(new EndLayoutUpgradeCommand.Preview("OK", "abc123", List.of(), null).executable());
    }

    /**
     * 只有**确定发生在写之前**的核验失败才算零写。
     *
     * <p>{@code SCHEDULE_FAILED} 可能是「前几个桶已经写完、后面的桶排不上」；{@code CONFLICT} 可能是在
     * 写前重核中途发现冲突。两者都必须走「未完成、部分方块可能已变更」，不能假称零写。
     */
    @Test
    void onlyPreWriteRefusalsCountAsZeroWrite() {
        for (String status : List.of("NOT_MANAGED", "NO_LAYOUT", "LAYOUT_UNREADABLE", "SEED_MISMATCH",
                "SNAPSHOT_MISSING", "SNAPSHOT_MISMATCH", "NOTHING_TO_REMOVE", "TOKEN_REJECTED")) {
            assertTrue(EndLayoutUpgradeCommand.zeroWriteStatus(status), status + " 是写前核验失败，可声称零写");
        }
        for (String status : List.of("SCHEDULE_FAILED", "CONFLICT", "WRITE_FAILED", "OK", "UNKNOWN", "")) {
            assertFalse(EndLayoutUpgradeCommand.zeroWriteStatus(status), status + " 不能假称零写");
        }
        assertFalse(EndLayoutUpgradeCommand.zeroWriteStatus(null), "没有状态名就更不能声称零写");
    }

    /** 端口名决定 confirm 命令的字面量：remove 与 restore 不能互相冒充。 */
    @Test
    void serviceNameDrivesTheConfirmCommand() {
        assertEquals("remove",
                EndLayoutUpgradeCommand.Service.notWired("remove", "x").name());
        assertEquals("restore",
                EndLayoutUpgradeCommand.Service.notWired("restore", "x").name());
    }

    /** 预览的行列表不可变：服务给什么就打印什么，命令层不改写。 */
    @Test
    void previewLinesAreAnImmutableCopy() {
        List<String> source = new java.util.ArrayList<>(List.of("a"));
        EndLayoutUpgradeCommand.Preview preview =
                new EndLayoutUpgradeCommand.Preview("OK", "t", source, null);
        source.add("b");
        assertEquals(List.of("a"), preview.lines());
    }
}
