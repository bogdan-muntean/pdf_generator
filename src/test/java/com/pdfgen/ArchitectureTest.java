package com.pdfgen;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Hexagonal dependency rule (CLAUDE.md §3, ADR-0004): dependencies point inward only. */
@AnalyzeClasses(packages = "com.pdfgen", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] FRAMEWORKS = {
            "org.springframework..", "com.fasterxml..", "tools.jackson..", "org.yaml..",
            "com.openhtmltopdf..", "org.apache.pdfbox..", "org.thymeleaf..", "org.jsoup..",
            "picocli..", "com.google.zxing.."};

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses().that().resideInAPackage("com.pdfgen.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS);

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses().that().resideInAPackage("com.pdfgen.application..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS);

    @ArchTest
    static final ArchRule domainDoesNotKnowOuterLayers = noClasses().that().resideInAPackage("com.pdfgen.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.pdfgen.application..", "com.pdfgen.adapters..",
                    "com.pdfgen.bootstrap..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses().that().resideInAPackage("com.pdfgen.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.pdfgen.adapters..", "com.pdfgen.bootstrap..");

    @ArchTest
    static final ArchRule adaptersDoNotKnowBootstrap = noClasses().that().resideInAPackage("com.pdfgen.adapters..")
            .should().dependOnClassesThat().resideInAPackage("com.pdfgen.bootstrap..");
}
