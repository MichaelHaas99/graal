package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;

/**
 * Checks if an array is flat. Either uses a bit in the mark word or the hub.
 */
@NodeInfo(cycles = CYCLES_8, size = SIZE_8)
public class IsFlatArrayNode extends FixedWithNextNode implements Lowerable, Canonicalizable, Virtualizable {
    public static final NodeClass<IsFlatArrayNode> TYPE = NodeClass.create(IsFlatArrayNode.class);

    @Input protected ValueNode value;

    public IsFlatArrayNode(ValueNode value) {
        super(TYPE, StampFactory.forKind(JavaKind.Boolean));
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
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }
        return this;
    }

    @NodeIntrinsic
    public static native boolean isFlatArray(Object node);

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(value);
        if (alias instanceof VirtualObjectNode) {
            // flat arrays can't be virtual at the moment so this is constant true
            // TODO: adapt if flat arrays can be virtual
            tool.replaceWithValue(ConstantNode.forBoolean(false, graph()));
        }
    }
}
