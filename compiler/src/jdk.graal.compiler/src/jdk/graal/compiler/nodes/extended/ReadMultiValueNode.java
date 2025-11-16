package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.MultiValue;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.FloatingNode;
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
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@code ReadMultiValueNode} represents one returned value from a MultiValue. A MultiValue in
 * this context is a node which returns a nullable scalarized inline object. E.g. an
 * {@link InvokeNode} which has a scalarized return can return multiple values in registers.
 */
@NodeInfo(nameTemplate = "ReadMultiValue#{p#index}", cycles = CYCLES_0, size = SIZE_0)
public class ReadMultiValueNode extends FloatingNode implements LIRLowerable, Canonicalizable, Virtualizable {
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
        return new ReadMultiValueNode(TYPE, StampFactory.forInteger(JavaKind.Int, 0, 1), multiValueNode, index, false, true);
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
                /*
                 * Just replace this node with the MultiValue, the InlineType node will then replace
                 * itself with the virtual oop value.
                 */
                tool.replaceWithVirtual(virtualMultiValue);
                return;
            }
            if (isNonNull) {
                tool.replaceWith(tool.getNonNull(virtualMultiValue));
                return;
            }
            /*
             * The ReadMultiValue node with index is the oop. Field values start with index 1, so we
             * need to subtract 1. The nonNull info has the highest index.
             */
            tool.replaceWith(tool.getEntry(virtualMultiValue, getIndex() - 1));
        }

    }

    public static MultiValues createNodes(ScalarizationNode node, Assumptions assumptions) {
        return createNodes(node, node.getType(), assumptions);
    }

    public static MultiValues createNodes(MultiValue node, ResolvedJavaType type, Assumptions assumptions) {
        ReadMultiValueNode oop = ReadMultiValueNode.createOop(type, assumptions, node, 0);

        ResolvedJavaField[] fields = type.getInstanceFields(true);
        ReadMultiValueNode[] fieldValues = new ReadMultiValueNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            fieldValues[i] = ReadMultiValueNode.createFieldValue(fields[i].getType(), assumptions, node, i + 1);
        }
        ReadMultiValueNode nonNull = ReadMultiValueNode.createNonNull(
                        node, fields.length + 1);
        return new MultiValues(oop, fieldValues, nonNull);
    }

    public record MultiValues(ValueNode oop, ValueNode[] fieldValues, ValueNode nonNull) {

        public MultiValues add(StructuredGraph graph) {
            ValueNode oop = graph.addOrUnique(this.oop);
            ValueNode nonNull = graph.addOrUnique(this.nonNull);
            ValueNode[] fieldValues = new ValueNode[this.fieldValues.length];
            for (int i = 0; i < this.fieldValues.length; i++) {
                fieldValues[i] = graph.addOrUnique(this.fieldValues[i]);
            }
            return new MultiValues(oop, fieldValues, nonNull);
        }
    }
}
