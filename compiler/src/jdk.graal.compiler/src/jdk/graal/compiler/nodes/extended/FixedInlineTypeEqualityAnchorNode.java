package jdk.graal.compiler.nodes.extended;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;

@NodeInfo()
public final class FixedInlineTypeEqualityAnchorNode extends FixedWithNextNode implements Virtualizable, AnchoringNode, LIRLowerable, Canonicalizable {

    public static final NodeClass<FixedInlineTypeEqualityAnchorNode> TYPE = NodeClass.create(FixedInlineTypeEqualityAnchorNode.class);

    @OptionalInput ValueNode object;

    public ValueNode object() {
        return object;
    }

    private FixedInlineTypeEqualityAnchorNode(NodeClass<? extends FixedInlineTypeEqualityAnchorNode> c, ValueNode object) {
        super(c, object == null ? StampFactory.forVoid() : object.stamp(NodeView.DEFAULT));
        this.object = object;
    }

    public FixedInlineTypeEqualityAnchorNode(ValueNode object) {
        this(TYPE, object);
    }

    @Override
    public boolean inferStamp() {
        if (object != null) {
            return updateStamp(stamp.join(object.stamp(NodeView.DEFAULT)));
        } else {
            return false;
        }
    }

    @NodeIntrinsic
    public static native Object getObject(Object object);

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(object());
        // if input is virtual propagate value
        if (alias instanceof VirtualObjectNode) {
            tool.replaceWithVirtual((VirtualObjectNode) alias);
        }
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        generator.setResult(this, generator.operand(object));
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }
        if (!StampTool.canBeInlineType(this, tool.getValhallaOptionsProvider())) {
            return object;
        }
        return this;
    }
}
