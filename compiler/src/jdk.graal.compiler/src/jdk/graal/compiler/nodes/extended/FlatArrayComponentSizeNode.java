package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_4;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_4;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FloatingAnchoredNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.vm.ci.meta.JavaKind;

@NodeInfo(cycles = CYCLES_4, size = SIZE_4)
public class FlatArrayComponentSizeNode extends FloatingAnchoredNode implements Lowerable, SingleMemoryKill {
    public static final NodeClass<FlatArrayComponentSizeNode> TYPE = NodeClass.create(FlatArrayComponentSizeNode.class);

    @Input protected ValueNode value;

    public FlatArrayComponentSizeNode(ValueNode value, AnchoringNode anchor) {
        super(TYPE, StampFactory.forKind(JavaKind.Int), anchor);
        this.value = value;
    }

    public ValueNode getValue() {
        return value;
    }

    public void setValue(ValueNode x) {
        updateUsages(value, x);
        value = x;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.any();
    }
}
