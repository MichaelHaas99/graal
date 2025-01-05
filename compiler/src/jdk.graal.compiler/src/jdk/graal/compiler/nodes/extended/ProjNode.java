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
 * The {@link ProjNode} represents one returned value from a MultiNode. A MultiNode in this context
 * is a node which returns a nullable scalarized inline object. E.g. an InvokeNode which has a
 * scalarized return can return multiple values in registers.
 */
@NodeInfo(nameTemplate = "ProjNode")
public class ProjNode extends FloatingNode implements LIRLowerable, Canonicalizable {
    public static final NodeClass<ProjNode> TYPE = NodeClass.create(ProjNode.class);

    @Input ValueNode multiNode;

    private final int index;

    public int getIndex() {
        return index;
    }

    public boolean pointsToOopOrHub() {
        return index == 0;
    }

    public ValueNode getMultiNode() {
        return multiNode;
    }

    public ProjNode(Stamp stamp, ValueNode multiNode, int index) {
        super(TYPE, stamp);
        this.multiNode = multiNode;
        this.index = index;
    }

    public ProjNode(JavaType type, Assumptions assumptions, ValueNode multiNode, int index) {
        this(StampFactory.forDeclaredType(assumptions, type, false).getTrustedStamp(), multiNode, index);
    }

    public void delete() {
        replaceAtUsages(null);
        safeDelete();
    }

    /**
     * TODO: Due to the cycle InvokeNode -> Framestate -> ProjNode -> InvokeNode, ProjNodes can be
     * scheduled before the InvokeNode.
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
