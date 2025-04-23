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
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.TriState;

/**
 * Checks if an array is null-restricted. Either uses a bit in the mark word or the layout helper.
 */
@NodeInfo(cycles = CYCLES_8, size = SIZE_8)
@NodeIntrinsicFactory
public class IsNullFreeArrayNode extends UnaryOpLogicNode implements Lowerable, Virtualizable {
    public static final NodeClass<IsNullFreeArrayNode> TYPE = NodeClass.create(IsNullFreeArrayNode.class);

    public IsNullFreeArrayNode(ValueNode value) {
        super(TYPE, value);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (!tool.getValhallaOptionsProvider().valhallaEnabled()) {
            return LogicConstantNode.contradiction();
        }
        return this;
    }

    @Override
    public TriState tryFold(Stamp valueStamp) {
        return TriState.UNKNOWN;
    }

    @NodeIntrinsic
    public static native boolean isNullFreeArray(Object node);

    public static boolean intrinsify(GraphBuilderContext b, ValueNode object) {
        if (!b.getValhallaOptionsProvider().valhallaEnabled()) {
            b.addPush(JavaKind.Int, ConstantNode.forInt(0));
        } else {
            IsNullFreeArrayNode isNullFreeArrayNode = b.add(new IsNullFreeArrayNode(object));
            b.addPush(JavaKind.Int, ConditionalNode.create(isNullFreeArrayNode, NodeView.DEFAULT));
        }
        return true;
    }

    @Override
    public Stamp getSucceedingStampForValue(boolean negated) {
        // we don't save stamp information of null-free arrays at the moment
        return getValue().stamp(NodeView.DEFAULT);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(value);
        if (alias instanceof VirtualObjectNode) {
            // null-free arrays can't be virtual at the moment so this is constant true
            // TODO: adapt if null-free arrays can be virtual
            tool.replaceWithValue(LogicConstantNode.contradiction());
        }
    }
}
