package com.astock.agent.agent.cycle;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CycleLibraryTest {
    Path temp = Files.createTempDirectory(Path.of("target"), "cycle-library-");
    CycleLibraryTest() throws Exception {}

    @Test
    void loadsOriginalSkillAndRestrictsChapterAccessToBook() throws Exception {
        Path skill = temp.resolve("skill");
        Path book = temp.resolve("book");
        Files.createDirectories(skill.resolve("references"));
        Files.createDirectories(book.resolve("content/chapters"));
        Files.createDirectories(book.resolve("index"));
        Files.writeString(skill.resolve("SKILL.md"), "原始技能：先检索再阅读");
        Files.writeString(skill.resolve("references/library-map.md"), "资料导航");
        Files.writeString(book.resolve("index/topics.zh.md"), "主题索引");
        Files.writeString(book.resolve("content/chapters/017-13.md"), "# 如何应对市场周期\n周期观点");
        CycleLibrary library = new CycleLibrary(skill, book, "python");
        assertThat(library.instructions()).contains("原始技能：先检索再阅读", "资料导航");
        assertThat(library.chapter("017-13.md", 0).text()).contains("周期观点");
        assertThatThrownBy(() -> library.chapter("../../SKILL.md", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> library.chapter("017-13.md", -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
