package com.astock.agent.agent.cycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 只读原始技能和指定书库；不将书本原则翻译成交易规则。 */
public final class CycleLibrary {
    private final Path skill;
    private final Path book;
    private final String python;
    public CycleLibrary(Path skill, Path book, String python) {
        this.skill = skill.toAbsolutePath().normalize();
        this.book = book.toAbsolutePath().normalize();
        this.python = python;
    }
    public String instructions() throws IOException {
        return read(skill, "SKILL.md") + "\n\n" + read(skill, "references/library-map.md");
    }
    public String topics() throws IOException { return read(book, "index/topics.zh.md"); }
    /** 只运行管理员配置的固定脚本，模型不能提供命令、选项或目录。 */
    public List<Hit> search(String query) throws IOException, InterruptedException {
        if (query == null || query.isBlank() || query.length() > 120
                || !query.matches("[\\p{L}\\p{N}\\s、，。？\"-]+") || query.strip().startsWith("-")) {
            throw new IllegalArgumentException("检索词无效");
        }
        Path script = skill.resolve("scripts/search_cycle.py").toRealPath();
        if (!script.startsWith(skill.toRealPath())) throw new IOException("检索脚本不可用");
        Path output = Files.createTempFile("cycle-search-", ".json");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(python, script.toString(), query,
                    "--root", book.getParent().getParent().toString(), "--limit", "6", "--json");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0 || Files.size(output) > 200_000) {
                throw new IOException("原书检索失败或超时");
            }
            var rows = new ObjectMapper().readTree(Files.readString(output));
            if (!rows.isArray()) throw new IOException("原书检索结果格式无效");
            List<Hit> hits = new ArrayList<>();
            Path chapterRoot = book.resolve("content/chapters").toRealPath();
            for (var row : rows) {
                Path file = Path.of(row.path("path").asText()).toRealPath();
                if (file.getParent().equals(chapterRoot)) {
                    hits.add(new Hit(file.getFileName().toString(), row.path("title").asText(), row.path("snippet").asText()));
                }
            }
            return List.copyOf(hits);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }
    public Page chapter(String id, int offset) throws IOException {
        if (id == null || !id.matches("[0-9]{3}-[a-zA-Z0-9-]+\\.md") || offset < 0) {
            throw new IllegalArgumentException("章节标识或偏移无效");
        }
        String text = read(book, "content/chapters/" + id);
        if (offset >= text.length()) throw new IllegalArgumentException("章节偏移超出范围");
        int end = Math.min(text.length(), offset + 12000);
        String title = text.lines().filter(line -> line.startsWith("# ")).findFirst().orElse(id).replaceFirst("^# ", "");
        return new Page(id, title, text.substring(offset, end), offset, end == text.length() ? -1 : end);
    }
    private String read(Path root, String relative) throws IOException {
        Path realRoot = root.toRealPath();
        Path file = root.resolve(relative).toRealPath();
        if (!file.startsWith(realRoot) || !Files.isRegularFile(file) || Files.size(file) > 2_000_000) {
            throw new IOException("资料文件不可用");
        }
        return Files.readString(file);
    }
    public record Page(String chapterId, String title, String text, int offset, int nextOffset) {}
    public record Hit(String chapterId, String title, String snippet) {}
}
