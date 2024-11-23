package jdk.graal.compiler.nodes;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

/**
 * Flat arrays use a different hub compared to normal arrays. When the hub of flat arrays needs to
 * be loaded, this node has to be inserted in between to avoid constant folding with a hub of a
 * normal array hub.
 */
@NodeInfo(cycles = CYCLES_0, size = SIZE_0)
public class EnsureRuntimeHubUsageNode extends FloatingGuardedNode implements LIRLowerable {

    public static final NodeClass<EnsureRuntimeHubUsageNode> TYPE = NodeClass.create(EnsureRuntimeHubUsageNode.class);
    @Input ValueNode object;

    protected EnsureRuntimeHubUsageNode(NodeClass<? extends FloatingGuardedNode> c, ValueNode object, GuardingNode guard) {
        super(c, StampFactory.objectNonNull(), guard);
        this.object = object;
    }

    public static EnsureRuntimeHubUsageNode create(ValueNode object, ValueNode guard) {
        return new EnsureRuntimeHubUsageNode(TYPE, object, (GuardingNode) guard);
    }

    public ValueNode object() {
        return object;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        if (generator.hasOperand(object)) {
            generator.setResult(this, generator.operand(object));
        }
    }
}
