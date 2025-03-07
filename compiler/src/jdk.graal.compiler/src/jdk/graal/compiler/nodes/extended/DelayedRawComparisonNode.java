package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_1;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FieldLocationIdentity;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.meta.JavaKind;

/**
 * Determines if two objects contain the same field value. Waits until certain inputs are converted
 * to {@code JavaConstant}. This is necessary e.g. in
 * {@link jdk.graal.compiler.hotspot.replacements.ObjectEqualsSnippets} where we need to wait until
 * the actual argument (being an array of constants) is bound to the snippet parameter.
 */
@NodeInfo(cycles = CYCLES_1)
public class DelayedRawComparisonNode extends FixedWithNextNode implements Canonicalizable, Lowerable {
    public static final NodeClass<DelayedRawComparisonNode> TYPE = NodeClass.create(DelayedRawComparisonNode.class);

    @Input ValueNode object1;
    @Input ValueNode object2;
    @Input ValueNode offset;
    @Input ValueNode accessKind;
    @Input ValueNode locationIdentity;

    public DelayedRawComparisonNode(ValueNode object1, ValueNode object2, ValueNode offset, ValueNode accessKind, ValueNode locationIdentity) {
        super(TYPE, StampFactory.forKind(JavaKind.Boolean));
        this.object1 = object1;
        this.object2 = object2;
        this.offset = offset;
        this.accessKind = accessKind;
        this.locationIdentity = locationIdentity;
    }

    public ValueNode getObject1() {
        return object1;
    }

    public ValueNode getObject2() {
        return object2;
    }

    public ValueNode getOffset() {
        return offset;
    }

    public long getLongOffset() {
        assert offset.isJavaConstant() : "offset is expected to be a constant";
        return offset.asJavaConstant().asLong();

    }

    public boolean isAccessKindConstant() {
        return accessKind.isJavaConstant();
    }

    public JavaKind getConstantKind() {
        assert isAccessKindConstant() : "accessKind must be a constant";
        return ((HotSpotObjectConstant) accessKind.asJavaConstant()).asObject(JavaKind.class);
    }

    public LocationIdentity getLocationIdentity() {
        assert locationIdentity.isJavaConstant() : "locationIdentity must be a constant";
        return ((HotSpotObjectConstant) locationIdentity.asJavaConstant()).asObject(FieldLocationIdentity.class);
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }
        return this;
    }

    @NodeIntrinsic
    public static native boolean load(Object object1, Object object2, long offset, Object kind, Object locationIdentity);
}
