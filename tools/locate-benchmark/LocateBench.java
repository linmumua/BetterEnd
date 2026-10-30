import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * 独立的 /locate 与密度列采样基准：同一台机器上对照两个 BetterEnd 构建产物。
 *
 * <p>不复制任何算法，只通过每个产物自己的真实公开接口取数：
 * {@code BiomePlacement.bind/isLand/at/quartAt/ring/caveBiomeAt/allowsCarvers/terrainHeightAt}
 * 与 {@code IslandField.topY/densityColumn/isSolid}（若存在则再加 {@code anySolidOnLattice}，
 * 以及 {@code densityColumnInto} —— 调用方持有缓冲的那个重载）。
 *
 * <p>隔离与公平性：
 * <ul>
 *   <li>每个 jar 一个 {@link URLClassLoader}，父加载器是 platform classloader —— 只可见 JDK。
 *       不需要 Bukkit、不 mock 世界、不启服，两个 jar 的同名类彻底隔离。</li>
 *   <li>两侧在<b>同一进程里按轮交错</b>测量（奇数轮 AB、偶数轮 BA）。串行跑两个独立 JVM 时
 *       先跑的一侧会系统性占便宜（机器在漂移：别的构建/编译同时在跑、频率与缓存状态在变），
 *       交错能让两侧承受同样的环境噪声。</li>
 *   <li>每次计时前两侧都做同样的 {@code System.gc()}，把"算法差异"和"GC 相位差异"分开。</li>
 * </ul>
 *
 * <p>冷/热的定义（关键，否则容易把命中缓存误读成算法变快）：
 * <ul>
 *   <li>warmup：先跑一段与测量坐标无关的探测，把类加载和 JIT 拉到稳态；</li>
 *   <li><b>cold</b>：新建 {@code BiomePlacement} 实例后的第一遍采样，memo（plane /
 *       columnHeight / landCache）全空 —— 这是 /locate 的真实形态：每列都是第一次问；</li>
 *   <li><b>warm</b>：同实例、同一批坐标预热后重复，即"memo 全命中"的稳态上界。</li>
 * </ul>
 *
 * <p>报告样本数、每样本微秒（中位数）与 (max-min)/median 的波动幅度，以及两侧最快一遍之比；
 * 不设任何"必须快多少"的脆弱阈值。返回值逐项滚动校验和，两侧必须完全相同。
 *
 * <p>除了耗时，本工具还量三件与"只改成本、不改结果"直接对应的事：
 * <ol>
 *   <li><b>分配字节/样本</b>：用 {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}
 *       数当前线程的分配量（反射取，非 HotSpot 上打印 n/a）。{@code densityColumn} 每列 new 一个
 *       double[]，{@code densityColumnInto} 复用调用方缓冲 —— 这一列才是缓冲复用是否真的生效的证据，
 *       耗时列会被 JIT 与噪声遮住。</li>
 *   <li><b>{@code densityColumnInto} 语义对照</b>：候选侧每次调用前把 scratch 填满哨兵值，
 *       结果仍必须与基准侧 {@code densityColumn} 全新数组的校验和逐位相同。只写"非零项"、
 *       或漏掉边界 y 的实现会在这里露馅。</li>
 *   <li><b>1 线程 ↔ N 线程等值</b>：同一批坐标切成 N 片，每片独立实例、独立 scratch，
 *       分布式执行与串行执行的合并校验和必须相同。切法固定，所以两种执行方式的合并次序一致，
 *       差异只可能来自算法本身对执行顺序的依赖。</li>
 * </ol>
 *
 * <p>本工具<b>不</b>测整服 MSPT/TPS：它测的是纯函数单次调用的成本与分配量，无 Bukkit、无世界、
 * 无区块生成、无调度线程。这里的数字不能当成"全服性能"或"生成速度"来引用。
 *
 * <p>冷/热两档不要混比：{@code -cold} 行只与另一侧的 {@code -cold} 行比，{@code -warm} 只与
 * {@code -warm} 比。冷档每遍都新建实例（memo 全空），热档是 memo 全命中的稳态上界；
 * 把两者放一起看会得出"cache 让算法变快"这种结论，而那是测量形态差异，不是算法差异。
 *
 * <p>用法（工作区根目录，JDK 17+ 单文件源码模式，不产生 class 文件）：
 * <pre>
 *   # 工具自校验：基线对基线，所有校验必须一致（候选尚未构建时先跑这个）
 *   java tools/locate-benchmark/LocateBench.java --self-check --quick
 *   # 正式对照：候选必须显式给出，工具不猜版本号
 *   java tools/locate-benchmark/LocateBench.java \
 *        --baseline jars/BetterEnd-0.1.13-folia26.3.jar \
 *        --candidate jars/BetterEnd-0.1.14-folia26.3.jar
 * </pre>
 *
 * <p>退出码：0 = 两侧都跑完且所有校验（冷/热返回值、{@code densityColumnInto} 语义、
 * 1↔N 线程等值）一致；3 = 有一致性问题（语义回归，先查语义再看耗时）；2 = 用法错误、jar 缺失、
 * 或候选里 {@code densityColumnInto} 的签名无法识别（工具不猜，也绝不对跑不通的候选报成功）。
 */
public final class LocateBench {

    /**
     * 当前已交付的产物，作为默认对照基准。换基线时改这一行，或者传 {@code --baseline}。
     * 候选<b>没有</b>默认值：下一个版本号是构建的结果，工具不猜。
     */
    static final String DEFAULT_BASELINE = "jars/BetterEnd-0.1.13-folia26.3.jar";

    /** 冷测量轮数：每轮两侧各一遍，奇数轮先 A 后 B、偶数轮先 B 后 A。 */
    static final int COLD_ROUNDS = 5;
    /** 热测量轮数（每轮每侧已经是一段 >=100ms 的重复块）。 */
    static final int WARM_ROUNDS = 3;
    /** 热测量每轮的目标块长度：太短会被计时器分辨率吃掉。 */
    static final long WARM_BLOCK_NANOS = 100_000_000L;
    /** {@code --quick} 用的一轮块长：只为连通性冒烟，不用于下结论。 */
    static final long QUICK_BLOCK_NANOS = 20_000_000L;

    /** 密度列的采样窗口 {@code [0, 127]}：128 个 double，与 isLand 的 land 窗口一致。 */
    static final int DENSITY_MIN_Y = 0;
    static final int DENSITY_MAX_Y = 127;
    static final int DENSITY_LEN = DENSITY_MAX_Y - DENSITY_MIN_Y + 1;

    public static void main(String[] argv) throws Throwable {
        System.exit(runSuite(Args.parse(argv)));
    }

    // ---------------------------------------------------------------- 参数

    static final class Args {
        String baseline = DEFAULT_BASELINE;
        /** 未给出时为 null：非 --self-check 下必填，见 {@link #resolveCandidate}。 */
        String candidate;
        long seed = 0x5EEDL;
        /** 少跑几轮，只求冒烟：校验和不变，耗时统计不可用于结论。 */
        boolean quick;
        /** 1↔N 等值校验的线程数；<=1 表示跳过该项。 */
        int parityThreads = 4;
        /** 自校验：候选 = 基准。放在解析末尾生效，这样 --self-check 与 --baseline 的先后顺序不影响结果。 */
        boolean selfCheck;

        int coldRounds() {
            return quick ? 2 : COLD_ROUNDS;
        }

        int warmRounds() {
            return quick ? 1 : WARM_ROUNDS;
        }

        long warmBlockNanos() {
            return quick ? QUICK_BLOCK_NANOS : WARM_BLOCK_NANOS;
        }

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--self-check" -> a.selfCheck = true;
                    case "--baseline" -> a.baseline = value(argv, ++i, "--baseline");
                    case "--candidate" -> a.candidate = value(argv, ++i, "--candidate");
                    case "--seed" -> a.seed = Long.decode(value(argv, ++i, "--seed"));
                    case "--quick" -> a.quick = true;
                    case "--parity-threads" ->
                            a.parityThreads = Integer.parseInt(value(argv, ++i, "--parity-threads"));
                    case "--no-parity" -> a.parityThreads = 1;
                    case "--help", "-h" -> usage("用法：");
                    default -> usage("未知参数: " + argv[i]);
                }
            }
            if (a.selfCheck) a.candidate = a.baseline;
            return a;
        }

        /**
         * 候选路径。{@code --self-check} 会把 candidate 预置成 baseline，所以这里只为
         * "既没给 --candidate 也没给 --self-check" 的情况报用法错误 —— 宁可让调用方多打一个路径，
         * 也不要让工具对某个猜出来的版本号报"通过"。
         */
        String resolveCandidate() {
            if (candidate == null) {
                usage("缺少 --candidate：候选 jar 必须显式给出（--self-check 时才可省略）。");
            }
            return candidate;
        }

        static String value(String[] argv, int i, String flag) {
            if (i >= argv.length) usage(flag + " 需要一个值");
            return argv[i];
        }

        static void usage(String msg) {
            System.out.println(msg);
            System.out.println("""
                    用法: java tools/locate-benchmark/LocateBench.java [选项]
                      --baseline  <jar>   对照基准（默认 %s）
                      --candidate <jar>   待测构建（必填，除非 --self-check）
                      --self-check        候选 = 基准（工具自校验，结果必须全同）
                      --seed      <n>     世界种子（默认 0x5EED）
                      --quick             少跑几轮，只做冒烟（耗时列不可用于结论）
                      --parity-threads <n>  线程等值校验的线程数（默认 4，1 = 跳过）
                      --no-parity         跳过线程等值校验
                    """.formatted(DEFAULT_BASELINE));
            System.exit(2);
        }
    }

    static void fail(String msg) {
        // 走 stdout：本机控制台是 GBK，System.err 的编码在不同启动方式下不一致，
        // 走 stdout 与报告正文一致，重定向到文件时也不会乱码。
        System.out.println(msg);
        System.out.flush();
        System.exit(2);
    }

    // ---------------------------------------------------------------- 编排

    static int runSuite(Args a) throws Exception {
        try {
            return runSuite0(a);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static int runSuite0(Args a) throws Throwable {
        Path baseline = Path.of(a.baseline);
        Path candidate = Path.of(a.resolveCandidate());
        for (Path p : List.of(baseline, candidate)) {
            if (!Files.isRegularFile(p)) {
                fail("缺少构建产物: " + p.toAbsolutePath()
                        + System.lineSeparator()
                        + "基准与待测 jar 都必须已存在；候选 jar 未构建时本工具不给出任何结论。"
                        + System.lineSeparator()
                        + availableJars()
                        + "只想验证工具本身请加 --self-check。");
            }
        }
        System.out.println("== BetterEnd 采样基准（locate 探针 + 密度列）==");
        System.out.println("seed      = " + a.seed);
        System.out.println("baseline  = " + describe(baseline));
        System.out.println("candidate = " + describe(candidate)
                + (candidate.equals(baseline) ? "  [与基准同一份：自校验]" : ""));

        Proxy base = new Proxy(baseline);
        Proxy cand = new Proxy(candidate);
        if (base.placementClass == cand.placementClass) {
            fail("两侧解析到了同一个类加载器，隔离失败");
        }
        Alloc.calibrate();
        System.out.println("隔离      = 两个独立 URLClassLoader（父加载器 = platform），无 Bukkit、无世界、不启服");
        System.out.println("测法      = 同进程按轮交错（AB/BA），冷 " + a.coldRounds() + " 轮、热 " + a.warmRounds()
                + " 轮，两侧样本量与坐标完全相同" + (a.quick ? "（--quick：只冒烟，耗时不作结论）" : ""));
        System.out.println("分配计数  = " + (Alloc.available()
                ? "com.sun.management.ThreadMXBean#getThreadAllocatedBytes（反射，本线程，已扣探测自身开销）"
                : "n/a（本 JDK 没有 getThreadAllocatedBytes）"));
        System.out.println("densityColumnInto = 基准侧 " + intoPresence(base) + " / 候选侧 " + intoPresence(cand));
        System.out.println("范围      = 纯函数调用成本与分配量；不测区块生成、不测整服 MSPT/TPS");
        System.out.println();

        Tally tally = new Tally();
        List<Row> rows = new ArrayList<>();
        for (Case c : cases(a.seed)) {
            rows.addAll(c.measure(base, cand, a));
        }
        printTable(rows, tally);
        crossCheckInto(base, cand, a, tally);
        parityCheck(base, cand, a, tally);
        return verdict(tally);
    }

    static String intoPresence(Proxy p) {
        if (p.densityInto != null && p.densityIntoForced != null) return "有（4 参与 5 参）";
        if (p.densityIntoForced != null) return "有（仅 5 参）";
        if (p.densityInto != null) return "有（仅 4 参）";
        return "无";
    }

    /** jar 缺失时把候选清单打出来，省得调用方去猜文件名（版本号是构建产出的，不是猜的）。 */
    static String availableJars() {
        Path dir = Path.of("jars");
        if (!Files.isDirectory(dir)) return "";
        try (var stream = Files.list(dir)) {
            List<String> names = stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .map(p -> p.getFileName().toString()).sorted().toList();
            if (names.isEmpty()) return "";
            return "jars/ 里现有的产物: " + names + System.lineSeparator();
        } catch (Exception e) {
            return "";
        }
    }

    static String describe(Path jar) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
        return jar + "  (" + Files.size(jar) + " B, sha256:" + sb + "...)";
    }

    /**
     * 一个测量侧的一次测量结果。
     * <p>{@code samples} 是<b>单遍</b>样本数（两侧必然相同，用于对照）；{@code repeat} 是热测量把
     * 同一遍重复了多少次（两侧各自校准，允许不同，只参与微秒换算，不参与一致性判定）。
     */
    record Side(long samples, int repeat, long[] nanos, long checksum) {
        long min() {
            long m = Long.MAX_VALUE;
            for (long n : nanos) m = Math.min(m, n);
            return m;
        }

        long max() {
            long m = Long.MIN_VALUE;
            for (long n : nanos) m = Math.max(m, n);
            return m;
        }

        long median() {
            long[] s = nanos.clone();
            Arrays.sort(s);
            return s[s.length / 2];
        }

        double microsPerSample() {
            long total = samples * (long) repeat;
            return total <= 0 ? Double.NaN : median() / 1000.0 / total;
        }

        double microsPerSampleMin() {
            long total = samples * (long) repeat;
            return total <= 0 ? Double.NaN : min() / 1000.0 / total;
        }

        String spread() {
            long med = median();
            if (med <= 0) return "-";
            return String.format("%.1f%%", 100.0 * (max() - min()) / med);
        }
    }

    /**
     * 一个 workload 的对照结果；某一侧为 null 表示该侧没有这个入口（新增 API）。
     * <p>{@code baseBytes}/{@code candBytes} 是稳态每样本分配字节（NaN = 没能测到）。
     * 它是"缓冲复用有没有生效"的唯一直接证据：{@code densityColumn} 每列 new 一个 128 长度的
     * {@code double[]}（≈1 KiB），{@code densityColumnInto} 复用调用方缓冲应当接近 0。
     */
    record Row(String name, Side base, Side cand, double baseBytes, double candBytes) {
    }

    /** 一个 workload 用例：工厂函数按各自 Proxy 造出等价 workload。 */
    record Case(String name, boolean withWarm, Function<Proxy, Workload> factory) {

        List<Row> measure(Proxy a, Proxy b, Args opts) throws Throwable {
            Workload wa = factory.apply(a);
            Workload wb = factory.apply(b);
            if (wa == null && wb == null) return List.of();

            if (wa == null || wb == null) {
                // 只有一侧存在：新增公共入口。只测存在的那一侧，不参与一致性判定。
                Proxy p = (wa != null) ? a : b;
                Workload w = (wa != null) ? wa : wb;
                Side side = interleavedCold(null, null, p, w, opts).get(0);
                double bytes = allocOne(p, w, opts.seed, false);
                return List.of(new Row(name, (wa != null) ? side : null,
                        (wb != null) ? side : null,
                        (wa != null) ? bytes : Double.NaN,
                        (wb != null) ? bytes : Double.NaN));
            }

            List<Side> cold = interleavedCold(a, wa, b, wb, opts);
            Side ca = cold.get(0);
            Side cb = cold.get(1);
            double[] bytes = allocPair(a, wa, b, wb, opts.seed, false);
            List<Row> out = new ArrayList<>();
            out.add(new Row(name + "-cold", ca, cb, bytes[0], bytes[1]));
            if (!withWarm) return out;

            Side[] warm = interleavedWarm(a, wa, b, wb, opts);
            double[] warmBytes = allocPair(a, wa, b, wb, opts.seed, true);
            out.add(new Row(name + "-warm", warm[0], warm[1], warmBytes[0], warmBytes[1]));
            return out;
        }

        /**
         * 冷：AB/BA 交替 —— 奇数轮先 A 后 B、偶数轮先 B 后 A，两侧各测冷轮数遍。
         * 每一遍都是"新建实例后的第一遍"，也就是 memo 全空的真实首次查询成本。
         * 只有一侧存在该入口时，走单侧路径。
         */
        static List<Side> interleavedCold(Proxy a, Workload wa, Proxy b, Workload wb, Args opts)
                throws Throwable {
            int rounds = opts.coldRounds();
            if (a == null || b == null) {
                Proxy p = (a != null) ? a : b;
                Workload w = (a != null) ? wa : wb;
                long[] state = new long[3];       // {checksum, samples, seen}
                long[] nanos = new long[rounds];
                for (int r = 0; r < rounds; r++) {
                    long[] rec = coldOne(p, w, opts.seed);
                    nanos[r] = rec[1];
                    settleChecksum(state, rec);
                }
                return List.of(new Side(state[1], 1, nanos, state[0]));
            }

            long[] naiveA = new long[3];
            long[] naiveB = new long[3];
            long[] nanosA = new long[rounds];
            long[] nanosB = new long[rounds];
            for (int r = 0; r < rounds; r++) {
                if (r % 2 == 0) {
                    long[] ra = coldOne(a, wa, opts.seed);
                    long[] rb = coldOne(b, wb, opts.seed);
                    nanosA[r] = ra[1];
                    nanosB[r] = rb[1];
                    settleChecksum(naiveA, ra);
                    settleChecksum(naiveB, rb);
                } else {
                    long[] rb = coldOne(b, wb, opts.seed);
                    long[] ra = coldOne(a, wa, opts.seed);
                    nanosB[r] = rb[1];
                    nanosA[r] = ra[1];
                    settleChecksum(naiveB, rb);
                    settleChecksum(naiveA, ra);
                }
            }
            return List.of(new Side(naiveA[1], 1, nanosA, naiveA[0]),
                    new Side(naiveB[1], 1, nanosB, naiveB[0]));
        }

        /**
         * 冷的一遍：新建实例 + 让 workload 重建自己的 scratch + settle + 计时，
         * 返回 {checksum, nanos, samples}。
         * <p>{@code newPass()} 在计时之外调用：调用方自带缓冲的 API，其缓冲属于夹具，不属于算法。
         * 这样冷档比较的是"两种 API 各自的一次完整查询"，而缓冲是否复用由分配列单独给出，
         * 不会把"夹具提前分配"记成"算法变快"。
         */
        static long[] coldOne(Proxy p, Workload w, long seed) throws Throwable {
            long[] c = new long[1];
            Object inst = p.placement(seed);
            w.newPass();
            settle();
            long t0 = System.nanoTime();
            long sum = w.run(inst, c);
            return new long[]{sum, System.nanoTime() - t0, c[0]};
        }

        /** 同一侧的校验和必须每一遍都相同；否则说明该 jar 内部就不是纯函数。 */
        static void settleChecksum(long[] state, long[] rec) {
            if (state[2] == 0) {
                state[0] = rec[0];
                state[1] = rec[2];
                state[2] = 1;
            } else if (rec[0] != state[0] || rec[2] != state[1]) {
                fail("同一侧同一 jar 内冷测量各遍结果不一致（不该发生）");
            }
        }

        /** 热：两侧同实例预热后，按轮交替计时（AB / BA），每块重复到目标块长。 */
        static Side[] interleavedWarm(Proxy a, Workload wa, Proxy b, Workload wb, Args opts)
                throws Throwable {
            long[] ca = new long[1];
            Object instA = a.placement(opts.seed);
            wa.newPass();
            long ckA = wa.run(instA, ca);
            long samplesA = ca[0];

            long[] cb = new long[1];
            Object instB = b.placement(opts.seed);
            wb.newPass();
            long ckB = wb.run(instB, cb);
            long samplesB = cb[0];

            settle();
            int repeatA = calibrate(wa, instA, opts.warmBlockNanos());
            int repeatB = calibrate(wb, instB, opts.warmBlockNanos());

            int rounds = opts.warmRounds();
            long[] nanosA = new long[rounds];
            long[] nanosB = new long[rounds];
            for (int round = 0; round < rounds; round++) {
                boolean aFirst = (round % 2 == 0);
                if (aFirst) {
                    nanosA[round] = warmBlock(wa, instA, repeatA);
                    nanosB[round] = warmBlock(wb, instB, repeatB);
                } else {
                    nanosB[round] = warmBlock(wb, instB, repeatB);
                    nanosA[round] = warmBlock(wa, instA, repeatA);
                }
            }
            return new Side[]{new Side(samplesA, repeatA, nanosA, ckA),
                    new Side(samplesB, repeatB, nanosB, ckB)};
        }

        /**
         * 用 3 次探测里最快的一次来定 repeat：单次探测常常撞上 JIT 还在编译/OS 调度，
         * 会把 repeat 算得过大，块长反而不稳定。
         */
        static int calibrate(Workload w, Object inst, long blockNanos) throws Throwable {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                settle();
                long t0 = System.nanoTime();
                w.run(inst, new long[1]);
                best = Math.min(best, System.nanoTime() - t0);
            }
            best = Math.max(1, best);
            return (int) Math.max(1, Math.min(20_000, blockNanos / best));
        }

        static long warmBlock(Workload w, Object inst, int repeat) throws Throwable {
            settle();
            long t0 = System.nanoTime();
            for (int r = 0; r < repeat; r++) w.run(inst, new long[1]);
            return System.nanoTime() - t0;
        }
    }

    /**
     * 每次计时前统一清一次垃圾。densityColumn 每列 new 一个 128 长度的 double[]，
     * 两次测量之间残留的垃圾量会直接决定这一遍是否踩到 GC。
     * <p>注意：这个 {@code System.gc()} 是测量夹具的一部分，它的成本落在计时区间之外；
     * 所以冷档与热档各自成行、各自对照，不能把两侧或两档的样本混在一起算。
     */
    static void settle() {
        System.gc();
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 分配探针

    /**
     * 当前线程的累计分配字节数。用反射取 {@code com.sun.management.ThreadMXBean}，
     * 这样在非 HotSpot JDK 上只是"测不到"，不会编译期就绑死。
     * <p>这是<b>分配计数</b>，不是耗时：它不受 GC 影响，正好用来证明"缓冲复用"是否真的生效
     * （复用的实现每样本接近 0 字节，每次 new 数组的实现是 ~1 KiB/列）。
     */
    static final class Alloc {
        static final Object bean;
        static final MethodHandle usedOn;
        /** 两次取值之间的固定开销；启动时标定，算每样本字节时扣掉。 */
        static long overhead = -1;

        static {
            Object b = null;
            MethodHandle h = null;
            try {
                Class<?> iface = Class.forName("com.sun.management.ThreadMXBean");
                java.lang.management.ThreadMXBean bt =
                        java.lang.management.ManagementFactory.getThreadMXBean();
                if (iface.isInstance(bt)) {
                    Method m = iface.getMethod("getThreadAllocatedBytes", long.class);
                    h = MethodHandles.publicLookup().unreflect(m)
                            .asType(MethodType.methodType(long.class, Object.class, long.class));
                    b = bt;
                }
            } catch (Throwable absent) {
                b = null;
                h = null;
            }
            bean = b;
            usedOn = h;
        }

        static boolean available() {
            return usedOn != null;
        }

        /** @return 本线程累计分配字节；测不到时 -1。 */
        // getId() 是 JDK 17 也有的写法；threadId() 要 21+，为兼容保留旧拼写。
        @SuppressWarnings("deprecation")
        static long used() {
            if (usedOn == null) return -1;
            try {
                return (long) usedOn.invokeExact(bean, Thread.currentThread().getId());
            } catch (Throwable t) {
                return -1;
            }
        }

        static void calibrate() {
            if (usedOn == null) return;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                long a = used();
                long b = used();
                if (a < 0 || b < 0) return;
                best = Math.min(best, b - a);
            }
            overhead = Math.max(0, best);
        }
    }

    /**
     * 稳态每样本分配字节。先预热一遍让类初始化/JIT 的分配落到区间之外，再量第二遍；
     * 两侧各量两遍取较小值（分配计数是精确的，取小只是排除偶发的膨胀）。
     */
    static double[] allocPair(Proxy a, Workload wa, Proxy b, Workload wb, long seed, boolean warm)
            throws Throwable {
        double a1 = allocOne(a, wa, seed, warm);
        double b1 = allocOne(b, wb, seed, warm);
        double a2 = allocOne(a, wa, seed, warm);
        double b2 = allocOne(b, wb, seed, warm);
        return new double[]{minNaN(a1, a2), minNaN(b1, b2)};
    }

    static double minNaN(double x, double y) {
        if (Double.isNaN(x)) return y;
        if (Double.isNaN(y)) return x;
        return Math.min(x, y);
    }

    static double allocOne(Proxy p, Workload w, long seed, boolean warm) throws Throwable {
        if (!Alloc.available()) return Double.NaN;
        long[] c = new long[1];
        Object prepared = p.placement(seed);
        w.newPass();
        w.run(prepared, c);
        Object inst = warm ? prepared : p.placement(seed);
        w.newPass();
        long before = Alloc.used();
        if (before < 0) return Double.NaN;
        w.run(inst, c);
        long after = Alloc.used();
        if (after < 0) return Double.NaN;
        return Math.max(0, after - before - Math.max(0, Alloc.overhead)) / (double) Math.max(1, c[0]);
    }

    // ---------------------------------------------------------------- 报告

    /** 统计"查了什么、哪几项不一致"，最后由 {@link #verdict} 汇总成一个退出码。 */
    static final class Tally {
        final List<String> mismatched = new ArrayList<>();
        final List<String> candidateOnly = new ArrayList<>();
        int compared;
        int intoChecked;
        int parityChecked;
    }

    static void printTable(List<Row> rows, Tally tally) {
        System.out.printf("%-38s %9s %5s %11s %11s %7s %8s %5s %9s %9s%n", "workload", "samples",
                "rep", "base us/n", "cand us/n", "cand/b", "base spr", "same", "base B/n",
                "cand B/n");
        System.out.println("-".repeat(126));

        for (Row row : rows) {
            if (row == null) continue;
            report(row, tally);
            if (row.cand() != null && row.base() != null) tally.compared++;
        }
        System.out.println("-".repeat(126));
        System.out.println("说明: *-cold = 每轮新建实例的第一遍（memo 全空，/locate 的真实形态）；"
                + "*-warm = memo 预热后的稳态重复。");
        System.out.println("      samples = 单遍样本数（两侧必然相同）；rep = 热测量把同一遍重复几次（"
                + "两侧各自校准，可不同）。");
        System.out.println("      us/n = 每样本微秒（中位数 / (samples*rep)）；校验和必须相同。");
        System.out.println("      cand/b 用两侧最快一遍（min）计算；base spr = (max-min)/median。");
        System.out.println("      B/n 按行区分首次查询与预热后查询的分配量；density 行每样本是一整列，其他行按各自查询计数。");
        System.out.println("      比值带 * 表示该行波动超过中位数 25% —— 噪声大，不要据它下结论。");
        System.out.println("      只在同一行内比较（cold 对 cold、warm 对 warm），不要跨行比较绝对耗时。");
        if (!tally.candidateOnly.isEmpty()) {
            System.out.println("      标 n/a 的行是候选侧新增的公共入口（基准侧没有），只示意、不参与一致性判定："
                    + tally.candidateOnly);
        }
        System.out.println();
    }

    static void report(Row row, Tally tally) {
        Side b = row.base();
        Side c = row.cand();
        String baseBytes = bytes(row.baseBytes());
        String candBytes = bytes(row.candBytes());
        if (b == null || c == null) {
            Side one = (b != null) ? b : c;
            tally.candidateOnly.add(row.name());
            System.out.printf("%-38s %9d %5d %11s %11.4f %7s %8s %5s %9s %9s%n", row.name(),
                    one.samples(), one.repeat(), "-", one.microsPerSample(), "-", one.spread(),
                    "n/a", baseBytes, candBytes);
            return;
        }
        boolean same = b.checksum() == c.checksum() && b.samples() == c.samples();
        if (!same) tally.mismatched.add(row.name());
        double ratio = b.microsPerSampleMin() <= 0 ? Double.NaN
                : c.microsPerSampleMin() / b.microsPerSampleMin();
        boolean shaky = (b.max() - b.min()) > 0.25 * b.median();
        System.out.printf("%-38s %9d %5d %11.4f %11.4f %7s %8s %5s %9s %9s%n", row.name(),
                b.samples(), b.repeat(), b.microsPerSample(), c.microsPerSample(),
                format(ratio) + (shaky ? "*" : ""), b.spread(), same ? "yes" : "NO",
                baseBytes, candBytes);
    }

    static String bytes(double perSample) {
        return Double.isNaN(perSample) ? "n/a" : String.format("%.0f", perSample);
    }

    static String format(double ratio) {
        return Double.isNaN(ratio) ? "-" : String.format("%.2f", ratio);
    }

    static int verdict(Tally tally) {
        if (tally.mismatched.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("RESULT: ").append(tally.compared).append(" 项对照一致");
            sb.append("（冷/热分档对照 ").append(tally.compared - tally.intoChecked
                    - tally.parityChecked).append(" 项");
            sb.append("；densityColumnInto 语义 ").append(tally.intoChecked == 0
                    ? "未测到该入口，跳过" : tally.intoChecked + " 项");
            sb.append("；1 线程与 N 线程等值 ").append(tally.parityChecked == 0
                    ? "跳过" : tally.parityChecked + " 项").append("）。");
            System.out.println(sb);
            System.out.println("        这是纯函数层的对照：不含区块生成、不含世界初始化、不含整服 MSPT/TPS。");
            return 0;
        }
        System.out.println("RESULT: 有一致性问题，项: " + tally.mismatched);
        System.out.println("        性能修复不应改变任何采样结果：先查语义，再看耗时。");
        return 3;
    }

    // ---------------------------------------------------------------- 反射门面（单 jar）

    static final class Proxy {
        final Class<?> placementClass;
        final MethodHandle bind;          // (long, Object) -> Object
        final Object defaults;
        final MethodHandle fieldOf;       // (Object) -> Object
        final MethodHandle isLand;        // (Object, int, int) -> boolean
        final MethodHandle at;            // (Object, int, int, int) -> Object
        final MethodHandle quartAt;       // (Object, int, int, int) -> Object
        final MethodHandle ring;          // (Object, int, int) -> Object
        final MethodHandle caveBiomeAt;   // (Object, int, int) -> Object
        final MethodHandle allowsCarvers; // (Object, int, int) -> boolean
        final MethodHandle terrainHeight; // (Object, int, int) -> double
        final MethodHandle topY;          // (Object, int, int) -> int
        final MethodHandle densityColumn; // (Object, int, int, int, int) -> double[]
        final MethodHandle densityForced; // (Object, int, int, int, int, float) -> double[]
        final MethodHandle isSolid;       // (Object, int, int, int) -> boolean
        /** 0.1.14 新增；0.1.13 没有，此时为 null。 */
        final MethodHandle anySolidOnLattice;
        /** 调用方自带缓冲的 4 参重载；没有则为 null。 */
        final MethodHandle densityInto;       // (Object,int,int,int,int,double[]) -> void
        /** 同上但强制 intensity；没有则为 null。 */
        final MethodHandle densityIntoForced; // (Object,int,int,int,int,float,double[]) -> void
        /** IslandField.LAND_INTENSITY：isLand 强制使用的那条 intensity。 */
        final float landIntensity;

        Proxy(Path jar) throws Exception {
            ClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            placementClass = Class.forName("dev.mumu.betterend.biome.BiomePlacement", true, cl);
            Class<?> config = Class.forName("dev.mumu.betterend.config.BetterEndConfig", true, cl);
            Class<?> field = Class.forName("dev.mumu.betterend.terrain.IslandField", true, cl);

            defaults = config.getField("DEFAULTS").get(null);
            bind = adapt(placementClass.getMethod("bind", long.class, config),
                    Object.class, long.class, Object.class);
            fieldOf = adapt(placementClass.getMethod("field"), Object.class, Object.class);
            isLand = adapt(placementClass.getMethod("isLand", int.class, int.class),
                    boolean.class, Object.class, int.class, int.class);
            at = adapt(placementClass.getMethod("at", int.class, int.class, int.class),
                    Object.class, Object.class, int.class, int.class, int.class);
            quartAt = adapt(placementClass.getMethod("quartAt", int.class, int.class, int.class),
                    Object.class, Object.class, int.class, int.class, int.class);
            ring = adapt(placementClass.getMethod("ring", int.class, int.class),
                    Object.class, Object.class, int.class, int.class);
            caveBiomeAt = adapt(placementClass.getMethod("caveBiomeAt", int.class, int.class),
                    Object.class, Object.class, int.class, int.class);
            allowsCarvers = adapt(placementClass.getMethod("allowsCarvers", int.class, int.class),
                    boolean.class, Object.class, int.class, int.class);
            terrainHeight = adapt(placementClass.getMethod("terrainHeightAt", int.class, int.class),
                    double.class, Object.class, int.class, int.class);
            topY = adapt(field.getMethod("topY", int.class, int.class),
                    int.class, Object.class, int.class, int.class);
            densityColumn = adapt(
                    field.getMethod("densityColumn", int.class, int.class, int.class, int.class),
                    double[].class, Object.class, int.class, int.class, int.class, int.class);
            densityForced = adapt(field.getMethod("densityColumn",
                            int.class, int.class, int.class, int.class, float.class),
                    double[].class, Object.class, int.class, int.class, int.class, int.class,
                    float.class);
            isSolid = adapt(field.getMethod("isSolid", int.class, int.class, int.class),
                    boolean.class, Object.class, int.class, int.class, int.class);
            landIntensity = field.getField("LAND_INTENSITY").getFloat(null);

            MethodHandle lattice = null;
            try {
                lattice = adapt(field.getMethod("anySolidOnLattice",
                                int.class, int.class, int.class, int.class, float.class),
                        boolean.class, Object.class, int.class, int.class, int.class, int.class,
                        float.class);
            } catch (NoSuchMethodException absent) {
                // 0.1.7 没有这个入口：该 workload 只在候选侧出现。
            }
            anySolidOnLattice = lattice;

            // 调用方自带缓冲的重载：只在候选侧存在。签名按参数形状识别，不按名字猜；
            // 识别不了就直接报错退出 —— 悄悄跳过会让"没测到"看起来像"没问题"。
            MethodHandle into4 = null;
            MethodHandle into5 = null;
            List<String> unknownInto = new ArrayList<>();
            for (Method m : field.getMethods()) {
                if (!m.getName().equals(DENSITY_INTO)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (Arrays.equals(ps, new Class<?>[]{double[].class, int.class, int.class, int.class, int.class})) {
                    into4 = adaptVoid(m, Object.class, double[].class, int.class, int.class, int.class, int.class);
                } else if (Arrays.equals(ps, new Class<?>[]{double[].class, int.class, int.class, int.class, int.class, float.class})) {
                    into5 = adaptVoid(m, Object.class, double[].class, int.class, int.class, int.class, int.class, float.class);
                } else {
                    unknownInto.add(m.toString());
                }
            }
            if (into4 == null && into5 == null && !unknownInto.isEmpty()) {
                fail("无法识别 densityColumnInto 签名，要求 (double[] out, int x, int z, int minY, int maxY[, float intensity]): " + unknownInto);
            }
            densityInto = into4;
            densityIntoForced = into5;
        }

        /** {@code (int,int,int,int,...)} 前缀：x、z、minY、maxY。 */
        static boolean isDensityRange(Class<?>[] ps) {
            return ps.length >= 4 && ps[0] == int.class && ps[1] == int.class
                    && ps[2] == int.class && ps[3] == int.class;
        }

        /** 把真实签名擦成调用点用的类型；asType 负责接收者/返回值的引用转换。 */
        static MethodHandle adapt(Method m, Class<?> ret, Class<?>... params) throws Exception {
            return MethodHandles.publicLookup().unreflect(m)
                    .asType(MethodType.methodType(ret, params));
        }

        /**
         * 同上但丢弃返回值：{@code densityColumnInto} 可能返回 void，也可能返回写入长度或
         * 同一个数组（视实现而定）。返回值不是契约的一部分，本工具只读调用方给的缓冲。
         */
        static MethodHandle adaptVoid(Method m, Class<?>... params) throws Exception {
            MethodHandle h = MethodHandles.publicLookup().unreflect(m)
                    .asType(MethodType.methodType(m.getReturnType(), params));
            return MethodHandles.dropReturn(h);
        }

        Object placement(long seed) throws Throwable {
            return (Object) bind.invokeExact(seed, defaults);
        }

        Object field(Object placement) throws Throwable {
            return (Object) fieldOf.invokeExact(placement);
        }

        boolean isLand(Object p, int qx, int qz) throws Throwable {
            return (boolean) isLand.invokeExact(p, qx, qz);
        }

        String atBiome(Object p, int x, int y, int z) throws Throwable {
            return name((Object) at.invokeExact(p, x, y, z));
        }

        String biome(Object p, int x, int y, int z) throws Throwable {
            return name((Object) quartAt.invokeExact(p, x, y, z));
        }

        String ring(Object p, int x, int z) throws Throwable {
            return name((Object) ring.invokeExact(p, x, z));
        }

        String cave(Object p, int x, int z) throws Throwable {
            return name((Object) caveBiomeAt.invokeExact(p, x, z));
        }

        boolean carvers(Object p, int x, int z) throws Throwable {
            return (boolean) allowsCarvers.invokeExact(p, x, z);
        }

        double terrainHeight(Object p, int qx, int qz) throws Throwable {
            return (double) terrainHeight.invokeExact(p, qx, qz);
        }

        int topY(Object f, int x, int z) throws Throwable {
            return (int) topY.invokeExact(f, x, z);
        }

        double[] density(Object f, int x, int z, int minY, int maxY) throws Throwable {
            return (double[]) densityColumn.invokeExact(f, x, z, minY, maxY);
        }

        double[] densityForced(Object f, int x, int z, int minY, int maxY) throws Throwable {
            return (double[]) densityForced.invokeExact(f, x, z, minY, maxY, landIntensity);
        }

        /** 写进调用方的 {@code out}；只在该 jar 有 4 参 {@code densityColumnInto} 时可调。 */
        void intoDensity(Object f, int x, int z, int minY, int maxY, double[] out)
                throws Throwable {
            densityInto.invokeExact(f, out, x, z, minY, maxY);
        }

        /** 同上，强制 LAND_INTENSITY。 */
        void intoDensityForced(Object f, int x, int z, int minY, int maxY, double[] out)
                throws Throwable {
            densityIntoForced.invokeExact(f, out, x, z, minY, maxY, landIntensity);
        }

        boolean solid(Object f, int x, int y, int z) throws Throwable {
            return (boolean) isSolid.invokeExact(f, x, y, z);
        }

        boolean anySolidLattice(Object f, int x, int z, int minY, int maxY) throws Throwable {
            return (boolean) anySolidOnLattice.invokeExact(f, x, z, minY, maxY, landIntensity);
        }

        static String name(Object o) {
            return o == null ? "<null>" : ((Enum<?>) o).name();
        }
    }

    // ---------------------------------------------------------------- workload 清单

    /** 候选侧新增的"调用方给缓冲"重载；基准侧没有。 */
    static final String DENSITY_INTO = "densityColumnInto";

    interface Workload {
        /** @return 返回值滚动校验和；counter[0] 写样本数。 */
        long run(Object placement, long[] counter) throws Throwable;

        /**
         * 每一"遍"开始前调用一次（冷档每轮一次、热档首遍一次），让 workload 重建自己的夹具。
         * <p>热测量的重复块里<b>不</b>调用 —— 那时才算"同一份夹具被反复使用"，缓冲复用才有意义。
         */
        default void newPass() {
        }
    }

    static long mix(long acc, long v) {
        return acc * 0x100000001B3L ^ v;
    }

    static List<Case> cases(long seed) {
        return List.of(
                new Case("isLand-spread", true, p -> isLandSpread(p, seed)),
                new Case("at-same-column-65y", true, p -> atSameColumn(p, seed)),
                new Case("anySolidOnLattice-spread", true, p -> anySolidLattice(p, seed)),
                new Case("isLand-ring-band", true, p -> isLandRingBand(p, seed)),
                new Case("topY-spread", true, p -> topYSpread(p, seed)),
                new Case("topY-far-islands", true, p -> topYFar(p, seed)),
                new Case("locate-spiral", false, LocateBench::locateSpiral),
                new Case("densityColumn-perColumn", true, p -> densityColumn(p, seed, false)),
                new Case("densityColumn-landIntensity", true, p -> densityColumn(p, seed, true)),
                new Case("quartAt-chunk-columns", true, LocateBench::quartAtChunk),
                new Case("regions-probe", false, LocateBench::regionsProbe));
    }

    // ---------------------------------------------------------------- 具体 workload

    /** 600 个广播四分列：一半落在半径 ≤256 四分格（核心/内环），一半 1024..10240 四分格。 */
    static Workload isLandSpread(Proxy p, long seed) {
        int[][] pts = spreadQuarts(seed ^ 0x11L, 600);
        return (placement, c) -> {
            long sum = 0;
            for (int[] q : pts) sum = mix(sum, p.isLand(placement, q[0], q[1]) ? 1 : 0);
            c[0] = pts.length;
            return sum;
        };
    }

    /**
     * 同一 (x,z) 列上按 y 逐层取群系（65 个 y）：结构探测的真实形态
     * （原版 couldStructureExistInTerrainColumn 对同一 quart 列逐 quartY 取样）。
     * 旧实现每层都会重算 landMap.at / voidMap.at，这里是收益最集中的一行。
     */
    static Workload atSameColumn(Proxy p, long seed) {
        int[][] cols = columns(seed ^ 0x66L, 256, 256, 12_256);
        return (placement, c) -> {
            long sum = 0;
            int n = 0;
            for (int[] col : cols) {
                for (int y = 0; y <= 128; y += 2) {
                    sum = mix(sum, p.atBiome(placement, col[0], y, col[1]).hashCode());
                    n++;
                }
            }
            c[0] = n;
            return sum;
        };
    }

    /** 0.1.8 新增的 IslandField.anySolidOnLattice：与 isLand-spread 同一批坐标。 */
    static Workload anySolidLattice(Proxy p, long seed) {
        if (p.anySolidOnLattice == null) return null;
        int[][] pts = spreadQuarts(seed ^ 0x11L, 600);
        return (placement, c) -> {
            Object f = p.field(placement);
            long sum = 0;
            for (int[] q : pts) {
                // isLand 用的就是 [0,128] + LAND_INTENSITY 这组参数
                sum = mix(sum, p.anySolidLattice(f, q[0] << 2, q[1] << 2, 0, 128) ? 1 : 0);
            }
            c[0] = pts.length;
            return sum;
        };
    }

    /** 固定半径环带上的四分列：四个象限（正负坐标）各一组。 */
    static Workload isLandRingBand(Proxy p, long seed) {
        int[][] pts = new int[200][];
        Random r = new Random(seed ^ 0x22L);
        for (int i = 0; i < pts.length; i++) {
            double ang = (i % 4) * (Math.PI / 2) + r.nextDouble() * 0.6;
            double rad = 10_000 * (0.9 + r.nextDouble() * 0.2);
            pts[i] = new int[]{(int) Math.round(Math.cos(ang) * rad),
                    (int) Math.round(Math.sin(ang) * rad)};
        }
        return (placement, c) -> {
            long sum = 0;
            for (int[] q : pts) sum = mix(sum, p.isLand(placement, q[0], q[1]) ? 1 : 0);
            c[0] = pts.length;
            return sum;
        };
    }

    /** topY 整列扫描：watchdog 栈上的另一条路径。每次调用约 2ms，样本数压到 96 以免单遍过长。 */
    static Workload topYSpread(Proxy p, long seed) {
        int[][] cols = columns(seed ^ 0x33L, 96, 256, 12_256);
        return (placement, c) -> {
            Object f = p.field(placement);
            long sum = 0;
            for (int[] col : cols) sum = mix(sum, p.topY(f, col[0], col[1]));
            c[0] = cols.length;
            return sum;
        };
    }

    /** 远外岛（半径 20000..30000 方块）：玩家跑远才卡的场景，同时覆盖 isSolid。 */
    static Workload topYFar(Proxy p, long seed) {
        int[][] cols = columns(seed ^ 0x44L, 96, 20_000, 30_000);
        return (placement, c) -> {
            Object f = p.field(placement);
            long sum = 0;
            int n = 0;
            for (int[] col : cols) {
                int y = p.topY(f, col[0], col[1]);
                sum = mix(sum, y);
                sum = mix(sum, p.solid(f, col[0], Math.max(0, y), col[1]) ? 1 : 0);
                n += 2;
            }
            c[0] = n;
            return sum;
        };
    }

    /**
     * 类似 /locate 的稀疏查询：从远处原点做扩张方螺旋（只走周长，不重复内圈），
     * 逐格问"是不是陆地"，命中就再取三个 y 的群系（locate 命中后要判群系），
     * 凑够 400 个命中即停。采样点真实铺开，不能靠同一坐标反复读制造假提升。
     * <p>
     * 注意：这里测的是"候选列的探测成本"。原版 /locate 是否提前返回取决于结构 tag
     * 与守卫（end_city 被 StructureWorldGuard 取消，所以实际会跑满整个半径），
     * 那属于结构注册语义，不是本工具或性能修复的范围。
     */
    static Workload locateSpiral(Proxy p) {
        return (placement, c) -> {
            long sum = 0;
            int probes = 0;
            int hits = 0;
            int originX = 2048;          // 四分格 => 8192 方块
            int originZ = -1536;
            int step = 16;               // 四分格 => 64 方块
            int targetHits = 400;
            int maxRing = 80;
            outer:
            for (int ring = 0; ring <= maxRing; ring++) {
                if (ring == 0) {
                    probes++;
                    sum = mix(sum, probeSpiral(p, placement, originX, originZ));
                    if (p.isLand(placement, originX, originZ)) hits++;
                    continue;
                }
                for (int i = -ring; i <= ring; i++) {
                    for (int side = 0; side < 4; side++) {
                        int dx;
                        int dz;
                        switch (side) {
                            case 0 -> { dx = i; dz = -ring; }
                            case 1 -> { dx = ring; dz = i; }
                            case 2 -> { dx = -i; dz = ring; }
                            default -> { dx = -ring; dz = -i; }
                        }
                        if (side > 0 && (i == -ring || i == ring)) continue; // 角点只算一次
                        int qx = originX + dx * step;
                        int qz = originZ + dz * step;
                        probes++;
                        if (p.isLand(placement, qx, qz)) {
                            hits++;
                            sum = mix(sum, biomeTriple(p, placement, qx, qz));
                            if (hits >= targetHits) break outer;
                        }
                    }
                }
            }
            c[0] = probes;
            return sum;
        };
    }

    static long probeSpiral(Proxy p, Object placement, int qx, int qz) throws Throwable {
        return biomeTriple(p, placement, qx, qz);
    }

    static long biomeTriple(Proxy p, Object placement, int qx, int qz) throws Throwable {
        int bx = qx << 2;
        int bz = qz << 2;
        long sum = p.biome(placement, bx, 8, bz).hashCode();
        sum = mix(sum, p.biome(placement, bx, 64, bz).hashCode());
        return mix(sum, p.biome(placement, bx, 100, bz).hashCode());
    }

    /**
     * densityColumn 两档：
     * <ul>
     *   <li>4 参（每列按 biome 自己的 intensity）：内部会走
     *       plane -> columnHeight -> terrainHeightAt -> at -> isLand 的重入链；</li>
     *   <li>5 参（强制 LAND_INTENSITY）：就是 isLand 内部反复调用的那一条。</li>
     * </ul>
     * <p>校验和覆盖<b>整列 128 个元素</b>、按 {@code y} 递增次序，并用
     * {@link Double#doubleToRawLongBits}（不是 {@code doubleToLongBits}）—— "逐位相同"就是逐位，
     * NaN 的载荷和 -0.0 与 0.0 的符号都算差异。原来每 4 个取 1 个的抽样会让"只写了一半"
     * 的实现蒙混过关，这里不再抽样。
     * <p>该 jar 若提供了调用方自带缓冲的重载，这一档就用它（缓冲每"遍"重建、遍内复用），
     * 否则用会分配数组的老入口 —— 两侧比的就是"生产上实际会调哪个"，
     * 缓冲复用的收益由报告里的分配列单独量化。
     */
    static Workload densityColumn(Proxy p, long seed, boolean forced) {
        int[][] cols = columns(seed ^ 0x55L, 160, 512, 9_512);
        boolean into = forced ? p.densityIntoForced != null : p.densityInto != null;
        if (into) return new IntoSink(p, cols, forced);

        return (placement, c) -> {
            Object f = p.field(placement);
            long sum = 0;
            int n = 0;
            for (int[] col : cols) {
                double[] values = forced
                        ? p.densityForced(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y)
                        : p.density(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y);
                for (int i = 0; i < values.length; i++) {
                    sum = mix(sum, Double.doubleToRawLongBits(values[i]));
                    n++;
                }
            }
            c[0] = cols.length;
            return sum;
        };
    }

    /**
     * 调用方持有缓冲的那一档。scratch 每"遍"新建一次（{@link #newPass}），遍内所有列复用 ——
     * 这正是生产里 {@code TerrainGenerator} 的形态，也是"分配字节/样本 ≈ 0"的来源。
     * <p>校验和与老入口同次序、同长度，所以两侧的 {@code same} 列就是跨 API 的语义对照。
     */
    static final class IntoSink implements Workload {
        private final Proxy p;
        private final int[][] cols;
        private final boolean forced;
        private double[] buf;

        IntoSink(Proxy p, int[][] cols, boolean forced) {
            this.p = p;
            this.cols = cols;
            this.forced = forced;
        }

        @Override
        public void newPass() {
            buf = new double[DENSITY_LEN];
        }

        @Override
        public long run(Object placement, long[] c) throws Throwable {
            // 防御：热测量的首遍、分配探针、以及任何直接调 run 的路径都可能没先 newPass。
            if (buf == null) newPass();
            Object f = p.field(placement);
            long sum = 0;
            int n = 0;
            for (int[] col : cols) {
                if (forced) {
                    p.intoDensityForced(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y, buf);
                } else {
                    p.intoDensity(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y, buf);
                }
                for (int i = 0; i < buf.length; i++) {
                    sum = mix(sum, Double.doubleToRawLongBits(buf[i]));
                    n++;
                }
            }
            c[0] = cols.length;
            return sum;
        }
    }

    /** 四个 chunk 的 16×16 列 × y 0..120 步 8：群系源在真实生成里的调用形态。 */
    static Workload quartAtChunk(Proxy p) {
        int[][] origins = {{30_000, -24_000}, {-30_000, 24_000}, {12_288, 9_216}, {-8_192, -16_384}};
        return (placement, c) -> {
            long sum = 0;
            int n = 0;
            for (int[] o : origins) {
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int bx = o[0] + dx * 4;
                        int bz = o[1] + dz * 4;
                        for (int y = 0; y <= 120; y += 8) {
                            sum = mix(sum, p.biome(placement, bx, y, bz).hashCode());
                            n++;
                        }
                    }
                }
            }
            c[0] = n;
            return sum;
        };
    }

    /**
     * 区域探针：核心（r&lt;384）、内环边界 r≈1024 的内外两侧、四个象限正负坐标、远外岛，
     * 覆盖 ring / caveBiomeAt / allowsCarvers / terrainHeightAt / isLand 五条分支。
     */
    static Workload regionsProbe(Proxy p) {
        int r = 1024;   // INNER_VOID_RADIUS_SQUARED = 4096*16*16 => 半径 1024 方块
        int[][] seedPoints = {
                {0, 0}, {100, -100}, {-200, 200}, {0, 380},              // 核心 / 核心边缘
                {r - 4, 0}, {-r + 4, 0}, {0, r - 4}, {0, -r + 4},        // 内环边界（内侧）
                {r, 0}, {-r, 0}, {r + 4, 0}, {0, -r - 4},                // 内环边界（外侧）
                {1200, 1200}, {-1200, 1200}, {1200, -1200}, {-1200, -1200},
                {8192, 8192}, {-8192, 8192}, {20_000, -20_000}, {-30_000, -30_000},
        };
        int[][] offsets = {{0, 0}, {64, 0}, {0, 64}, {-64, -64}, {256, -256}};
        return (placement, c) -> {
            long sum = 0;
            int n = 0;
            for (int[] b : seedPoints) {
                for (int[] off : offsets) {
                    int x = b[0] + off[0];
                    int z = b[1] + off[1];
                    sum = mix(sum, p.ring(placement, x, z).hashCode());
                    sum = mix(sum, p.cave(placement, x, z).hashCode());
                    sum = mix(sum, p.carvers(placement, x, z) ? 1 : 0);
                    sum = mix(sum, Double.doubleToRawLongBits(
                            p.terrainHeight(placement, x >> 2, z >> 2)));
                    sum = mix(sum, p.isLand(placement, x >> 2, z >> 2) ? 1 : 0);
                    n += 5;
                }
            }
            c[0] = n;
            return sum;
        };
    }

    // ---------------------------------------------------------------- 跨 API / 跨线程对照

    /**
     * {@code densityColumnInto} 的语义对照：候选侧每次调用<b>之前</b>把 scratch 填满哨兵，
     * 结果仍必须与基准侧 {@code densityColumn}（全新数组）的校验和逐位相同。
     * <p>为什么必须填哨兵：只写"该写的那一段"、或把 {@code <= 0} 的项留给调用方默认值的实现，
     * 在缓冲恰好干净时能骗过任何一次比对。哨兵把"没写"变成可观测的差异。
     * <p>只在该侧确有该重载时执行；没有就明确说"跳过"，绝不当作通过。
     */
    static void crossCheckInto(Proxy base, Proxy cand, Args opts, Tally tally) throws Throwable {
        int[][] cols = columns(opts.seed ^ 0x55L, 160, 512, 9_512);
        boolean any = false;
        for (boolean forced : new boolean[]{false, true}) {
            String name = "into-parity-" + (forced ? "landIntensity" : "perColumn");
            if (forced ? cand.densityIntoForced == null : cand.densityInto == null) continue;
            any = true;

            long ref = referenceColumnSum(base, cols, forced);
            long clean = candidateIntoSum(cand, cols, forced, false);
            long dirty = candidateIntoSum(cand, cols, forced, true);

            boolean ok = ref == clean && ref == dirty;
            if (!ok) tally.mismatched.add(name);
            tally.compared++;
            tally.intoChecked++;
            System.out.printf("%-38s %9s %5s %11s %11s %7s %8s %5s%n", name, "-", "-",
                    "ref " + Long.toHexString(ref), "dirty " + Long.toHexString(dirty), "-", "-",
                    ok ? "yes" : "NO");
            if (ok) {
                System.out.println("        基准侧全新数组、候选侧复用 scratch、候选侧复用且每列先填哨兵 —— 三者逐位相同。");
            } else {
                System.out.println("        候选侧 densityColumnInto 与基准侧 densityColumn 不一致："
                        + "先查「是否整列写满」与「y 边界是否漏项」，再看耗时。");
            }
        }
    }

    /** 基准侧：老入口每次新数组，全列按 y 递增混入。 */
    static long referenceColumnSum(Proxy p, int[][] cols, boolean forced) throws Throwable {
        Object inst = p.placement(0x5EEDL);
        Object f = p.field(inst);
        long sum = 0;
        for (int[] col : cols) {
            double[] values = forced
                    ? p.densityForced(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y)
                    : p.density(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y);
            for (int i = 0; i < values.length; i++) {
                sum = mix(sum, Double.doubleToRawLongBits(values[i]));
            }
        }
        return sum;
    }

    /** 候选侧：复用一块 scratch，{@code poison} 为真时每列调用前先填满哨兵。 */
    static long candidateIntoSum(Proxy p, int[][] cols, boolean forced, boolean poison)
            throws Throwable {
        Object inst = p.placement(0x5EEDL);
        Object f = p.field(inst);
        double[] buf = new double[DENSITY_LEN];
        long sum = 0;
        for (int[] col : cols) {
            if (poison) Arrays.fill(buf, POISON);
            if (forced) {
                p.intoDensityForced(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y, buf);
            } else {
                p.intoDensity(f, col[0], col[1], DENSITY_MIN_Y, DENSITY_MAX_Y, buf);
            }
            for (int i = 0; i < buf.length; i++) {
                sum = mix(sum, Double.doubleToRawLongBits(buf[i]));
            }
        }
        return sum;
    }

    /** 绝不会与真实密度相等的哨兵（NaN 的一个非规范载荷）。 */
    static final double POISON = Double.longBitsToDouble(0x7FF8DEADBEEFL);

    /**
     * 1 线程 ↔ N 线程等值：同一批坐标按 {@code i % n == 片号} 切成 n 片，每片<b>独立实例、
     * 独立 scratch</b>，先把 n 片串行跑一遍，再用 n 个线程各跑一片，合并校验和必须逐位相同。
     * <p>切法与合并次序都与线程数无关（都按片号 0..n-1 依次 mix），所以差异只可能来自
     * "算法对执行顺序/共享状态有依赖"，不会来自合并方式。
     * <p>它<b>不</b>证明该类可以多线程共享同一个实例（那是另一件事，事务边界在调用方）；
     * 它证明的是：同样的输入，串行与并行产生的输出逐位相同。
     */
    static void parityCheck(Proxy base, Proxy cand, Args opts, Tally tally) throws Throwable {
        int threads = opts.parityThreads;
        if (threads <= 1) {
            System.out.println("1 线程与 N 线程等值：跳过（--no-parity 或 --parity-threads <= 1）");
            System.out.println();
            return;
        }
        int[][] quarts = spreadQuarts(opts.seed ^ 0x77L, 240);
        int[][] cols = columns(opts.seed ^ 0x78L, 96, 512, 9_512);

        for (String side : new String[]{"baseline", "candidate"}) {
            Proxy p = side.equals("baseline") ? base : cand;
            long[] serial = runSlices(p, quarts, cols, threads, 1);
            long[] parallel = runSlices(p, quarts, cols, threads, threads);
            boolean ok = Arrays.equals(serial, parallel);
            String name = "parity-1t-vs-" + threads + "t-" + side;
            if (!ok) tally.mismatched.add(name);
            tally.compared++;
            tally.parityChecked++;
            System.out.printf("%-38s %9s %5s %11s %11s %7s %8s %5s%n", name, "-", "-",
                    "serial " + hex3(serial), "par " + hex3(parallel), "-", "-", ok ? "yes" : "NO");
            if (!ok) {
                System.out.println("        同一批坐标在 " + threads
                        + " 线程与 1 线程下输出不同：这不是性能问题，是确定性/共享状态问题。");
            }
        }
        System.out.println("        每片独立实例与独立 scratch；切法与合并次序与线程数无关。");
        System.out.println();
    }

    static String hex3(long[] v) {
        return Long.toHexString(v[0]) + "/" + Long.toHexString(v[1]) + "/" + Long.toHexString(v[2]);
    }

    /** @return {isLand 和, topY 和, 密度列和}；合并次序固定为片号递增。 */
    static long[] runSlices(Proxy p, int[][] quarts, int[][] cols, int threads, int parallelism)
            throws Throwable {
        long[][] parts = new long[threads][];
        if (parallelism <= 1) {
            for (int s = 0; s < threads; s++) {
                parts[s] = sliceSum(p, quarts, cols, s, threads);
            }
        } else {
            // 裸线程而不是 Executor：sliceSum 抛 Throwable，Callable 只允许 Exception，
            // 包一层反而会把真正的失败原因埋掉。
            Throwable[] error = new Throwable[1];
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            for (int s = 0; s < threads; s++) {
                final int slice = s;
                workers[s] = new Thread(() -> {
                    try {
                        start.await();
                        parts[slice] = sliceSum(p, quarts, cols, slice, threads);
                    } catch (Throwable t) {
                        synchronized (error) {
                            if (error[0] == null) error[0] = t;
                        }
                    }
                }, "locate-bench-parity-" + s);
                workers[s].setDaemon(true);
                workers[s].start();
            }
            start.countDown();
            for (Thread w : workers) w.join();
            if (error[0] != null) throw error[0];
        }
        long[] out = new long[3];
        for (long[] part : parts) {
            for (int k = 0; k < 3; k++) out[k] = mix(out[k], part[k]);
        }
        return out;
    }

    static long[] sliceSum(Proxy p, int[][] quarts, int[][] cols, int slice, int threads)
            throws Throwable {
        Object inst = p.placement(0x5EEDL);
        Object f = p.field(inst);
        long land = 0;
        long top = 0;
        long den = 0;

        for (int i = slice; i < quarts.length; i += threads) {
            land = mix(land, p.isLand(inst, quarts[i][0], quarts[i][1]) ? 1 : 0);
        }

        double[] buf = new double[DENSITY_LEN];
        for (int i = slice; i < cols.length; i += threads) {
            top = mix(top, p.topY(f, cols[i][0], cols[i][1]));
            if (p.densityIntoForced != null) {
                p.intoDensityForced(f, cols[i][0], cols[i][1], DENSITY_MIN_Y, DENSITY_MAX_Y, buf);
            } else if (p.densityInto != null) {
                p.intoDensity(f, cols[i][0], cols[i][1], DENSITY_MIN_Y, DENSITY_MAX_Y, buf);
            } else {
                double[] v = p.densityForced(f, cols[i][0], cols[i][1], DENSITY_MIN_Y,
                        DENSITY_MAX_Y);
                System.arraycopy(v, 0, buf, 0, DENSITY_LEN);
            }
            for (int j = 0; j < buf.length; j++) {
                den = mix(den, Double.doubleToRawLongBits(buf[j]));
            }
        }
        return new long[]{land, top, den};
    }

    // ---------------------------------------------------------------- 坐标生成

    /** 600 个广播四分列：一半在半径 ≤256（核心/内环），一半在 1024..10240。 */
    static int[][] spreadQuarts(long seed, int count) {
        int[][] pts = new int[count][];
        Random r = new Random(seed);
        for (int i = 0; i < count; i++) {
            double radius = (i % 2 == 0) ? 256 * Math.sqrt(r.nextDouble())
                    : 1024 + Math.sqrt(r.nextDouble()) * 9216;
            double ang = r.nextDouble() * Math.PI * 2;
            pts[i] = new int[]{(int) Math.round(Math.cos(ang) * radius),
                    (int) Math.round(Math.sin(ang) * radius)};
        }
        return pts;
    }

    /** 极坐标散布的方块列（正负象限全覆盖）。 */
    static int[][] columns(long seed, int count, double minRadius, double maxRadius) {
        int[][] cols = new int[count][];
        Random r = new Random(seed);
        for (int i = 0; i < count; i++) {
            double radius = minRadius + Math.sqrt(r.nextDouble()) * (maxRadius - minRadius);
            double ang = r.nextDouble() * Math.PI * 2;
            cols[i] = new int[]{(int) Math.round(Math.cos(ang) * radius),
                    (int) Math.round(Math.sin(ang) * radius)};
        }
        return cols;
    }
}
