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
 * Checks if an array is flat. Either uses a bit in the mark word or the hub.
 */
@NodeInfo(cycles = CYCLES_8, size = SIZE_8)
@NodeIntrinsicFactory
public class IsFlatArrayNode extends UnaryOpLogicNode implements Lowerable, Virtualizable {
    public static final NodeClass<IsFlatArrayNode> TYPE = NodeClass.create(IsFlatArrayNode.class);

    public IsFlatArrayNode(ValueNode value) {
        super(TYPE, value);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (!tool.getValhallaOptionsProvider().useArrayFlattening()) {
            return LogicConstantNode.contradiction();
        }
        return this;
    }

    @Override
    public TriState tryFold(Stamp valueStamp) {
        return TriState.UNKNOWN;
    }

    @NodeIntrinsic
    public static native boolean isFlatArray(Object node);

    public static boolean intrinsify(GraphBuilderContext b, ValueNode object) {
        if (!b.getValhallaOptionsProvider().useArrayFlattening()) {
            b.addPush(JavaKind.Int, ConstantNode.forInt(0));
        } else {
            IsFlatArrayNode isFlatArrayNode = b.add(new IsFlatArrayNode(object));
            b.addPush(JavaKind.Int, ConditionalNode.create(isFlatArrayNode, NodeView.DEFAULT));
        }
        return true;
    }

    @Override
    public Stamp getSucceedingStampForValue(boolean negated) {
        // we don't save stamp information of flat arrays at the moment
        return getValue().stamp(NodeView.DEFAULT);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(value);
        if (alias instanceof VirtualObjectNode) {
            // flat arrays can't be virtual at the moment so this is constant true
            // TODO: adapt if flat arrays can be virtual
            tool.replaceWithValue(LogicConstantNode.contradiction());
        }
    }

}
