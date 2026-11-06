package com.pdfgen.adapters.cli;

import com.pdfgen.application.DocumentService;
import com.pdfgen.application.DocumentService.DocumentSummary;
import com.pdfgen.application.DocumentService.RenderedPdf;
import com.pdfgen.domain.DocumentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command line: {@code list}, {@code render <id>... | --all}, {@code serve}.
 * Spring-style property arguments ({@code --pdfgen.data-dir=...}) are left to Spring and hidden from picocli.
 */
@Component
public class CliCommands {

    private final DocumentService service;
    private final Path outputDir;

    public CliCommands(DocumentService service, @Value("${pdfgen.output-dir:output}") Path outputDir) {
        this.service = service;
        this.outputDir = outputDir;
    }

    public int execute(String[] args) {
        return execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
    }

    int execute(String[] args, PrintWriter out, PrintWriter err) {
        CommandLine commandLine = new CommandLine(new Root())
                .addSubcommand(new ListCommand(out))
                .addSubcommand(new RenderCommand(out, err))
                .addSubcommand(new ServeCommand(out));
        commandLine.setOut(out);
        commandLine.setErr(err);
        return commandLine.execute(withoutSpringProperties(args));
    }

    private static String[] withoutSpringProperties(String[] args) {
        return Arrays.stream(args).filter(arg -> !arg.matches("--[a-zA-Z]+\\.[^=]+=.*")).toArray(String[]::new);
    }

    @Command(name = "pdfgen", mixinStandardHelpOptions = true, version = "pdf-generator 0.1.0",
            description = "Generate PDF CVs and cover letters from YAML base documents and variants.")
    static final class Root implements Runnable {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "list", description = "List documents in the data directory.")
    final class ListCommand implements Callable<Integer> {
        private final PrintWriter out;

        ListCommand(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            List<DocumentSummary> documents = service.list();
            if (documents.isEmpty()) {
                out.println("No documents found.");
            }
            for (DocumentSummary doc : documents) {
                String kind = doc.parentId().map(parent -> "variant of " + parent).orElse("base");
                String error = doc.loadError().map(message -> "  [ERROR] " + message).orElse("");
                String type = doc.type().map(t -> t.key()).orElse("?");
                out.printf("%-30s %-13s %s%s%n", doc.id(), type, kind, error);
            }
            return 0;
        }
    }

    @Command(name = "render", description = "Render documents to PDF in the output directory.")
    final class RenderCommand implements Callable<Integer> {
        private final PrintWriter out;
        private final PrintWriter err;

        @Parameters(arity = "0..*", paramLabel = "ID", description = "Document ids (file names without .yaml).")
        List<String> ids = List.of();

        @Option(names = "--all", description = "Render every document.")
        boolean all;

        @Option(names = {"-o", "--output"}, description = "Output directory (default: ${DEFAULT-VALUE}).")
        Path output = outputDir;

        RenderCommand(PrintWriter out, PrintWriter err) {
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() throws IOException {
            List<String> targets = all ? service.list().stream().map(DocumentSummary::id).toList() : ids;
            if (targets.isEmpty()) {
                err.println("Nothing to render: give document ids or --all");
                return 2;
            }
            Files.createDirectories(output);
            int failures = 0;
            for (String id : targets) {
                try {
                    RenderedPdf pdf = service.render(id);
                    Path file = output.resolve(pdf.fileName());
                    Files.write(file, pdf.content());
                    pdf.warnings().forEach(warning -> err.println("WARN  " + warning));
                    out.println("OK    " + id + " -> " + file.toAbsolutePath().normalize());
                } catch (DocumentException e) {
                    failures++;
                    err.println("FAIL  " + id + ": " + e.getMessage());
                }
            }
            return failures == 0 ? 0 : 1;
        }
    }

    @Command(name = "serve", description = "Start the local web viewer on http://127.0.0.1:8080 (must be the first argument).")
    static final class ServeCommand implements Runnable {
        private final PrintWriter out;

        ServeCommand(PrintWriter out) {
            this.out = out;
        }

        @Override
        public void run() {
            out.println("'serve' must be the first argument, e.g.: java -jar pdf-generator.jar serve");
        }
    }
}
