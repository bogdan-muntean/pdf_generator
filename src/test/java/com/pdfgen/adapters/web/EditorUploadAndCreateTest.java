package com.pdfgen.adapters.web;

import com.pdfgen.bootstrap.PdfGeneratorApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Photo upload, thumbnails, new documents and the cross-site guard over HTTP (ADR-0024, ADR-0026). */
@SpringBootTest(classes = PdfGeneratorApplication.class)
@AutoConfigureMockMvc
class EditorUploadAndCreateTest {

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pdfgen.data-dir", () -> data.toString());
        registry.add("pdfgen.system-font-dir", () -> "");
    }

    @Autowired
    MockMvc mvc;

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    @Test
    void uploadsAPhotoAndServesItsThumbnail() throws Exception {
        mvc.perform(post("/api/assets/images").param("name", "../My Photo.jpeg")
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("assets/my-photo.jpg"));
        mvc.perform(get("/api/assets/images")).andExpect(jsonPath("$[0]").value("assets/my-photo.jpg"));
        mvc.perform(get("/api/assets/image").param("path", "assets/my-photo.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        Files.writeString(data.resolve("private.yaml"), "x: 1");
        mvc.perform(get("/api/assets/image").param("path", "private.yaml")).andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/image").param("path", "assets/../private.yaml")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsWrongTypesFakeImagesAndHugeFiles() throws Exception {
        mvc.perform(post("/api/assets/images").contentType(MediaType.MULTIPART_FORM_DATA).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/assets/images").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/assets/images").contentType(MediaType.IMAGE_PNG).content("<svg onload=x>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Only JPEG and PNG photos are supported"));
        mvc.perform(post("/api/assets/images").contentType(MediaType.IMAGE_JPEG)
                        .content(new byte[10 * 1024 * 1024 + 1]))
                .andExpect(status().is(413));
    }

    @Test
    void blocksStateChangingRequestsFromOtherSites() throws Exception {
        mvc.perform(post("/api/assets/images").header("Origin", "https://evil.example")
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/documents").header("Sec-Fetch-Site", "cross-site")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"x\",\"type\":\"cv\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/documents").header("Origin", "null")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"x\",\"type\":\"cv\"}"))
                .andExpect(status().isForbidden());
        // the editor itself (same origin, or the Vite dev server on another localhost port) is allowed
        mvc.perform(post("/api/assets/images").header("Origin", "http://localhost:5173")
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg()))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/documents").header("Origin", "https://evil.example")).andExpect(status().isOk());
    }

    @Test
    void createsDocumentsOfTheChosenType() throws Exception {
        mvc.perform(post("/api/documents").header("Origin", "http://127.0.0.1:8080")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"letter-en\",\"type\":\"cover-letter\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/documents/letter-en/edit"))
                .andExpect(jsonPath("$.tree.letter.closing").value("Sincerely,"))
                .andExpect(jsonPath("$.rules.letter").value(true))
                .andExpect(jsonPath("$.rules.sectionKinds[1]").value("lines"));
        mvc.perform(get("/api/documents"))
                .andExpect(jsonPath("$[?(@.id == 'letter-en')].type").value("cover-letter"));
        mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"y\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Choose the type")));
    }
}
