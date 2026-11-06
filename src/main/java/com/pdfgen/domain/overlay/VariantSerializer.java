package com.pdfgen.domain.overlay;

import com.pdfgen.domain.tree.Tree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns operations back into the raw tree of a variant file: the inverse of {@link VariantParser}. */
public final class VariantSerializer {

    public Map<String, Object> toTree(String baseId, List<Operation> operations) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put(VariantParser.EXTENDS, baseId);
        tree.put("operations", operations.stream().map(VariantSerializer::operation).toList());
        return Tree.freezeMap(tree);
    }

    private static Map<String, Object> operation(Operation operation) {
        Map<String, Object> body = new LinkedHashMap<>();
        String type = switch (operation) {
            case Operation.Set set -> {
                body.put("target", set.target().toString());
                if (!set.fields().isEmpty()) {
                    body.put("fields", set.fields());
                }
                if (!set.unset().isEmpty()) {
                    body.put("unset", set.unset());
                }
                yield "set";
            }
            case Operation.Add add -> {
                if (!add.into().isRoot()) {
                    body.put("into", add.into().toString());
                }
                switch (add.placement().kind()) {
                    case AFTER -> body.put("after", add.placement().anchorId().orElseThrow());
                    case BEFORE -> body.put("before", add.placement().anchorId().orElseThrow());
                    case END -> { }
                }
                body.put(add.kind().key(), add.element());
                yield "add";
            }
            case Operation.Remove remove -> {
                body.put("target", remove.target().toString());
                yield "remove";
            }
            case Operation.Order order -> {
                if (!order.target().isRoot()) {
                    body.put("target", order.target().toString());
                }
                body.put("ids", order.ids());
                yield "order";
            }
        };
        return Map.of(type, body);
    }
}
