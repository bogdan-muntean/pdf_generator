package com.pdfgen.adapters.web;

import com.pdfgen.bootstrap.PdfGeneratorApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Backup download, restore upload and cascading delete over HTTP, with their request guards (ADR-0028/0029). */
@SpringBootTest(classes = PdfGeneratorApplication.class)
@AutoConfigureMockMvc
class BackupAndDeleteHttpTest {

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pdfgen.data-dir", () -> data.toString());
        registry.add("pdfgen.system-font-dir", () -> "");
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void samples() throws IOException {
        for (String name : List.of("cv-sample.yaml", "cv-sample-qa.yaml", "cv-sample-qa-acme.yaml")) {
            Files.copy(Path.of("samples", name), data.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void backupThenRestore() throws Exception {
        mvc.perform(get("/api/backup")).andExpect(status().isForbidden());
        byte[] zip = mvc.perform(get("/api/backup").header("X-Pdfgen-Action", "backup"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern("attachment; filename=\"pdf-generator-backup-.*\\.zip\"")))
                .andReturn().getResponse().getContentAsByteArray();

        Files.writeString(data.resolve("cv-later.yaml"), "meta: {type: cv}");
        mvc.perform(post("/api/backup/restore").contentType("application/zip").content(zip))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/backup/restore").header("X-Pdfgen-Action", "restore")
                        .header("Origin", "https://evil.example").contentType("application/zip").content(zip))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/backup/restore").header("X-Pdfgen-Action", "restore")
                        .contentType("application/zip").content(zip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed[0]").value("cv-later"))
                .andExpect(jsonPath("$.restored.length()").value(3))
                .andExpect(jsonPath("$.safetyBackup").value(org.hamcrest.Matchers.startsWith(".history/restore-")));
        assertThat(data.resolve("cv-later.yaml")).doesNotExist();

        mvc.perform(post("/api/backup/restore").header("X-Pdfgen-Action", "restore")
                        .contentType("application/zip").content("not a zip".getBytes()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Not a valid backup")));
    }

    @Test
    void cascadingDeleteNeedsTheHeaderAndTheConfirmedList() throws Exception {
        mvc.perform(delete("/api/documents/cv-sample").param("cascade", "true")
                        .header("X-Pdfgen-Expected", "cv-sample-qa-acme,cv-sample-qa,cv-sample"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/documents/cv-sample").header("X-Pdfgen-Action", "delete"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("cv-sample-qa extends it")));
        mvc.perform(delete("/api/documents/cv-sample").param("cascade", "true").header("X-Pdfgen-Action", "delete")
                        .header("X-Pdfgen-Expected", "cv-sample-qa,cv-sample"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/documents/cv-sample").param("cascade", "true").header("X-Pdfgen-Action", "delete")
                        .header("X-Pdfgen-Expected", "cv-sample-qa-acme,cv-sample-qa,cv-sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted[0]").value("cv-sample-qa-acme"))
                .andExpect(jsonPath("$.deleted[2]").value("cv-sample"));
        assertThat(data.resolve("cv-sample.yaml")).doesNotExist();
        assertThat(data.resolve("cv-sample-qa-acme.yaml")).doesNotExist();
    }
}
