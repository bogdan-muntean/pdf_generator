package com.pdfgen.adapters.render;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PDFontSupplier;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the fonts themes may use (ADR-0002, ADR-0017):
 * <ul>
 *   <li>bundled, always available: {@code Carlito} (OFL, metric-compatible with Calibri),
 *       {@code Liberation Serif} / {@code Liberation Sans} 2.1.5 (OFL, metric-compatible with Times New Roman /
 *       Arial; cover letter themes, ADR-0026, ADR-0027),
 *       {@code FA Solid} / {@code FA Brands} (Font Awesome Free icon fonts, OFL);</li>
 *   <li>optional system fonts, never committed: {@code Calibri}, {@code Arial}, {@code Times New Roman}
 *       from {@code systemFontDir}.</li>
 * </ul>
 * Themes list a system font first and the bundled one as fallback, e.g. {@code font-family: Calibri, Carlito}
 * {@code "Times New Roman", "Liberation Serif"} or {@code Arial, "Liberation Sans"}.
 *
 * <p><b>Glyph substitution (GSUB) is disabled</b> on every font (ADR-0019). Otherwise PDFBox applies
 * ligatures such as "ti"/"ft", which Calibri maps to unrelated code points: the PDF looks fine but its
 * text extracts as "OpƟmized" / "SoŌware", which breaks copy-paste and applicant tracking systems.
 */
public final class FontRegistry {

    private static final Logger log = LoggerFactory.getLogger(FontRegistry.class);

    private record FontFile(String family, String file, int weight, FontStyle style) {
    }

    private static final List<FontFile> BUNDLED = List.of(
            new FontFile("Carlito", "Carlito-Regular.ttf", 400, FontStyle.NORMAL),
            new FontFile("Carlito", "Carlito-Bold.ttf", 700, FontStyle.NORMAL),
            new FontFile("Carlito", "Carlito-Italic.ttf", 400, FontStyle.ITALIC),
            new FontFile("Carlito", "Carlito-BoldItalic.ttf", 700, FontStyle.ITALIC),
            new FontFile("Liberation Serif", "LiberationSerif-Regular.ttf", 400, FontStyle.NORMAL),
            new FontFile("Liberation Serif", "LiberationSerif-Bold.ttf", 700, FontStyle.NORMAL),
            new FontFile("Liberation Serif", "LiberationSerif-Italic.ttf", 400, FontStyle.ITALIC),
            new FontFile("Liberation Serif", "LiberationSerif-BoldItalic.ttf", 700, FontStyle.ITALIC),
            new FontFile("Liberation Sans", "LiberationSans-Regular.ttf", 400, FontStyle.NORMAL),
            new FontFile("Liberation Sans", "LiberationSans-Bold.ttf", 700, FontStyle.NORMAL),
            new FontFile("Liberation Sans", "LiberationSans-Italic.ttf", 400, FontStyle.ITALIC),
            new FontFile("Liberation Sans", "LiberationSans-BoldItalic.ttf", 700, FontStyle.ITALIC),
            new FontFile("FA Solid", "fa-solid-900.ttf", 400, FontStyle.NORMAL),
            new FontFile("FA Brands", "fa-brands-400.ttf", 400, FontStyle.NORMAL));

    private static final List<FontFile> SYSTEM = List.of(
            new FontFile("Calibri", "calibri.ttf", 400, FontStyle.NORMAL),
            new FontFile("Calibri", "calibrib.ttf", 700, FontStyle.NORMAL),
            new FontFile("Calibri", "calibrii.ttf", 400, FontStyle.ITALIC),
            new FontFile("Calibri", "calibriz.ttf", 700, FontStyle.ITALIC),
            new FontFile("Arial", "arial.ttf", 400, FontStyle.NORMAL),
            new FontFile("Arial", "arialbd.ttf", 700, FontStyle.NORMAL),
            new FontFile("Arial", "ariali.ttf", 400, FontStyle.ITALIC),
            new FontFile("Arial", "arialbi.ttf", 700, FontStyle.ITALIC),
            new FontFile("Times New Roman", "times.ttf", 400, FontStyle.NORMAL),
            new FontFile("Times New Roman", "timesbd.ttf", 700, FontStyle.NORMAL),
            new FontFile("Times New Roman", "timesi.ttf", 400, FontStyle.ITALIC),
            new FontFile("Times New Roman", "timesbi.ttf", 700, FontStyle.ITALIC));

    private final Optional<Path> systemFontDir;
    /** Font file bytes, read once per process. */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /** @param systemFontDir where to look for Calibri/Arial; empty to use bundled fonts only (tests). */
    public FontRegistry(Optional<Path> systemFontDir) {
        this.systemFontDir = systemFontDir.filter(Files::isDirectory);
        if (systemFontDir.isPresent() && this.systemFontDir.isEmpty()) {
            log.warn("System font directory {} not found; using bundled fonts only", systemFontDir.get());
        }
    }

    /**
     * Loads every available font into {@code pdf} and registers it with {@code builder}.
     *
     * @return the parsed fonts; close them after the PDF has been saved
     */
    Closeable registerAll(PdfRendererBuilder builder, PDDocument pdf) {
        List<TrueTypeFont> loaded = new ArrayList<>();
        for (FontFile font : BUNDLED) {
            register(builder, pdf, font, bundled("/fonts/" + font.file()), loaded);
        }
        systemFontDir.ifPresent(dir -> SYSTEM.forEach(font -> {
            Path file = dir.resolve(font.file());
            if (Files.isRegularFile(file)) {
                register(builder, pdf, font, system(file), loaded);
            }
        }));
        return () -> {
            for (TrueTypeFont font : loaded) {
                font.close();
            }
        };
    }

    private static void register(PdfRendererBuilder builder, PDDocument pdf, FontFile font, byte[] bytes,
                                 List<TrueTypeFont> loaded) {
        try {
            TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(bytes));
            ttf.setEnableGsub(false);
            loaded.add(ttf);
            PDType0Font pdFont = PDType0Font.load(pdf, ttf, true);
            builder.useFont(new PDFontSupplier(pdFont), font.family(), font.weight(), font.style(), true);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load font " + font.file(), e);
        }
    }

    private byte[] bundled(String resource) {
        return cache.computeIfAbsent(resource, key -> {
            try (InputStream in = FontRegistry.class.getResourceAsStream(key)) {
                if (in == null) {
                    throw new IllegalStateException("Bundled font missing from the classpath: " + key);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private byte[] system(Path file) {
        return cache.computeIfAbsent(file.toString(), key -> {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
