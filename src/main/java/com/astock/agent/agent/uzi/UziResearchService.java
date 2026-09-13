package com.astock.agent.agent.uzi;

import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * UZI 深度分析的异步任务边界。
 *
 * <p>官方 Python 项目通过受限 Runner 接入；浏览器只能提交证券代码、深度和流派，不能
 * 覆盖 Python 命令、工作目录、环境变量或输出路径。任务完成后保留版本化结构化 bundle，
 * 后续 FinRobot 可以直接消费该 bundle。</p>
 */
public final class UziResearchService implements AutoCloseable {
    static final String ROLE = "uzi-research";
    private static final int MAX_JOBS = 100;

    @FunctionalInterface
    interface Runner {
        UziResearchBundle run(String code, String depth, String school, String modelName,
                Path outputDir) throws Exception;
    }

    private final UziProperties properties;
    private final NamedChatClientRegistry registry;
    private final Runner runner;
    private final Path reports;
    private final Duration taskTimeout;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "uzi-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore capacity;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public UziResearchService(UziProperties properties, NamedChatClientRegistry registry) {
        this(properties, registry, new UziPythonWorker(properties)::run);
    }

    public UziResearchService(UziProperties properties) {
        this(properties, new NamedChatClientRegistry(null, null));
    }

    UziResearchService(UziProperties properties, NamedChatClientRegistry registry, Runner runner) {
        this.properties = properties == null
                ? new UziProperties(null, null, null, null, 1) : properties;
        this.registry = registry == null ? new NamedChatClientRegistry(null, null) : registry;
        this.runner = runner;
        this.reports = Path.of(this.properties.reportPath()).toAbsolutePath().normalize();
        this.taskTimeout = this.properties.taskTimeout();
        this.capacity = new Semaphore(this.properties.maxConcurrentTasks());
    }

    public List<NamedChatClientRegistry.ModelReference> models() {
        return registry.availableModels(ROLE);
    }

    public Status status() {
        Path root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
        boolean installed = Files.isRegularFile(root.resolve("run.py"));
        boolean pythonAvailable = commandAvailable(properties.python());
        String reason = !installed ? "UZI_ROOT_NOT_FOUND"
                : !pythonAvailable ? "PYTHON_NOT_AVAILABLE" : "READY";
        return new Status(installed && pythonAvailable, installed, pythonAvailable, reason,
                root.toString(), properties.python());
    }

    public synchronized Task start(String code, String depth, String school) {
        String normalizedCode = validateCode(code);
        String normalizedDepth = normalizeDepth(depth);
        String normalizedSchool = normalizeSchool(school);
        for (Job current : jobs.values()) {
            if (current.code.equals(normalizedCode) && "RUNNING".equals(current.status)) {
                return current.view();
            }
        }
        if (jobs.size() >= MAX_JOBS) {
            jobs.values().removeIf(job -> !"RUNNING".equals(job.status));
        }

        NamedChatClientRegistry.NamedModel selected = registry.forRole(ROLE).orElse(null);
        String modelName = selected == null ? "" : selected.modelName();
        Job job = new Job(normalizedCode, normalizedDepth, normalizedSchool, modelName);
        jobs.put(job.id, job);

        Status runtime = status();
        if (!runtime.enabled()) {
            job.fail(runtime.reason().equals("UZI_ROOT_NOT_FOUND")
                    ? "UZI 未安装，请先运行 scripts/setup-uzi.ps1"
                    : "Python 不可用，请检查 app.uzi.python 配置");
            return job.view();
        }
        if (!capacity.tryAcquire()) {
            jobs.remove(job.id);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "UZI 研究任务繁忙，请稍后重试");
        }

        job.future = executor.submit(() -> {
            try {
                execute(job);
            } finally {
                capacity.release();
            }
        });
        job.timeout = timer.schedule(() -> {
            synchronized (job) {
                if ("RUNNING".equals(job.status)) {
                    job.fail("UZI 研究超时，请检查模型响应速度或缩小分析深度");
                    if (job.future != null) job.future.cancel(true);
                }
            }
        }, taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
        return job.view();
    }

    public synchronized Task get(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "UZI 研究任务不存在或已过期");
        }
        return job.view();
    }

    public synchronized Task latest(String code) {
        String normalizedCode = validateCode(code);
        return jobs.values().stream()
                .filter(job -> job.code.equals(normalizedCode))
                .max((left, right) -> left.startedAt.compareTo(right.startedAt))
                .map(Job::view)
                .orElseGet(() -> readLatest(normalizedCode));
    }

    private void execute(Job job) {
        try {
            job.stage = "执行 UZI 深度分析";
            job.updatedAt = Instant.now();
            UziResearchBundle bundle = runner.run(job.code, job.depth, job.school, job.modelName,
                    reports.resolve(job.id));
            if (bundle == null || !job.code.equals(bundle.ticker())) {
                throw new IOException("UZI bundle 缺少匹配的证券代码");
            }
            bundle = sanitizeBundle(bundle);
            synchronized (job) {
                if (!"RUNNING".equals(job.status) || Thread.currentThread().isInterrupted()) return;
                job.bundle = bundle;
                job.snapshotAt = parseInstant(bundle.generatedAt());
                job.reportPath = bundle.reportPath();
                job.status = "COMPLETED";
                job.stage = "研究完成";
                job.updatedAt = Instant.now();
                try {
                    save(job.view());
                } catch (Exception unavailableStorage) {
                    job.error = "UZI bundle 已生成，但本地保存失败；请检查报告目录权限";
                }
            }
        } catch (Exception failure) {
            synchronized (job) {
                if ("RUNNING".equals(job.status)) {
                    job.fail("UZI 研究未完成：" + safeMessage(failure));
                }
            }
        } finally {
            if (job.timeout != null) job.timeout.cancel(false);
        }
    }

    private Task readLatest(String code) {
        Path file = reports.resolve(code + ".json").normalize();
        if (!file.startsWith(reports) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未生成 UZI 研究报告");
        }
        try {
            if (Files.isSymbolicLink(file) || Files.size(file) > 20_000_000) throw new IOException();
            Task task = mapper.readValue(Files.readString(file), Task.class);
            if (!code.equals(task.code()) || !"COMPLETED".equals(task.status())) throw new IOException();
            return task;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "本地 UZI bundle 不可读，请重新生成");
        }
    }

    private void save(Task task) throws IOException {
        Files.createDirectories(reports);
        Path file = reports.resolve(task.code() + ".json").normalize();
        if (!file.startsWith(reports)) throw new IOException("UZI 报告路径无效");
        Path temporary = Files.createTempFile(reports, "uzi-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(task));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String validateCode(String code) {
        try {
            return SecurityId.parse(code).code();
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "证券代码无效");
        }
    }

    private static String normalizeDepth(String depth) {
        String value = depth == null || depth.isBlank() ? "medium" : depth.trim().toLowerCase();
        if (!List.of("lite", "medium", "deep").contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UZI 分析深度无效");
        }
        return value;
    }

    private static String normalizeSchool(String school) {
        if (school == null || school.isBlank()) return "";
        String value = school.trim().toUpperCase();
        if (!value.matches("[A-I]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UZI 投资流派无效");
        }
        return value;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static UziResearchBundle sanitizeBundle(UziResearchBundle bundle) {
        String safeReportPath = safeRelativePath(bundle.reportPath());
        if (java.util.Objects.equals(safeReportPath, bundle.reportPath())) return bundle;
        return new UziResearchBundle(bundle.schema(), bundle.ticker(), bundle.generatedAt(),
                bundle.rawData(), bundle.dimensions(), bundle.panel(), bundle.synthesis(),
                bundle.structured(), bundle.sources(), bundle.dataGaps(), safeReportPath);
    }

    private static String safeRelativePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value).normalize();
            return path.isAbsolute() || path.startsWith("..") ? null : path.toString().replace('\\', '/');
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean commandAvailable(String command) {
        if (command == null || command.isBlank() || command.contains("\0")) return false;
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        timer.shutdownNow();
        executor.shutdownNow();
        try {
            timer.awaitTermination(2, TimeUnit.SECONDS);
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public record Task(String id, String code, String status, String stage, String depth,
            String modelName, Instant startedAt, Instant updatedAt, Instant snapshotAt,
            UziResearchBundle bundle, List<String> limitations, String error, String reportPath) {
        public Task {
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record Status(boolean enabled, boolean installed, boolean pythonAvailable,
            String reason, String rootPath, String python) {}

    private final class Job {
        final String id = UUID.randomUUID().toString();
        final String code;
        final String depth;
        final String school;
        final String modelName;
        final Instant startedAt = Instant.now();
        volatile Instant updatedAt = startedAt;
        volatile String status = "RUNNING";
        volatile String stage = "准备 UZI 深度分析";
        volatile Instant snapshotAt;
        volatile UziResearchBundle bundle;
        volatile String error;
        volatile String reportPath;
        Future<?> future;
        ScheduledFuture<?> timeout;

        Job(String code, String depth, String school, String modelName) {
            this.code = code;
            this.depth = depth;
            this.school = school;
            this.modelName = modelName;
        }

        void fail(String message) {
            status = "FAILED";
            stage = "研究未完成";
            error = message;
            updatedAt = Instant.now();
        }

        synchronized Task view() {
            return new Task(id, code, status, stage, depth, modelName, startedAt, updatedAt,
                    snapshotAt, "COMPLETED".equals(status) ? bundle : null,
                    List.of("UZI 结果依赖本地数据源与模型配置；缺失项不会被估算填充"), error,
                    reportPath);
        }
    }
}
