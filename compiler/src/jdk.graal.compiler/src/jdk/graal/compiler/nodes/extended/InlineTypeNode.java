package jdk.graal.compiler.nodes.extended;

import java.lang.ref.Reference;
import java.util.Collections;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@link InlineTypeNode} represents a (nullable) scalarized inline object. It takes an optional
 * object {@link #oopOrHub} (in C2 it is called Oop) and the scalarized field values
 * {@link #scalarizedInlineObject} as well as an isNotNull information as input. If the object
 * represents a null value then the input {@link #oopOrHub} will be null at runtime. If the bit 0 of
 * {@link #oopOrHub} is set at runtime, no oop exists and the object needs to be reconstructed by
 * the scalarized field values, if needed. If an oop exists it is up to the compiler to either use
 * the oop or the scalarized field values. The isNotNull information indicates if the inline object
 * is null or not, and can be used e.g. for the debugInfo (in C2 it is called isInit).
 *
 * An {@link Invoke} is responsible for setting the {@link #isNotNull} output correctly based on the
 * {@link #oopOrHub}, because the information doesn't exist as return value.
 *
 * For a null-restricted flat field only the {@link #scalarizedInlineObject} will be set.
 *
 * For a scalarized method parameter, the {@link #scalarizedInlineObject} and the {@link #isNotNull}
 * fields will be directly set by passed parameters. The {@link #oopOrHub} will stay empty.
 *
 * For a nullable flat field, the {@link #scalarizedInlineObject} and the {@link #isNotNull}
 * information can be loaded directly from the flat field.
 *
 */
@NodeInfo(nameTemplate = "InlineTypeNode")
public class InlineTypeNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill, VirtualizableAllocation, Canonicalizable {

    public static final NodeClass<InlineTypeNode> TYPE = NodeClass.create(InlineTypeNode.class);

    @OptionalInput ValueNode oopOrHub;
    @Input NodeInputList<ValueNode> scalarizedInlineObject;
    @OptionalInput ValueNode isNotNull;

    private final ResolvedJavaType type;

    public InlineTypeNode(ResolvedJavaType type, ValueNode oopOrHub, ValueNode[] scalarizedInlineObject, ValueNode isNotNull) {
        super(TYPE, StampFactory.object(TypeReference.createExactTrusted(type), isNotNull == null));
        this.oopOrHub = oopOrHub;
        this.scalarizedInlineObject = new NodeInputList<>(this, scalarizedInlineObject);
        this.type = type;
        this.isNotNull = isNotNull;
    }

    public ValueNode getOopOrHub() {
        return oopOrHub;
    }

    public ValueNode getIsNotNull() {
        return isNotNull;
    }

    public LogicNode createIsNullCheck() {
        assert !isNullFree() : "should only be called if node is not null free";
        return graph().addOrUnique(new IntegerEqualsNode(isNotNull, ConstantNode.forInt(0, graph())));
    }

    public List<ValueNode> getScalarizedInlineObject() {
        return scalarizedInlineObject;
    }

    public ResolvedJavaType getType() {
        return type;
    }


    public ValueNode getField(int index) {
        return scalarizedInlineObject.get(index);
    }

    public boolean isNullFree() {
        return isNotNull == null;
    }

    public boolean hasOopOrHub() {
        return oopOrHub != null;
    }

    public static InlineTypeNode createNullFree(ResolvedJavaType type, ValueNode oopOrHub, ValueNode[] scalarizedInlineObject) {
        return new InlineTypeNode(type, oopOrHub, scalarizedInlineObject, null);
    }

    public static InlineTypeNode createNullFreeWithoutOop(ResolvedJavaType type, ValueNode[] scalarizedInlineObject) {
        return InlineTypeNode.createNullFree(type, null, scalarizedInlineObject);
    }

    public static InlineTypeNode createWithoutOop(ResolvedJavaType type, ValueNode[] scalarizedInlineObject, ValueNode isNotNull) {
        return new InlineTypeNode(type, null, scalarizedInlineObject, isNotNull);
    }

    public static InlineTypeNode createFromInvoke(GraphBuilderContext b, Invoke invoke) {
        ResolvedJavaType returnType = invoke.callTarget().returnStamp().getTrustedStamp().javaType(b.getMetaAccess());

        // can also represent a hub therefore stamp is of type object
        ProjNode oopOrHub = b.add(new ProjNode(StampFactory.object(), invoke.asNode(), 0));

        ResolvedJavaField[] fields = returnType.getInstanceFields(true);
        ProjNode[] projs = new ProjNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            projs[i] = b.add(new ProjNode(fields[i].getType(), b.getAssumptions(), invoke.asNode(), i + 1));

        }

        // additional usage for oopOrHub
        ProjNode isNotNull = b.add(new ProjWithInputNode(StampFactory.forKind(JavaKind.Int), invoke.asNode(), fields.length + 1, oopOrHub));

        InlineTypeNode newInstance = b.append(new InlineTypeNode(returnType, oopOrHub, projs, isNotNull));
// b.append(new ForeignCallNode(LOG_OBJECT, oop, ConstantNode.forBoolean(true,
// b.getGraph()), ConstantNode.forBoolean(true, b.getGraph())));

        return newInstance;
    }

    public static InlineTypeNode createFromFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {
        assert StampTool.isPointerNonNull(object) : "expect an already null-checked object";

        // only support null-restricted flat fields for now

        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();
        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);
        LoadFieldNode[] loads = new LoadFieldNode[innerFields.length];

        int srcOff = field.getOffset();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has no header so remove the header offset
            loads[i] = b.add(LoadFieldNode.create(b.getAssumptions(), object, innerField.changeOffset(srcOff + off)));
        }

        return b.append(new InlineTypeNode(fieldType, null, loads, null));
    }

    public void removeOnInlining() {
        assert oopOrHub instanceof ProjNode : "oopOrHub has to be a ProjNode";
        assert isNotNull instanceof ProjNode : "isNotNull has to be a ProjNode";
        ValueNode invoke = ((ProjNode) oopOrHub).getMultiNode();
        assert invoke instanceof Invoke : "should only be called on inlining of invoke nodes";
        replaceAtUsages(invoke);

        // remove inputs of ProjNodes to MultiNode
        ((ProjNode) oopOrHub).delete();
        ((ProjNode) isNotNull).delete();
        for (ValueNode p : scalarizedInlineObject) {
            assert p instanceof ProjNode : "scalarized value has to be a ProjNode";
            ((ProjNode) p).delete();
        }

        // set control flow correctly and delete
        graph().removeFixed(this);

    }

    /**
     * Needed for replacement with a {@link CommitAllocationNode}
     */
    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.init();
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        // TODO: produces error in TestCallingConvention test49
// if (tool.allUsagesAvailable() && hasNoUsages()) {
// return null;
// }
        return this;
    }

    private boolean scalarize = true;

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (!scalarize)
            return;
        /*
         * Reference objects can escape into their ReferenceQueue at any safepoint, therefore
         * they're excluded from escape analysis.
         */
        if (!tool.getMetaAccess().lookupJavaType(Reference.class).isAssignableFrom(type) &&
                        tool.getMetaAccessExtensionProvider().canVirtualize(type)) {

            if (!isNullFree()) {
                // Because the node can represent a null value, insert a guard before we virtualize
                tool.addNode(new FixedGuardNode(createIsNullCheck(), DeoptimizationReason.TransferToInterpreter, DeoptimizationAction.None, true));
            }

            // virtualize
            VirtualInstanceNode virtualObject = new VirtualInstanceNode(type, false);
            ResolvedJavaField[] fields = virtualObject.getFields();
            ValueNode[] state = new ValueNode[fields.length];
            for (int i = 0; i < state.length; i++) {
                state[i] = getField(i);
            }

            // create virtual object and hand over oopOrHub
            tool.createVirtualObject(virtualObject, state, Collections.emptyList(), getNodeSourcePosition(), false, oopOrHub);
            tool.replaceWithVirtual(virtualObject);
        }
    }

}
