package com.astock.agent.agent.uzi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 固定参数的官方 UZI Python 适配器。
 *
 * <p>该类不接受命令、URL、文件路径或环境变量作为请求参数。Python 路径、UZI 根目录和
 * 超时时间都来自管理员配置；模型名只作为非敏感路由提示传递，密钥仍由本地环境负责。</p>
 */
final class UziPythonWorker {
    private static final long MAX_JSON_BYTES = 10_000_000L;
    private final UziProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    UziPythonWorker(UziProperties properties) {
        this.properties = properties == null
                ? new UziProperties(null, null, null, null, 1) : properties;
    }

    UziResearchBundle run(String code, String depth, String school, String modelName,
            Path outputDir) throws Exception {
        validate(code, depth, school);
        Path root = Path.of(properties.rootPath()).toAbsolutePath().normalize().toRealPath();
        Path officialScript = root.resolve("run.py").toRealPath();
        if (!officialScript.startsWith(root) || !Files.isRegularFile(officialScript)) {
            throw new IOException("UZI run.py 不可用");
        }
        Path worker = Path.of("scripts", "uzi-worker.py").toAbsolutePath().normalize().toRealPath();
        if (!Files.isRegularFile(worker) || !worker.getFileName().toString().equals("uzi-worker.py")) {
            throw new IOException("项目内 UZI Worker 不可用");
        }
        Path output = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(output);
        if (!output.startsWith(Path.of(properties.reportPath()).toAbsolutePath().normalize())) {
            throw new IOException("UZI 输出目录不在受限报告目录内");
        }

        List<String> command = new ArrayList<>(List.of(properties.python(), worker.toString(),
                "--uzi-root", root.toString(), "--code", code, "--depth", depth,
                "--output-dir", output.toString()));
        if (school != null && !school.isBlank()) {
            command.add("--school");
            command.add(school);
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("UZI_CLI_ONLY", "1");
            builder.environment().put("UZI_NO_AUTO_OPEN", "1");
            if (modelName != null && !modelName.isBlank()) {
                builder.environment().put("UZI_MODEL_NAME", modelName);
            }
            process = builder.start();
            boolean finished = process.waitFor(properties.taskTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroy(process);
                throw new IOException("UZI Python Worker 超时");
            }
            if (process.exitValue() != 0) {
                throw new IOException("UZI 官方脚本返回非零状态 " + process.exitValue());
            }
        } catch (InterruptedException interrupted) {
            if (process != null) destroy(process);
            Thread.currentThread().interrupt();
            throw new IOException("UZI Python Worker 被中断", interrupted);
        } finally {
            if (process != null && process.isAlive()) destroy(process);
        }

        Path bundleFile = output.resolve("bundle.json");
        if (!Files.isRegularFile(bundleFile) || Files.isSymbolicLink(bundleFile)
                || Files.size(bundleFile) > MAX_JSON_BYTES) {
            throw new IOException("UZI bundle.json 不可用");
        }
        UziResearchBundle bundle = mapper.readValue(Files.readString(bundleFile), UziResearchBundle.class);
        if (!code.equals(bundle.ticker())) throw new IOException("UZI bundle 证券代码不匹配");
        return bundle;
    }

    private static void validate(String code, String depth, String school) {
        if (code == null || !code.matches("\\d{6}")) throw new IllegalArgumentException("证券代码无效");
        if (depth == null || !List.of("lite", "medium", "deep").contains(depth)) {
            throw new IllegalArgumentException("UZI 分析深度无效");
        }
        if (school != null && !school.isBlank() && !school.matches("[A-I]")) {
            throw new IllegalArgumentException("UZI 投资流派无效");
        }
    }

    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
