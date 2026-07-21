package de.visterion.dracul.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DecisionDocControllerTest {

    private MockMvc mvc(String path, long maxBytes) {
        return MockMvcBuilders.standaloneSetup(new DecisionDocController(path, maxBytes)).build();
    }

    @Test
    void blankPathReturns404() throws Exception {
        mvc("", 1_048_576).perform(get("/api/decision-doc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void presentFileReturnsMarkdown(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("doc.md");
        String content = "# Wie Dracul entscheidet\n\nSignale → Trigger → Agenten.\n";
        Files.writeString(f, content, StandardCharsets.UTF_8);

        mvc(f.toString(), 1_048_576).perform(get("/api/decision-doc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value(content));
    }

    @Test
    void nonexistentFileReturns404(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("missing.md");
        mvc(f.toString(), 1_048_576).perform(get("/api/decision-doc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void directoryReturns404(@TempDir Path dir) throws Exception {
        mvc(dir.toString(), 1_048_576).perform(get("/api/decision-doc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileExactlyMaxBytesReturns200(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("exact.md");
        byte[] bytes = new byte[64];
        java.util.Arrays.fill(bytes, (byte) 'a');
        Files.write(f, bytes);

        mvc(f.toString(), 64).perform(get("/api/decision-doc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value("a".repeat(64)));
    }

    @Test
    void fileOverMaxBytesReturns404(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("over.md");
        byte[] bytes = new byte[65];
        java.util.Arrays.fill(bytes, (byte) 'a');
        Files.write(f, bytes);

        mvc(f.toString(), 64).perform(get("/api/decision-doc"))
                .andExpect(status().isNotFound());
    }
}
