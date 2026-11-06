package com.pdfgen.domain.model;

/**
 * A titled block of a document.
 *
 * @param pageBreakBefore layout hint: start this section on a new page
 */
public record Section(String id, String title, boolean pageBreakBefore, SectionContent content) {

    /** The kind name used in data files and template fragments. */
    public String kind() {
        return content.kind();
    }
}
