package com.pdfgen.domain.overlay;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One step of a variant (ADR-0005). Operations are applied in file order; {@code index} is the
 * 1-based position in the variant's {@code operations} list, used in error messages.
 */
public sealed interface Operation {

    int index();

    /** Display name, e.g. {@code "operation #3 (remove)"}. */
    default String label() {
        return "operation #" + index() + " (" + getClass().getSimpleName().toLowerCase() + ")";
    }

    /**
     * Shallow-replaces {@code fields} of the target section/item/bullet or reserved root, then removes the
     * {@code unset} fields (e.g. drop the QR code or the job title in a variant).
     */
    record Set(int index, IdPath target, Map<String, Object> fields, List<String> unset) implements Operation {
        public Set {
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
            unset = List.copyOf(unset);
        }

        public Set(int index, IdPath target, Map<String, Object> fields) {
            this(index, target, fields, List.of());
        }
    }

    /**
     * Inserts {@code element} into the child list of {@code into}.
     * {@code kind} is the key the author used ({@code section}, {@code item} or {@code bullet}).
     */
    record Add(int index, IdPath into, ElementKind kind, Object element, Placement placement) implements Operation {
    }

    /** Removes the section/item/bullet at {@code target}. */
    record Remove(int index, IdPath target) implements Operation {
    }

    /** Reorders the children of {@code target}: listed ids first, the rest keep their base order. */
    record Order(int index, IdPath target, List<String> ids) implements Operation {
        public Order {
            ids = List.copyOf(ids);
        }
    }

    /** What an {@link Add} inserts; each kind lives at a fixed depth of the tree. */
    enum ElementKind {
        SECTION(0), ITEM(1), BULLET(2);

        private final int parentDepth;

        ElementKind(int parentDepth) {
            this.parentDepth = parentDepth;
        }

        /** Depth of the path the element must be added {@code into}. */
        public int parentDepth() {
            return parentDepth;
        }

        public String key() {
            return name().toLowerCase();
        }
    }

    /** Where an added element goes relative to its siblings. */
    record Placement(Kind kind, Optional<String> anchorId) {

        public enum Kind { END, AFTER, BEFORE }

        public static Placement end() {
            return new Placement(Kind.END, Optional.empty());
        }

        public static Placement after(String id) {
            return new Placement(Kind.AFTER, Optional.of(id));
        }

        public static Placement before(String id) {
            return new Placement(Kind.BEFORE, Optional.of(id));
        }
    }
}
