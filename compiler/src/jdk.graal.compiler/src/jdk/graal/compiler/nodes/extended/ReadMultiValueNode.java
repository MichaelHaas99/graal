package jdk.graal.compiler.nodes.extended;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaType;

/**
 * The {@link ReadMultiValueNode} represents one returned value from a MultiValueNode. A
 * MultiValueNode in this context is a node which returns a nullable scalarized inline object. E.g.
 * an InvokeNode which has a scalarized return can return multiple values in registers.
 */
@NodeInfo(nameTemplate = "ReadMultiValue#{p#index}")
public class ReadMultiValueNode extends FloatingNode implements LIRLowerable, Canonicalizable {
    public static final NodeClass<ReadMultiValueNode> TYPE = NodeClass.create(ReadMultiValueNode.class);

    @Input ValueNode multiValueNode;

    private final int index;

    public int getIndex() {
        return index;
    }

    public ValueNode getMultiValueNode() {
        return multiValueNode;
    }

    public ReadMultiValueNode(Stamp stamp, ValueNode multiValueNode, int index) {
        this(TYPE, stamp, multiValueNode, index);
    }

    public ReadMultiValueNode(NodeClass<? extends FloatingNode> c, Stamp stamp, ValueNode multiValueNode, int index) {
        super(c, stamp);
        this.multiValueNode = multiValueNode;
        this.index = index;
    }

    public ReadMultiValueNode(JavaType type, Assumptions assumptions, ValueNode multiValueNode, int index) {
        this(StampFactory.forDeclaredType(assumptions, type, false).getTrustedStamp(), multiValueNode, index);
    }

    public void delete() {
        replaceAtUsages(null);
        safeDelete();
    }

    /**
     * Due to the cycle InvokeNode -> Framestate -> ReadMultiValueNode -> InvokeNode, ProjNodes can
     * be scheduled before the InvokeNode.
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

}
