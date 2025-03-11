package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.vm.ci.meta.JavaKind;

@NodeInfo(cycles = CYCLES_8, size = SIZE_8)
public class HasIdentityNode extends FixedWithNextNode implements Lowerable, Canonicalizable {
    public static final NodeClass<HasIdentityNode> TYPE = NodeClass.create(HasIdentityNode.class);

    @Input protected ValueNode value;

    public HasIdentityNode(ValueNode value) {
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

        if (!StampTool.canBeInlineType(value, tool.getValhallaOptionsProvider())) {
            return LogicConstantNode.tautology();
        }
        if (StampTool.isInlineType(value, tool.getValhallaOptionsProvider())) {
            return LogicConstantNode.contradiction();
        }
        return this;
    }

    @NodeIntrinsic
    public static native boolean hasIdentity(Object node);

}
