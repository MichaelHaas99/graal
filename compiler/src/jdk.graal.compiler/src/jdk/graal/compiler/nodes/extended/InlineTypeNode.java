package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import java.util.Collections;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.Stamp;
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
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Simplifiable;
import jdk.graal.compiler.nodes.spi.SimplifierTool;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@link InlineTypeNode} represents a (nullable) scalarized inline object. It takes an optional
 * object {@link #oop} (in C2 it is called Oop) and the field values {@link #fieldValues} as well as
 * an non-null information as input. If the object represents a null value then the input
 * {@link #oop} will be null at runtime. If the bit 0 of {@link #oop} is set at runtime, no oop
 * exists and the object needs to be reconstructed by the scalarized field values, if needed. If an
 * oop exists it is up to the compiler to either use the oop or the scalarized field values. The
 * non-null information indicates if the inline object is null or not, and can be used e.g. for null
 * checks or for the debugInfo (in C2 it is called isInit).
 *
 * An {@link Invoke} is responsible for setting the {@link #nonNull} output correctly based on the
 * {@link #oop}, because the information doesn't exist as return value. It also sets the tagged hub
 * to a null pointer.
 *
 * For a null-restricted flat field only the {@link #fieldValues} will be set.
 *
 * For a scalarized method parameter, the {@link #fieldValues} and the {@link #nonNull} fields will
 * be directly set by passed parameters. The {@link #oop} will stay empty.
 *
 * For a nullable flat field, the {@link #fieldValues} and the {@link #nonNull} information can be
 * loaded directly from the flat field.
 *
 */
@NodeInfo(nameTemplate = "InlineTypeNode", cycles = CYCLES_8, cyclesRationale = "tlab alloc + header init", size = SIZE_8)
public class InlineTypeNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill, VirtualizableAllocation, Simplifiable {

    public static final NodeClass<InlineTypeNode> TYPE = NodeClass.create(InlineTypeNode.class);

    @OptionalInput ValueNode oop;
    @OptionalInput NodeInputList<ValueNode> fieldValues;
    @OptionalInput ValueNode nonNull;

    private final ResolvedJavaType type;

    public InlineTypeNode(ResolvedJavaType type, ValueNode oop, ValueNode[] fieldValues, ValueNode nonNull) {
        super(TYPE, StampFactory.object(TypeReference.createExactTrusted(type), nonNull == null));
        this.oop = oop;
        this.fieldValues = new NodeInputList<>(this, fieldValues);
        this.type = type;
        this.nonNull = nonNull;
        assert nonNull == null && oop == null || nonNull != null && oop != null : "both should be either null or not null";
        inferStamp();
    }

    public void setFieldValue(ResolvedJavaField field, ValueNode value) {
        ResolvedJavaField[] fields = type.getInstanceFields(true);
        int index = -1;
        // on average fields.length == ~6, so a linear search is fast enough
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].equals(field)) {
                index = i;
            }
        }

        if (index != -1) {
            fieldValues.set(index, value);
        }

    }

    public ValueNode getOop() {
        return oop;
    }

    public ValueNode getNonNull() {
        return nonNull;
    }

    public LogicNode createNullCheck() {
        assert !StampTool.isPointerNonNull(this) : "should only be called if node is not non-null";
        return graph().addOrUnique(new IntegerEqualsNode(nonNull, ConstantNode.forInt(0, graph())));
    }

    public List<ValueNode> getFieldValues() {
        return fieldValues;
    }

    public ResolvedJavaType getType() {
        return type;
    }


    public ValueNode getField(int index) {
        return fieldValues.get(index);
    }



    public static InlineTypeNode createWithoutValues(ResolvedJavaType type, ValueNode oop, ValueNode nonNull) {
        return new InlineTypeNode(type, oop, new ValueNode[type.getInstanceFields(true).length], nonNull);
    }

    public static InlineTypeNode createNonNull(ResolvedJavaType type, ValueNode oop, ValueNode[] fieldValues) {
        return new InlineTypeNode(type, oop, fieldValues, null);
    }

    public static InlineTypeNode createNonNullWithoutOop(ResolvedJavaType type, ValueNode[] fieldValues) {
        return InlineTypeNode.createNonNull(type, null, fieldValues);
    }

    public static InlineTypeNode createWithoutOop(ResolvedJavaType type, ValueNode[] fieldValues, ValueNode nonNull) {
        return new InlineTypeNode(type, ConstantNode.forConstant(JavaConstant.NULL_POINTER, null), fieldValues, nonNull);
    }

    public static InlineTypeNode createFromInvoke(GraphBuilderContext b, Invoke invoke) {
        ResolvedJavaType returnType = invoke.callTarget().returnStamp().getTrustedStamp().javaType(b.getMetaAccess());

        // can also represent an oop or a null pointer
        ReadMultiValueNode oop = b.add(new ReadMultiValueNode(returnType, b.getAssumptions(), invoke.asNode(), 0));

        ResolvedJavaField[] fields = returnType.getInstanceFields(true);
        ReadMultiValueNode[] fieldValues = new ReadMultiValueNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            fieldValues[i] = b.add(new ReadMultiValueNode(fields[i].getType(), b.getAssumptions(), invoke.asNode(), i + 1));

        }

        ReadMultiValueNode nonNull = b.add(new ReadMultiValueNode(StampFactory.forKind(JavaKind.Int),
                        invoke.asNode(), fields.length + 1));

        InlineTypeNode newInstance = b.append(new InlineTypeNode(returnType, oop, fieldValues, nonNull));
// b.append(new ForeignCallNode(LOG_OBJECT, oop, ConstantNode.forBoolean(true,
// b.getGraph()), ConstantNode.forBoolean(true, b.getGraph())));

        return newInstance;
    }


    public void removeOnInlining() {
        assert oop instanceof ReadMultiValueNode : "oop has to be a ReadMultiValueNode";
        assert nonNull instanceof ReadMultiValueNode : "nonNull has to be a ReadMultiValueNode";
        ValueNode invoke = ((ReadMultiValueNode) oop).getMultiValueNode();
        assert invoke instanceof Invoke : "should only be called on inlining of invoke nodes";
        replaceAtUsages(invoke);

        // remove inputs of ReadMultiValueNode to MultiValueNode
        ((ReadMultiValueNode) oop).delete();
        ((ReadMultiValueNode) nonNull).delete();
        for (ValueNode p : fieldValues) {
            assert p instanceof ReadMultiValueNode : "scalarized value has to be a ProjNode";
            ((ReadMultiValueNode) p).delete();
        }

        // set control flow correctly and delete
        graph().removeFixed(this);

    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.init();
    }


    // comment to see inline type node getting materialized to null for test6_verifier
    @Override
    public void simplify(SimplifierTool tool) {
        if (StampTool.isPointerAlwaysNull(this)) {
            List<Node> inputSnapshot = inputs().snapshot();
            List<Node> usages = this.usages().snapshot();

            ValueNode nullPointer = graph().addOrUnique(ConstantNode.forConstant(JavaConstant.NULL_POINTER, null));
            tool.addToWorkList(usages);
            this.replaceAtUsages(nullPointer);
            graph().removeFixed(this);
            for (Node input : inputSnapshot) {
                tool.removeIfUnused(input);
            }
        }
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(computeStamp());
    }

    private Stamp computeStamp() {
        if (isNonNull()) {
            return StampFactory.objectNonNull().improveWith(stamp);
        }
        if (isNull()) {
            return StampFactory.alwaysNull();
        }
        return stamp;
    }

    private boolean isNonNull() {
        return nonNull == null || nonNull.isJavaConstant() && nonNull.asJavaConstant().asInt() == 1;
    }

    private boolean isNull() {
        return nonNull != null && nonNull.isJavaConstant() && nonNull.asJavaConstant().asInt() == 0;
    }

    private boolean virtualize = true;
    private boolean insertGuardBeforeVirtualize = false;

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (!virtualize)
            return;

        if (tool.getMetaAccessExtensionProvider().canVirtualize(type)) {

            ValueNode oop = this.oop;
            ValueNode notNull = this.nonNull;
            if (insertGuardBeforeVirtualize) {
                if (!StampTool.isPointerNonNull(this)) {
                    // Because the node can represent a null value, insert a guard before we
                    // virtualize
                    tool.addNode(new FixedGuardNode(createNullCheck(), DeoptimizationReason.TransferToInterpreter, DeoptimizationAction.None, true));
                    if (oop == null) {
                        notNull = null;
                    } else {
                        notNull = ConstantNode.forInt(1, graph());
                        tool.ensureAdded(notNull);
                    }
                }

            }

            // virtualize
            VirtualInstanceNode virtualObject = new VirtualInstanceNode(type, false, StampTool.isPointerNonNull(this) || insertGuardBeforeVirtualize);
            ResolvedJavaField[] fields = virtualObject.getFields();
            ValueNode[] state = new ValueNode[fields.length];
            for (int i = 0; i < state.length; i++) {
                state[i] = getField(i);
            }

            // make sure both values are either null or set
            // after an invoke we already have both
            // a parameter only includes the non-null information so use the null pointer constant
            if (oop == null && notNull != null) {
                oop = ConstantNode.forConstant(JavaConstant.NULL_POINTER, tool.getMetaAccess(), graph());
            }
            if (oop != null && notNull == null) {
                notNull = ConstantNode.forInt(1, graph());
            }

            // create virtual object and hand over oop and non-null info
            tool.createVirtualObject(virtualObject, state, Collections.emptyList(), getNodeSourcePosition(), false, oop, notNull);
            tool.replaceWithVirtual(virtualObject);
        }
    }

}
