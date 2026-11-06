package com.pdfgen.adapters.render;

import com.pdfgen.application.AssetSource;
import com.pdfgen.application.ThemeCatalog;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.model.Document;
import com.pdfgen.domain.model.Section;
import com.pdfgen.domain.model.SectionContent;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders a {@link Document} to HTML with its theme: {@code classpath:themes/<type>/<theme>/document.html}
 * plus {@code style.css} from the same folder (ADR-0006). Text is always escaped by the templates.
 */
public final class HtmlRenderer {

    private static final Pattern THEME_NAME = Pattern.compile("[a-z0-9-]+");

    private final TemplateEngine engine;
    private final AssetSource assets;
    private final ThemeCatalog themes;
    private final ThemeHelper helper = new ThemeHelper();

    public HtmlRenderer(AssetSource assets) {
        this(assets, new ClasspathThemeCatalog());
    }

    public HtmlRenderer(AssetSource assets, ThemeCatalog themes) {
        this.assets = assets;
        this.themes = themes;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("themes/");
        resolver.setSuffix("/document.html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
    }

    public String render(Document document) {
        String themePath = themePath(document);
        Context context = new Context(Locale.forLanguageTag(document.meta().lang()));
        context.setVariables(variables(document, themePath));
        try {
            return engine.process(themePath, context);
        } catch (TemplateInputException e) {
            throw new DocumentException("Theme '" + document.meta().theme() + "' cannot be rendered: "
                    + e.getMessage(), e);
        }
    }

    private Map<String, Object> variables(Document document, String themePath) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("doc", document);
        variables.put("t", helper);
        variables.put("css", readCss(themePath));
        variables.put("photo", document.person().photo()
                .map(path -> Images.roundPhotoDataUri(assets.load(path), path))
                .orElse(null));
        Map<String, String> qrCodes = new HashMap<>();
        for (Section section : document.sections()) {
            if (section.content() instanceof SectionContent.Contact contact) {
                contact.qr().ifPresent(qr -> qrCodes.put(section.id(), Images.qrCodeDataUri(qr.url())));
            }
        }
        variables.put("qrCodes", qrCodes);
        return variables;
    }

    private String themePath(Document document) {
        String theme = document.meta().theme();
        if (!THEME_NAME.matcher(theme).matches()) {
            throw new DocumentException("Invalid theme name '" + theme + "' (use lowercase letters, digits, '-')");
        }
        List<String> available = themes.themes(document.meta().type());
        if (!available.contains(theme)) {
            throw new DocumentException("Theme '" + theme + "' does not exist for " + document.meta().type().key()
                    + "; available: " + String.join(", ", available));
        }
        return document.meta().type().key() + "/" + theme;
    }

    private static String readCss(String themePath) {
        String resource = "/themes/" + themePath + "/style.css";
        try (InputStream in = HtmlRenderer.class.getResourceAsStream(resource)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
