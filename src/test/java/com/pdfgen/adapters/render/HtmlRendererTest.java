package com.pdfgen.adapters.render;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.pdfgen.adapters.storage.FileDocumentRepository;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.mapping.DocumentMapper;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.tree.Tree;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.pdfgen.domain.TestTrees.baseCv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HtmlRendererTest {

    private final HtmlRenderer renderer = new HtmlRenderer(new FileDocumentRepository(Path.of("samples")));
    private final DocumentMapper mapper = new DocumentMapper();

    private Document document(Map<String, Object> overrides) {
        Map<String, Object> tree = new LinkedHashMap<>(baseCv());
        tree.putAll(overrides);
        return mapper.map("'test'", Tree.freezeMap(tree));
    }

    @Test
    void rendersSectionTitlesInDocumentOrder() {
        var html = Jsoup.parse(renderer.render(document(Map.of())));
        assertThat(html.select("h2.section-title").eachText())
                .containsExactly("SUMMARY", "WORK EXPERIENCE", "SKILLS");
        assertThat(html.select(".entry-heading").eachText()).containsExactly("Acme – Dev", "Globex – Intern");
        assertThat(html.select(".name").text()).isEqualTo("Jane Doe");
    }

    @Test
    void entriesSectionsUseARepeatingTableHeader() {
        var html = Jsoup.parse(renderer.render(document(Map.of())));
        Element table = html.selectFirst("table.entries");
        assertThat(table).isNotNull();
        assertThat(table.select("thead h2").text()).isEqualTo("WORK EXPERIENCE");
    }

    @Test
    void escapesUserText() {
        var html = renderer.render(document(Map.of("person", Map.of("name", "<script>alert(1)</script>"))));
        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
    }

    @Test
    void qrCodeDecodesBackToTheUrl() throws Exception {
        var doc = document(Map.of("sections", List.of(Map.of("id", "contact", "kind", "contact",
                "title", "CONTACT", "items", List.of(),
                "qr", Map.of("url", "https://example.com/me", "caption", "Scan or use {url} today")))));
        var html = Jsoup.parse(renderer.render(doc));

        String src = html.selectFirst("img.qr").attr("src");
        byte[] png = Base64.getDecoder().decode(src.substring(src.indexOf(',') + 1));
        var image = ImageIO.read(new ByteArrayInputStream(png));
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        assertThat(new QRCodeReader().decode(bitmap).getText()).isEqualTo("https://example.com/me");
        assertThat(html.selectFirst(".qr-caption").text()).isEqualTo("Scan or use https://example.com/me today");
    }

    @Test
    void unknownThemeIsAClearError() {
        var doc = document(Map.of("meta", Map.of("type", "cv", "theme", "nope")));
        assertThatThrownBy(() -> renderer.render(doc))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("Theme 'nope' does not exist");
    }
}
