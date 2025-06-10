package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.graph.Node.NodeIntrinsicFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.UnaryOpLogicNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.TriState;

/**
 * Checks if an object has identity.
 */
@NodeInfo(cycles = CYCLES_8, size = SIZE_8)
@NodeIntrinsicFactory
public class HasIdentityNode extends UnaryOpLogicNode implements Lowerable, Virtualizable {
    public static final NodeClass<HasIdentityNode> TYPE = NodeClass.create(HasIdentityNode.class);

    public HasIdentityNode(ValueNode value) {
        super(TYPE, value);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }

        if (!tool.getValhallaOptionsProvider().valhallaEnabled()) {
            return LogicConstantNode.tautology();
        }
        TriState fold = tryFold(forValue.stamp(NodeView.DEFAULT));
        if (fold.isTrue()) {
            return LogicConstantNode.tautology();
        } else if (fold.isFalse()) {
            return LogicConstantNode.contradiction();
        }
        return this;
    }

    @Override
    public TriState tryFold(Stamp valueStamp) {
        if (!StampTool.canBeInlineType(valueStamp, null)) {
            return TriState.TRUE;
        }
        if (StampTool.isInlineType(valueStamp, null)) {
            return TriState.FALSE;
        }
        return TriState.UNKNOWN;
    }

    @NodeIntrinsic
    public static native boolean hasIdentity(Object node);

    public static boolean intrinsify(GraphBuilderContext b, ValueNode object) {
        if (!b.getValhallaOptionsProvider().valhallaEnabled()) {
            b.addPush(JavaKind.Int, ConstantNode.forInt(1));
        } else {
            HasIdentityNode hasIdentityNode = b.add(new HasIdentityNode(object));
            b.addPush(JavaKind.Int, ConditionalNode.create(hasIdentityNode, NodeView.DEFAULT));
        }
        return true;
    }

    @Override
    public Stamp getSucceedingStampForValue(boolean negated) {
        // we don't save stamp information at the moment
        return getValue().stamp(NodeView.DEFAULT);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(getValue());
        if (alias instanceof VirtualObjectNode virtualObjectNode) {
            tool.replaceWithValue(LogicConstantNode.forBoolean(virtualObjectNode.hasIdentity(), graph()));
        }
    }

}
