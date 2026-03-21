package jdk.graal.compiler.nodes;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.extended.ReadMultiValueNode;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * This interface marks nodes which can return a value object in scalarized form.
 */
public interface MultiValue extends ValueNodeInterface {

    static void verifyNode(ValueNode node) {
        node.assertTrue(node.usages().stream().allMatch(usage -> usage instanceof ReadMultiValueNode), "Illegal usage of %s", node);
    }

    /**
     * {@link Invoke} is not a multi value nodes if the method has no scalarized return.
     */
    boolean isMultiValue();

    default boolean onlyReadMuliValueUsages() {
        return asNode().usages().stream().allMatch(usage -> usage instanceof ReadMultiValueNode);
    }

    ResolvedJavaType getMultiValueType();

    default ReadMultiValueNode getOop() {
        return getUsage(asNode(), r -> ((ReadMultiValueNode) r).isOop());
    }

    default ReadMultiValueNode getNonNull() {
        return getUsage(asNode(), r -> ((ReadMultiValueNode) r).isNonNull());
    }

    default List<ReadMultiValueNode> getFieldValues() {
        return getUsages(asNode(), r -> {
            ReadMultiValueNode read = (ReadMultiValueNode) r;
            return !read.isOop() && !read.isNonNull();
        });
    }

    default ReadMultiValueNode getFieldValue(int index) {
        for (ReadMultiValueNode fieldValue : getFieldValues()) {
            if (fieldValue.getFieldIndex() == index) {
                return fieldValue;
            }
        }
        return null;
    }

    private static ReadMultiValueNode getUsage(ValueNode node, Predicate<Node> p) {
        return (ReadMultiValueNode) node.usages().stream().filter(p).findFirst().orElse(null);
    }

    private static List<ReadMultiValueNode> getUsages(ValueNode node, Predicate<Node> p) {
        return node.usages().stream().filter(p).map(e -> (ReadMultiValueNode) e).collect(Collectors.toList());
    }
}
