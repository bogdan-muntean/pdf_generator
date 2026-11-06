package com.pdfgen.adapters.storage;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.regex.Pattern;

/**
 * SnakeYAML configured for hand-written documents (ADR-0016):
 * <ul>
 *   <li>{@link SafeConstructor}: only plain maps, lists and scalars, never arbitrary classes;</li>
 *   <li>duplicate keys are an error;</li>
 *   <li>low alias limits (no "billion laughs");</li>
 *   <li>only {@code true/false} and null are typed; numbers, dates and {@code yes/no/on/off} stay text,
 *       so {@code 2023-07}, {@code 1.0} or {@code NO} are never silently converted.</li>
 * </ul>
 */
final class StrictYaml {

    private static final Pattern BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
    private static final int MAX_FILE_CODE_POINTS = 2 * 1024 * 1024;

    private StrictYaml() {
    }

    /** A new parser; {@link Yaml} instances are not thread-safe. */
    static Yaml create() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setAllowRecursiveKeys(false);
        options.setCodePointLimit(MAX_FILE_CODE_POINTS);
        DumperOptions dumperOptions = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumperOptions), dumperOptions, options,
                new TextFirstResolver());
    }

    private static final class TextFirstResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "tTfF");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }
}
