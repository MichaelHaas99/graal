package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.spi.NodeWithIdentity;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.java.MultiValue;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;

/**
 * The {@code ReadMultiValueNode} represents one returned value from a MultiValue. A MultiValue in
 * this context is a node which returns a nullable scalarized inline object. E.g. an
 * {@link InvokeNode} which has a scalarized return can return multiple values in registers.
 */
@NodeInfo(nameTemplate = "ReadMultiValue#{p#index}", cycles = CYCLES_0, size = SIZE_0)
public class ReadMultiValueNode extends FloatingNode implements LIRLowerable, Canonicalizable, NodeWithIdentity, Virtualizable {
    public static final NodeClass<ReadMultiValueNode> TYPE = NodeClass.create(ReadMultiValueNode.class);

    @Input MultiValue multiValueNode;

    private final int index;
    private final boolean isNonNull;
    private final boolean isOop;

    public int getIndex() {
        return index;
    }

    public MultiValue getMultiValueNode() {
        return multiValueNode;
    }

    public boolean isNonNull() {
        return isNonNull;
    }

    public boolean isOop() {
        return isOop;
    }

    private ReadMultiValueNode(NodeClass<? extends FloatingNode> c, Stamp stamp, MultiValue multiValueNode, int index, boolean isOop, boolean isNonNull) {
        super(c, stamp);
        this.multiValueNode = multiValueNode;
        this.index = index;
        this.isOop = isOop;
        this.isNonNull = isNonNull;
    }

    public ReadMultiValueNode(JavaType type, Assumptions assumptions, MultiValue multiValueNode, int index, boolean isOop, boolean isNonNull) {
        this(TYPE, StampFactory.forDeclaredType(assumptions, type, false).getTrustedStamp(), multiValueNode, index, isOop, isNonNull);
    }

    public static ReadMultiValueNode createNonNull(MultiValue multiValueNode, int index) {
        return new ReadMultiValueNode(TYPE, StampFactory.forKind(JavaKind.Int), multiValueNode, index, false, true);
    }

    public static ReadMultiValueNode createOop(JavaType type, Assumptions assumptions, MultiValue multiValueNode, int index) {
        return new ReadMultiValueNode(type, assumptions, multiValueNode, index, true, false);
    }

    public static ReadMultiValueNode createFieldValue(JavaType type, Assumptions assumptions, MultiValue multiValueNode, int index) {
        return new ReadMultiValueNode(type, assumptions, multiValueNode, index, false, false);
    }

    public InlineTypeNode getInlineTypeNode() {
        assert hasExactlyOneUsage() : "only one usage expected";
        return (InlineTypeNode) usages().first();
    }

    /**
     * Due to the cycle InvokeNode -> Framestate -> ReadMultiValueNode -> InvokeNode,
     * ReadMultiValueNodes can be scheduled before the InvokeNode.
     */
    @Override
    public void generate(NodeLIRBuilderTool generator) {
        // nothing to do
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }
        return this;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(multiValueNode.asNode());
        if (alias instanceof VirtualObjectNode virtualMultiValue) {
            if (isOop) {
                // Just replace this node with the MultiValue, the InlineTypeNode will then
                // replace itself with the virtual oop value
                tool.replaceWithVirtual(virtualMultiValue);
            } else {
                tool.delete();
            }
        }

    }
}
