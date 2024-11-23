package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_4;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_4;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;

@NodeInfo(cycles = CYCLES_4, size = SIZE_4)
public class IsFlatArrayNode extends LogicNode implements Canonicalizable.Unary<ValueNode>, Lowerable, SingleMemoryKill, Virtualizable {
    public static final NodeClass<IsFlatArrayNode> TYPE = NodeClass.create(IsFlatArrayNode.class);

    @Input protected ValueNode value;
    @Input(InputType.Anchor) private AnchoringNode anchor;

    public IsFlatArrayNode(ValueNode value, AnchoringNode anchor) {
        super(TYPE);
        this.value = value;
        this.anchor = anchor;
    }

    @Override
    public ValueNode getValue() {
        return value;
    }

    public void setValue(ValueNode x) {
        updateUsages(value, x);
        value = x;
    }

    public AnchoringNode getAnchor() {
        return anchor;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forValue) {
        if (!value.stamp(NodeView.DEFAULT).canBeInlineTypeArray())
            return LogicConstantNode.contradiction();
        return this;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.any();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(value);
        if (alias instanceof VirtualObjectNode) {
            ValueNode result = LogicConstantNode.contradiction();
            tool.addNode(result);
            tool.replaceWith(result);
        }
    }
}
