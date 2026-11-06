package com.pdfgen.adapters.web;

import com.pdfgen.bootstrap.PdfGeneratorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = PdfGeneratorApplication.class,
        properties = {"pdfgen.data-dir=samples", "pdfgen.system-font-dir="})
@AutoConfigureMockMvc
class DocumentControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsDocumentsWithTheirBase() throws Exception {
        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItems("cv-sample", "cv-sample-qa")))
                .andExpect(jsonPath("$[?(@.id == 'cv-sample-qa')].parentId").value("cv-sample"));
    }

    @Test
    void servesPdfInlineOrAsDownload() throws Exception {
        mvc.perform(get("/api/documents/cv-sample-qa/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("X-Pdfgen-Warnings", containsString("soft-skills")));
        mvc.perform(get("/api/documents/cv-sample/pdf").param("download", "true"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void reportsErrorsAsJson() throws Exception {
        mvc.perform(get("/api/documents/does-not-exist/pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Document 'does-not-exist' not found"));
    }

    @Test
    void rejectsRequestsForForeignHosts() throws Exception {
        mvc.perform(get("/api/documents").header("Host", "evil.example:8080"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk());
    }

    @Test
    void shutdownRequiresTheActionHeader() throws Exception {
        mvc.perform(post("/api/shutdown")).andExpect(status().isForbidden());
    }

    @Test
    void pingIdentifiesTheApp() throws Exception {
        mvc.perform(get("/api/ping")).andExpect(jsonPath("$.app").value("pdf-generator"));
    }

    @Test
    void exposesAChangeToken() throws Exception {
        mvc.perform(get("/api/changes")).andExpect(status().isOk()).andExpect(jsonPath("$.version").isNumber());
    }
}
