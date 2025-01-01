package jdk.graal.compiler.nodes.extended;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaType;

/**
 * The {@link ProjNode} represents one returned value from a MultiNode returning a nullable
 * scalarized inline object. E.g. an InvokeNode which has a scalarized return can return multiple
 * values in registers.
 */
@NodeInfo(nameTemplate = "ProjNode")
public class ProjNode extends ValueNode implements LIRLowerable, Canonicalizable {
    public static final NodeClass<ProjNode> TYPE = NodeClass.create(ProjNode.class);

    @Input ValueNode src;

    int index;

    public boolean pointsToOopOrHub() {
        return index == 0;
    }

    protected ProjNode(NodeClass<? extends ProjNode> c, Stamp stamp) {
        super(c, stamp);
    }

    public ProjNode(Stamp stamp, InvokeNode src, int index) {
        this(TYPE, stamp);
        this.src = src;
        this.index = index;
    }

    public ProjNode(JavaType type, Assumptions assumptions, InvokeNode src, int index) {
        this(StampFactory.forDeclaredType(assumptions, type, false).getTrustedStamp(), src, index);
    }

    // TODO: Due to the cycle with the framestate, ProjNodes can be scheduled before the
    // InvokeNode, therefore trigger InvokeNode code generation.
    @Override
    public void generate(NodeLIRBuilderTool generator) {
        ((InvokeNode) src).generate(generator);
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (!src.getNodeClass().equals(InvokeNode.TYPE)) {
            // Inlining can replace the InvokeNode, therefore canonicalize
            if (pointsToOopOrHub()) {
                // The first ProjNode always represents the regular result
                return src;
            } else {
                return null;
            }
        }
        return this;
    }
}
