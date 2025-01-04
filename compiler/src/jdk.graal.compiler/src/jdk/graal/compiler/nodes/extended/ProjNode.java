package jdk.graal.compiler.nodes.extended;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.InvokeNode;
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

    @Input ValueNode src;

    private final int index;

    public boolean pointsToOopOrHub() {
        return index == 0;
    }


    public ProjNode(Stamp stamp, InvokeNode src, int index) {
        super(TYPE, stamp);
        this.src = src;
        this.index = index;
    }

    public ProjNode(JavaType type, Assumptions assumptions, InvokeNode src, int index) {
        this(StampFactory.forDeclaredType(assumptions, type, false).getTrustedStamp(), src, index);
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
