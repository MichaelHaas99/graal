package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_2;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_1;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.SignExtendNode;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.vm.ci.hotspot.DirectHotSpotObjectConstantImpl;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.PrimitiveConstant;

@NodeInfo(cycles = CYCLES_2, size = SIZE_1)
public class DelayedRawComparison extends FixedWithNextNode implements Canonicalizable, SingleMemoryKill, Lowerable {
    public static final NodeClass<DelayedRawComparison> TYPE = NodeClass.create(DelayedRawComparison.class);

    @Input ValueNode object1;
    @Input ValueNode object2;
    @Input ValueNode offset;
    @Input ValueNode accessKind;
    private final LocationIdentity locationIdentity;

    public DelayedRawComparison(ValueNode object1, ValueNode object2, ValueNode offset, ValueNode accessKind, LocationIdentity locationIdentity) {
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

    public int getIntOffset() {
        if (offset instanceof ConstantNode) {
            return (int) ((PrimitiveConstant) (((ConstantNode) ((ConstantNode) this.offset)).getValue())).asLong();
        } else {
            return ((PrimitiveConstant) ((ConstantNode) ((SignExtendNode) offset).getValue()).getValue()).asInt();
        }

    }

    public ValueNode getAccessKind() {
        return accessKind;
    }

    public boolean isAccessKindConstant() {
        return accessKind.isConstant();
    }

    public JavaKind getConstantKind() {
        assert isAccessKindConstant() : "accessKind must be a constant";
        Constant constant = accessKind.asConstant();
        assert constant instanceof DirectHotSpotObjectConstantImpl : "no correct type for a constant JavaKind";
        DirectHotSpotObjectConstantImpl directHotSpotObjectConstant = (DirectHotSpotObjectConstantImpl) constant;
        Object kind = directHotSpotObjectConstant.getObject();
        assert kind instanceof JavaKind : "Object must be a JavaKind";
        return (JavaKind) kind;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        // return locationIdentity;
        return MemoryKill.NO_LOCATION;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }
// if (accessKind.isConstant()) {
// Constant constant = accessKind.asConstant();
// if (constant instanceof DirectHotSpotObjectConstantImpl directHotSpotObjectConstant) {
// Object kind = directHotSpotObjectConstant.getObject();
// assert kind instanceof JavaKind;
// JavaKind javaKind = (JavaKind) kind;
// RawLoadNode load1 = graph().addOrUnique(new RawLoadNode(object1, offset, javaKind,
// locationIdentity));
// graph().addBeforeFixed(this, load1);
// RawLoadNode load2 = graph().addOrUnique(new RawLoadNode(object1, offset, javaKind,
// locationIdentity));
// graph().addBeforeFixed(this, load2);
// LogicNode result = null;
// if (javaKind == JavaKind.Boolean || javaKind == JavaKind.Byte || javaKind == JavaKind.Char ||
// javaKind == JavaKind.Int || javaKind == JavaKind.Long) {
// result = IntegerEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
// tool.getOptions(), null, load1, load2, NodeView.DEFAULT);
// } else if (javaKind == JavaKind.Object) {
// result = ObjectEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
// tool.getOptions(), load1, load2, NodeView.DEFAULT);
// } else if (javaKind == JavaKind.Float || javaKind == JavaKind.Double) {
// ValueNode normalizeNode = FloatNormalizeCompareNode.create(load1, load2, true, JavaKind.Int,
// tool.getConstantReflection());
// ValueNode constantZero = ConstantNode.forConstant(JavaConstant.INT_0, tool.getMetaAccess(),
// graph());
// result = IntegerEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
// tool.getOptions(), null, normalizeNode, constantZero, NodeView.DEFAULT);
// }
// assert result != null;
// graph().addOrUnique(result);
// ConditionalNode conditional = graph().addOrUnique(new ConditionalNode(result,
// ConstantNode.forBoolean(true, graph()), ConstantNode.forBoolean(false, graph())));
// // replaceAtAllUsages(conditional, true);
// return conditional;
// }
// }
        return this;
    }

    @NodeIntrinsic
    public static native boolean load(Object object1, Object object2, long offset, Object kind, @ConstantNodeParameter LocationIdentity locationIdentity);
}
