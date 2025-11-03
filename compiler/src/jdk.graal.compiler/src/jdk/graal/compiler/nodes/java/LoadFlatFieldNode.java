package jdk.graal.compiler.nodes.java;

import java.util.Collections;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ReadMultiValueNode;
import jdk.graal.compiler.nodes.memory.MemoryAccess;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.memory.OrderedMemoryAccess;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@code LoadFlatFieldNode} performs an atomic load operation for a flat value object. This
 * node returns a value for each instance field of the value object, so multiple values. Each value
 * is represented with a {@link ReadMultiValueNode}. The structure looks as follows:
 * 
 * <pre>
 *            object
 *              ^
 *              |
 *       LoadFlatFieldNode
 *              ^
 *              |
 *    ------------------------- 
 *    |     |      |    ..    |
 *   oop  field  field  ..  nonNull
 *    ^     ^      ^          ^
 *    |     |      |    ..    |
 *    -------------------------
 *              |
 *        InlineTypeNode
 * </pre>
 * 
 * There exists no oop in a flat field so the oop node should be replaced with a null pointer during
 * lowering. We need the oop node during PEA, when we replace the LoadFlatFieldNode with a virtual
 * object. We use the oop node to propagate the virtual object to the InlinetypeNode, which will
 * replace itself with this virtual object as well.
 */
// TOOD: WIP, preparation for nullable heap flattening
@NodeInfo(nameTemplate = "LoadFlatFieldNode#{p#declaredField/s}")
public class LoadFlatFieldNode extends FixedWithNextNode implements Virtualizable, Canonicalizable, Lowerable, OrderedMemoryAccess, MemoryAccess, SingleMemoryKill {
    public static final NodeClass<LoadFlatFieldNode> TYPE = NodeClass.create(LoadFlatFieldNode.class);
    @OptionalInput ValueNode object;
    private final LocationIdentity location;
    private final ResolvedJavaField declaredField;
    private final MemoryOrderMode memoryOrder;
    private final ResolvedJavaField[] instanceFields;
    private final ResolvedJavaField nullMarker;

    public ValueNode object() {
        return object;
    }

    private LoadFlatFieldNode(Stamp stamp, ValueNode object, ResolvedJavaField declaredField, MemoryOrderMode memoryOrder, boolean immutable, ResolvedJavaField[] instanceFields,
                    ResolvedJavaField nullMarker) {
        super(TYPE, stamp);
        assert !immutable || declaredField.isFinal() : "immutable fields must also be final";
        assert !immutable || !declaredField.isStatic() : "immutable fields must also be non-static";
        this.object = object;
        this.declaredField = declaredField;
        this.memoryOrder = memoryOrder;
        this.location = LocationIdentity.any();
        this.instanceFields = instanceFields;
        this.nullMarker = nullMarker;
    }

    public LoadFlatFieldNode(Stamp stamp, ValueNode object, ResolvedJavaField declaredField, ResolvedJavaField[] instanceFields, ResolvedJavaField nullMarker) {
        this(stamp, object, declaredField, MemoryOrderMode.getMemoryOrder(declaredField), false, instanceFields, nullMarker);
    }

    @Override
    public boolean verifyNode() {
        assertTrue(this.usages().snapshot().stream().allMatch(usage -> usage instanceof ReadMultiValueNode), "Illegal usage of %s");
        return super.verifyNode();
    }

    @Override
    public MemoryOrderMode getMemoryOrder() {
        return memoryOrder;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return location;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        if (ordersMemoryAccesses()) {
            return LocationIdentity.any();
        }
        return MemoryKill.NO_LOCATION;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        // TODO
        return this;
    }

    private void getFields(VirtualizerTool tool, VirtualInstanceNode alias, ValueNode[] entries) {
        for (int i = 0; i < entries.length; ++i) {
            int fieldIndex = alias.fieldIndex(instanceFields[i]);
            GraalError.guarantee(fieldIndex != -1, "field value was not found");
            entries[i] = tool.getEntry(alias, fieldIndex);
        }
    }

    private ValueNode getNonNull(VirtualizerTool tool, VirtualInstanceNode alias) {
        if (nullMarker == null) {
            return ConstantNode.forInt(1, this.graph());
        }
        int fieldIndex = alias.fieldIndex(nullMarker);
        return tool.getEntry(alias, fieldIndex);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(object);
        if (alias instanceof VirtualInstanceNode virtualInstanceNode) {
            if (!tool.isNonNull(virtualInstanceNode)) {
                tool.nullCheckAndCast(virtualInstanceNode);
            }
            ValueNode[] state = new ValueNode[instanceFields.length];
            getFields(tool, virtualInstanceNode, state);
            VirtualInstanceNode virtualObject = new VirtualInstanceNode((ResolvedJavaType) declaredField.getType(), false, StampTool.isPointerNonNull(asNode()));

            ValueNode oop = ConstantNode.defaultForKind(JavaKind.Object, this.graph());
            ValueNode nonNull = getNonNull(tool, virtualInstanceNode);
            tool.createVirtualObject(virtualObject, state, Collections.emptyList(), asNode().getNodeSourcePosition(), false, oop, nonNull, false);
            tool.replaceWithVirtual(virtualObject);
        }
    }
}
