package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_2;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_1;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.vm.ci.meta.JavaKind;

/**
 * The {@code ReturnResultDeciderNode} selects, based on its inputs, the node that will be returned
 * by the {@link jdk.graal.compiler.nodes.ReturnScalarizedNode}. If {@link #nonNull} indicates that
 * the result is null, a null pointer will be returned. In case the result is non-null the node
 * either returns the tagged hub created from {@link #hub} or the {@link #oop} if it does not
 * represent a null pointer.
 * 
 */
@NodeInfo(cycles = CYCLES_2, size = SIZE_1)
public class ReturnResultDeciderNode extends FixedWithNextNode implements Lowerable {

    public static final NodeClass<ReturnResultDeciderNode> TYPE = NodeClass.create(ReturnResultDeciderNode.class);
    @Input ValueNode nonNull;
    @Input ValueNode oop;
    @Input ValueNode hub;

    public ReturnResultDeciderNode(JavaKind resultKind, ValueNode nonNull, ValueNode oop, ValueNode hub) {
        super(TYPE, StampFactory.forKind(resultKind));
        this.nonNull = nonNull;
        this.oop = oop;
        this.hub = hub;
    }

    public ValueNode getNonNull() {
        return nonNull;
    }

    public ValueNode getOop() {
        return oop;
    }

    public ValueNode getHub() {
        return hub;
    }
}
