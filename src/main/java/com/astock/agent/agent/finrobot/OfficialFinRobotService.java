package com.astock.agent.agent.finrobot;

import com.astock.agent.agent.model.AiModelProperties;
import com.astock.agent.agent.model.ModelNotAvailableException;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 官方引擎的有界异步任务；报告失败不会调用旧模板回退。 */
public final class OfficialFinRobotService implements AutoCloseable {
    @FunctionalInterface public interface Runner {
        JsonNode run(String code, AiModelProperties.Model model, Path output) throws Exception;
    }
    private final OfficialFinRobotProperties properties;
    private final AiModelProperties models;
    private final Runner runner;
    private final Path reports;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore capacity;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public OfficialFinRobotService(OfficialFinRobotProperties properties, AiModelProperties models, Runner runner) {
        this.properties = properties; this.models = models; this.runner = runner;
        this.reports = Path.of(properties.reportPath()).toAbsolutePath().normalize();
        this.capacity = new Semaphore(properties.maxConcurrentTasks());
    }

    public Map<String, Object> runtime() {
        boolean installed = Files.isRegularFile(Path.of(properties.python()))
                && Files.isRegularFile(Path.of("third_party/finrobot/UPSTREAM.json"));
        return Map.of("engine", properties.engine(), "installed", installed,
                "upstreamCommit", OfficialFinRobotWorker.UPSTREAM,
                "message", installed ? "官方 FinRobot Equity · 八个专题 Agent" : "请运行 scripts/setup-finrobot.ps1 安装 Python 依赖");
    }

    public synchronized Task start(String code, String modelId) {
        SecurityId.parse(code);
        String selectedId = modelId == null || modelId.isBlank() ? models.roles().get("finrobot-research") : modelId;
        var model = selectedId == null ? null : models.models().get(selectedId);
        if (model == null || !model.configured()) throw new ModelNotAvailableException();
        for (Job job : jobs.values()) if (job.code.equals(code) && job.modelId.equals(selectedId)
                && job.status.equals("RUNNING")) return view(job);
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "FinRobot 研究任务繁忙");
        if (jobs.size() >= 100) jobs.values().removeIf(job -> !job.status.equals("RUNNING"));
        Job job = new Job(code, selectedId, model.model());
        jobs.put(job.id, job);
        job.timeout = timer.schedule(() -> {
            synchronized (job) {
                if (job.status.equals("RUNNING")) {
                    job.fail("研究超时，请稍后重试");
                    if (job.worker != null) job.worker.interrupt();
                }
            }
        }, properties.taskTimeout().toMillis(), TimeUnit.MILLISECONDS);
        job.future = executor.submit(() -> {
            try {
                synchronized (job) {
                    job.worker = Thread.currentThread();
                    if (!job.status.equals("RUNNING")) return;
                }
                JsonNode report = runner.run(code, model, reports.resolve(job.id));
                if (report == null || !code.equals(report.path("ticker").asText())
                        || !"finrobot-official-v1".equals(report.path("schema").asText())
                        || !OfficialFinRobotWorker.UPSTREAM.equals(report.path("upstreamCommit").asText())) {
                    throw new IllegalStateException("报告契约不匹配");
                }
                synchronized (job) {
                    if (!job.status.equals("RUNNING")) return;
                    String status = report.path("status").asText();
                    if (!Set.of("COMPLETED", "PARTIAL").contains(status)) {
                        job.fail("官方专题生成失败，请检查所选模型的兼容性与连接配置");
                    } else {
                        job.report = report; job.status = status; job.updatedAt = Instant.now();
                    }
                }
            } catch (Exception failure) {
                synchronized (job) { if (job.status.equals("RUNNING")) job.fail("官方引擎执行失败，请检查 Python 环境、模型连接和数据状态"); }
            } finally {
                job.timeout.cancel(false); capacity.release();
            }
        });
        return view(job);
    }

    public Task get(String id) { return view(requireJob(id)); }

    public Task latest(String code) {
        SecurityId.parse(code);
        return jobs.values().stream().filter(job -> job.code.equals(code))
                .max(Comparator.comparing(job -> job.startedAt)).map(this::view).orElse(null);
    }

    public Task cancel(String id) {
        Job job = requireJob(id);
        synchronized (job) {
            if (job.status.equals("RUNNING")) {
                job.status = "CANCELLED"; job.updatedAt = Instant.now();
                if (job.worker != null) job.worker.interrupt();
                job.timeout.cancel(false);
            }
        }
        return view(job);
    }

    public Path artifact(String id, String kind) throws Exception {
        Job job = requireJob(id);
        if (!Set.of("COMPLETED", "PARTIAL").contains(job.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "报告尚未完成");
        String file = switch (kind) { case "html" -> "report.html"; case "json" -> "bundle.json"; case "evidence" -> "evidence.json";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知报告格式"); };
        Path result = reports.resolve(id).resolve(file);
        if (!Files.isRegularFile(result) || Files.isSymbolicLink(result)
                || !result.toRealPath().startsWith(reports.toRealPath()) || Files.size(result) > 20_000_000) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告文件不可用");
        }
        return result;
    }

    private Job requireJob(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "研究任务不存在或服务已重启");
        return job;
    }

    private Task view(Job job) {
        synchronized (job) {
            String stage = job.status.equals("RUNNING") ? "准备 A 股证据" : job.status;
            int completed = 0;
            try {
                Path progress = reports.resolve(job.id).resolve("progress.json");
                if (Files.isRegularFile(progress) && Files.size(progress) < 4096) {
                    JsonNode node = mapper.readTree(progress.toFile());
                    stage = node.path("stage").asText(stage); completed = node.path("completed").asInt();
                }
            } catch (Exception ignored) { /* 原子进度更新期间沿用任务状态。 */ }
            return new Task(job.id, job.code, job.modelName, job.status, stage, completed,
                    job.startedAt, job.updatedAt, job.report, job.error);
        }
    }

    public record Task(String id, String code, String modelName, String status, String stage,
            int completed, Instant startedAt, Instant updatedAt, JsonNode report, String error) {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String code, modelId, modelName;
        final Instant startedAt = Instant.now();
        volatile Instant updatedAt = startedAt;
        volatile String status = "RUNNING";
        JsonNode report; String error; Future<?> future; Thread worker; ScheduledFuture<?> timeout;
        Job(String code, String modelId, String modelName) { this.code = code; this.modelId = modelId; this.modelName = modelName; }
        void fail(String message) { status = "FAILED"; error = message; report = null; updatedAt = Instant.now(); }
    }

    @PreDestroy @Override public void close() {
        jobs.values().forEach(job -> { synchronized (job) {
            if (job.status.equals("RUNNING")) { job.fail("服务正在停止"); if (job.worker != null) job.worker.interrupt(); }
        }});
        executor.shutdownNow(); timer.shutdownNow();
    }
}
