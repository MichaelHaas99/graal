package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_4;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_4;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;

@NodeInfo(cycles = CYCLES_4, size = SIZE_4)
public class IsNullFreeArrayNode extends LogicNode implements Lowerable, SingleMemoryKill {
    public static final NodeClass<IsNullFreeArrayNode> TYPE = NodeClass.create(IsNullFreeArrayNode.class);

    @Input protected ValueNode value;
    @Input(InputType.Anchor) private AnchoringNode anchor;

    public IsNullFreeArrayNode(ValueNode value, AnchoringNode anchor) {
        super(TYPE);
        this.value = value;
        this.anchor = anchor;
    }

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
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.any();
    }

}
