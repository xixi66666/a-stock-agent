package com.astock.agent.agent.cycle;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 独立异步研究任务。两项并发、五分钟时限，失败不产生伪造的回退报告。 */
@Service
public final class CycleResearchService implements AutoCloseable {
    private final NamedChatClientRegistry registry;
    private final StockAgentTools stockTools;
    private final CycleLibrary library;
    private final Path reports;
    private final Runner runner;
    private final Duration taskTimeout;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cycle-timeout"); thread.setDaemon(true); return thread;
    });
    private final Semaphore capacity = new Semaphore(2);
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CycleResearchService(NamedChatClientRegistry registry, StockAgentTools stockTools, CycleProperties properties) {
        this(registry, stockTools, properties,
                new CycleLibrary(Path.of(properties.skillPath()), Path.of(properties.bookPath()), properties.python()),
                new CycleAgent()::run, Duration.ofMinutes(5));
    }
    @FunctionalInterface interface Runner {
        CycleReport run(org.springframework.ai.chat.client.ChatClient client, CycleSession session) throws Exception;
    }
    CycleResearchService(NamedChatClientRegistry registry, StockAgentTools stockTools, CycleProperties properties,
            CycleLibrary library, Runner runner, Duration taskTimeout) {
        this.registry = registry;
        this.stockTools = stockTools;
        this.library = library;
        this.runner = runner;
        this.taskTimeout = taskTimeout;
        this.reports = Path.of(properties.reportPath()).toAbsolutePath().normalize();
    }
    public List<NamedChatClientRegistry.ModelReference> models() { return registry.availableModels("cycle-report"); }

    public synchronized Task start(String code, String modelId) {
        validateCode(code);
        if (modelId != null && (modelId.length() > 80 || !modelId.matches("[A-Za-z0-9_.-]+"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型标识无效");
        }
        var selected = modelId == null ? registry.forRole("cycle-report") : registry.byId(modelId);
        for (Job current : jobs.values()) {
            if (current.code.equals(code) && "RUNNING".equals(current.status)) return current.view();
        }
        // 仅淘汰已结束任务；最新成功报告另存本地，可跨重启读取。
        if (jobs.size() >= 100) jobs.entrySet().removeIf(e -> !"RUNNING".equals(e.getValue().status));
        Job job = new Job(code, selected.map(NamedChatClientRegistry.NamedModel::modelName).orElse(""));
        if (selected.isEmpty()) {
            job.fail("未配置周期研究模型，请配置 cycle-report 角色或选择可用模型");
            jobs.put(job.id, job);
            return job.view();
        }
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "周期研究任务繁忙，请稍后重试");
        jobs.put(job.id, job);
        job.future = executor.submit(() -> {
            try { execute(job, selected.orElseThrow()); }
            finally { capacity.release(); }
        });
        job.timeout = timer.schedule(() -> {
            synchronized (job) {
                if ("RUNNING".equals(job.status)) {
                    job.fail("周期研究超时，请稍后重试或检查模型响应速度");
                    job.future.cancel(true);
                }
            }
        }, taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!"RUNNING".equals(job.status)) job.timeout.cancel(false);
        return job.view();
    }
    private void execute(Job job, NamedChatClientRegistry.NamedModel model) {
        try {
            job.stage = "加载研究方法";
            String instructions = library.instructions();
            job.skillDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instructions.getBytes(StandardCharsets.UTF_8)));
            job.stage = "准备股票证据";
            var snapshot = stockTools.getResearchSnapshot(job.code);
            job.snapshotAt = snapshot.fetchedAt();
            job.session = new CycleSession(library, snapshot, stage -> {
                if ("RUNNING".equals(job.status)) { job.stage = stage; job.updatedAt = Instant.now(); }
            });
            job.stage = "执行周期研究";
            CycleReport report = runner.run(model.client(), job.session);
            synchronized (job) {
                if (!"RUNNING".equals(job.status) || Thread.currentThread().isInterrupted()) return;
                job.report = report;
                job.status = "COMPLETED";
                job.stage = "研究完成";
                job.updatedAt = Instant.now();
                try { save(job.view()); }
                catch (Exception unavailableStorage) {
                    job.error = "报告已生成，但本地保存失败；刷新页面后可能无法恢复，请检查报告目录权限";
                }
            }
        } catch (Exception failure) {
            synchronized (job) {
                if ("RUNNING".equals(job.status)) {
                    job.fail("加载研究方法".equals(job.stage)
                            ? "原始技能或书库不可用，请检查本地资料配置"
                            : "准备股票证据".equals(job.stage)
                                ? "股票证据获取失败，请稍后重试"
                                : "周期研究未完成：请检查模型工具调用能力、原书检索与阅读记录后重试");
                }
            }
        } finally {
            if (job.timeout != null) job.timeout.cancel(false);
        }
    }
    public synchronized Task get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "研究任务不存在或已过期");
        return job.view();
    }
    public synchronized Task latest(String code) {
        validateCode(code);
        // 刷新或切换标签时继续显示当前任务，包括最新失败，不静默回退成旧的成功报告。
        var current = jobs.values().stream().filter(job -> job.code.equals(code)).reduce((a, b) -> b);
        if (current.isPresent()) return current.orElseThrow().view();
        Path file = reports.resolve(code + ".json");
        if (!Files.exists(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未生成周期报告");
        try {
            if (Files.isSymbolicLink(file) || Files.size(file) > 10_000_000) throw new IllegalStateException();
            Task task = mapper.readValue(Files.readString(file), Task.class);
            if (!code.equals(task.code()) || !"COMPLETED".equals(task.status())) throw new IllegalStateException();
            return task;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "本地周期报告不可读，请重新生成");
        }
    }
    private void save(Task task) throws Exception {
        Files.createDirectories(reports);
        Path temp = Files.createTempFile(reports, "cycle-", ".tmp");
        try {
            Files.writeString(temp, mapper.writeValueAsString(task));
            try { Files.move(temp, reports.resolve(task.code() + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, reports.resolve(task.code() + ".json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }
    private static void validateCode(String code) {
        try { SecurityId.parse(code); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "证券代码无效"); }
    }
    @Override @PreDestroy public void close() { timer.shutdownNow(); executor.shutdownNow(); }

    public record Task(String id, String code, String status, String stage, String modelName,
            Instant startedAt, Instant updatedAt, Instant snapshotAt, String skillDigest,
            CycleReport report, List<CycleSession.Chapter> chapters, List<CycleSession.Evidence> evidence,
            List<CycleSession.Event> trace, List<String> limitations, String error) {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String code;
        final String modelName;
        final Instant startedAt = Instant.now();
        volatile Instant updatedAt = startedAt;
        volatile String status = "RUNNING";
        volatile String stage = "准备研究";
        String error;
        String skillDigest;
        Instant snapshotAt;
        CycleReport report;
        volatile CycleSession session;
        Future<?> future;
        ScheduledFuture<?> timeout;
        Job(String code, String modelName) { this.code = code; this.modelName = modelName; }
        void fail(String message) { status = "FAILED"; stage = "研究未完成"; error = message; updatedAt = Instant.now(); }
        synchronized Task view() {
            boolean completed = "COMPLETED".equals(status);
            return new Task(id, code, status, stage, modelName, startedAt, updatedAt, snapshotAt, skillDigest,
                    completed ? report : null, completed ? session.chapters() : List.of(),
                    completed ? session.evidence() : List.of(), session == null ? List.of() : session.trace(),
                    CycleSession.LIMITATIONS, error);
        }
    }
}
