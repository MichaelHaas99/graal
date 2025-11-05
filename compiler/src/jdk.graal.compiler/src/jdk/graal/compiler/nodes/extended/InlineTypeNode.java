package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.graph.spi.NodeWithIdentity;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.WithExceptionNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.java.MultiValue;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Simplifiable;
import jdk.graal.compiler.nodes.spi.SimplifierTool;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@link InlineTypeNode} represents a (nullable) scalarized inline object. It takes an optional
 * object {@link #oop} (in C2 it is called Oop) and the field values {@link #entries} as well as an
 * non-null information as input. If the object represents a null value then the input {@link #oop}
 * will be null at runtime. If the bit 0 of {@link #oop} is set at runtime, no oop exists and the
 * object needs to be reconstructed by the scalarized field values, if needed. If an oop exists it
 * is up to the compiler to either use the oop or the scalarized field values. The non-null
 * information indicates if the inline object is null or not, and can be used e.g. for null checks
 * or for the debugInfo (in C2 it is called isInit).
 *
 * An {@link Invoke} is responsible for setting the {@link #nonNull} output correctly based on the
 * {@link #oop}, because the information doesn't exist as return value. It also sets the tagged hub
 * to a null pointer.
 *
 * For a null-restricted flat field only the {@link #entries} will be set.
 *
 * For a scalarized method parameter, the {@link #entries} and the {@link #nonNull} fields will be
 * directly set by passed parameters. The {@link #oop} will stay empty.
 *
 * For a nullable flat field, the {@link #entries} and the {@link #nonNull} information can be
 * loaded directly from the flat field.
 *
 */
@NodeInfo(nameTemplate = "InlineType", cycles = CYCLES_8, cyclesRationale = "tlab alloc + header init", size = SIZE_8)
public class InlineTypeNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill, VirtualizableAllocation, Simplifiable, VirtualNode {

    public static final NodeClass<InlineTypeNode> TYPE = NodeClass.create(InlineTypeNode.class);

    @OptionalInput ValueNode oop;
    @OptionalInput NodeInputList<ValueNode> entries;
    @OptionalInput ValueNode nonNull;
    private final boolean isAllocatedOrNull;
    private final ResolvedJavaType type;
    private final ResolvedJavaField[] fields;
    private final boolean handlesScalarizedReturn;

    @SuppressWarnings("this-escape")
    private InlineTypeNode(ResolvedJavaType type, ValueNode oop, ValueNode[] entries, ValueNode nonNull, boolean isAllocatedOrNull, boolean handlesScalarizedReturn) {
        super(TYPE, StampFactory.object(TypeReference.createExactTrusted(type), nonNull == null));
        this.oop = oop;
        this.nonNull = nonNull;
        this.isAllocatedOrNull = isAllocatedOrNull;
        GraalError.guarantee(type.getInstanceFields(true).length == entries.length, "field size does not match value size");
        this.entries = new NodeInputList<>(this, entries);
        this.type = type;
        this.fields = type.getInstanceFields(true);
        inferStamp();
        this.handlesScalarizedReturn = handlesScalarizedReturn;
    }

    public InlineTypeNode(ResolvedJavaType type, ValueNode oop, ValueNode[] entries, ValueNode nonNull, boolean isAllocatedOrNull) {
        this(type, oop, entries, nonNull, isAllocatedOrNull, false);
    }

    public ValueNode getOop() {
        return oop;
    }

    public ValueNode getNonNull() {
        return nonNull;
    }

    public boolean isAllocatedOrNull() {
        return isAllocatedOrNull;
    }

    public LogicNode createNullCheck(boolean insertIntoGraph) {
        assert !StampTool.isPointerNonNull(this) : "should only be called if node is not non-null";
        LogicNode check = new IntegerEqualsNode(nonNull, ConstantNode.forInt(0, graph()));
        return insertIntoGraph ? graph().addOrUnique(check) : check;
    }

    public List<ValueNode> getEntries() {
        return entries;
    }

    public ResolvedJavaType getType() {
        return type;
    }

    @Override
    public ResolvedJavaField[] getFields() {
        return fields;
    }

    public ValueNode getEntry(int index) {
        return entries.get(index);
    }

    public ValueNode getEntry(ResolvedJavaField field) {
        return getEntry(fieldIndex(field));
    }

    public static InlineTypeNode createWithoutValues(ResolvedJavaType type, ValueNode oop, ValueNode nonNull) {
        return new InlineTypeNode(type, oop, new ValueNode[type.getInstanceFields(true).length], nonNull, false);
    }

    public static InlineTypeNode createNonNull(ResolvedJavaType type, ValueNode oop, ValueNode[] fieldValues) {
        return new InlineTypeNode(type, oop, fieldValues, ConstantNode.forInt(1), false);
    }

    public static InlineTypeNode createNonNullWithoutOop(ResolvedJavaType type, ValueNode[] fieldValues) {
        return InlineTypeNode.createNonNull(type, ConstantNode.defaultForKind(JavaKind.Object), fieldValues);
    }

    public static InlineTypeNode createWithoutOop(ResolvedJavaType type, ValueNode[] fieldValues, ValueNode nonNull) {
        return new InlineTypeNode(type, ConstantNode.defaultForKind(JavaKind.Object), fieldValues, nonNull, false);
    }

    public static InlineTypeNode createFromInvoke(Invoke invoke, MetaAccessProvider metaAccess) {
        StructuredGraph graph = invoke.asNode().graph();
        ResolvedJavaType returnType = invoke.callTarget().returnStamp().getTrustedStamp().javaType(metaAccess);

        // can also represent an oop or a null pointer
        ReadMultiValueNode oop = graph.addOrUnique(ReadMultiValueNode.createOop(returnType, graph.getAssumptions(), invoke, 0));

        ResolvedJavaField[] fields = returnType.getInstanceFields(true);
        ReadMultiValueNode[] fieldValues = new ReadMultiValueNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            fieldValues[i] = graph.addOrUnique(ReadMultiValueNode.createFieldValue(fields[i].getType(), graph.getAssumptions(), invoke, i + 1));

        }

        ReadMultiValueNode nonNull = graph.addOrUnique(ReadMultiValueNode.createNonNull(
                        invoke, fields.length + 1));

        InlineTypeNode inlineTypeNode = graph.addOrUnique(new InlineTypeNode(returnType, oop, fieldValues, nonNull, false, true));
        FixedNode addBefore;
        if (invoke instanceof WithExceptionNode withExceptionNode) {
            addBefore = withExceptionNode.next().next();
        } else {
            addBefore = ((FixedWithNextNode) invoke.asFixedNode()).next();
        }
        graph.addBeforeFixed(addBefore, inlineTypeNode);
// b.append(new ForeignCallNode(LOG_OBJECT, oop, ConstantNode.forBoolean(true,
// b.getGraph()), ConstantNode.forBoolean(true, b.getGraph())));

        return inlineTypeNode;
    }

    public void removeOnInlining() {
        MultiValue invoke = ((ReadMultiValueNode) oop).getMultiValueNode();
        assert invoke instanceof Invoke : "should only be called on inlining of invoke nodes";
        replaceAtUsages(invoke.asNode());

        // remove inputs of ReadMultiValueNode to MultiValueNode
        ((ReadMultiValueNode) oop).delete();
        ((ReadMultiValueNode) nonNull).delete();
        for (ValueNode p : entries) {
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

        if (usages().count() == 0) {
            List<Node> inputSnapshot = inputs().snapshot();
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
        return nonNull.isJavaConstant() && nonNull.asJavaConstant().asInt() == 1;
    }

    private boolean isNull() {
        return nonNull.isJavaConstant() && nonNull.asJavaConstant().asInt() == 0;
    }

    public ValueNode[] getScalarizedRepresentation(boolean isNonNull, boolean includeNonNullIfNonNull) {
        ValueNode[] result;

        if (isNonNull && !includeNonNullIfNonNull) {
            result = entries.toArray(ValueNode.EMPTY_ARRAY);
        } else {
            List<ValueNode> list = new ArrayList<>(entries);
            if (isNonNull) {
                list.addFirst(ConstantNode.forInt(1, this.graph()));
            } else {
                list.addFirst(nonNull);
            }
            result = list.toArray(ValueNode.EMPTY_ARRAY);
        }
        return result;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {

        ValueNode oopAlias = tool.getAlias(oop);
        if (oopAlias instanceof VirtualObjectNode virtualOop) {
            tool.replaceWithVirtual(virtualOop);
            return;
        }

        if (tool.getMetaAccessExtensionProvider().canVirtualize(type)) {

            VirtualInstanceNode virtualObject = new VirtualInstanceNode(type, false, StampTool.isPointerNonNull(this));
            ResolvedJavaField[] fields = virtualObject.getFields();
            ValueNode[] state = new ValueNode[fields.length];
            for (int i = 0; i < state.length; i++) {
                state[i] = getEntry(i);
            }

            // create virtual object and hand over oop and non-null info
            tool.createVirtualObject(virtualObject, state, Collections.emptyList(), getNodeSourcePosition(), false, this.oop, this.nonNull, isAllocatedOrNull);
            tool.replaceWithVirtual(virtualObject);
        }
    }

    @NodeInfo(cycles = CYCLES_0, size = SIZE_0)
    public static class Placeholder extends FixedWithNextNode implements NodeWithIdentity {
        public static final NodeClass<Placeholder> TYPE = NodeClass.create(Placeholder.class);
        @Input ValueNode object;
        private final ResolvedJavaType type;
        private final boolean nonNull;

        public ValueNode object() {
            return object;
        }

        public MethodCallTargetNode callTarget() {
            for (Node usage : usages()) {
                if (usage instanceof MethodCallTargetNode methodCallTargetNode && methodCallTargetNode.getScalarizedArguments().contains(this)) {
                    return methodCallTargetNode;
                }
            }
            throw GraalError.shouldNotReachHere("no call target found");
        }

        protected Placeholder(NodeClass<? extends Placeholder> c, ValueNode object, ResolvedJavaType type, boolean nonNull) {
            super(c, StampFactory.forDeclaredType(null, type, nonNull).getTrustedStamp());
            this.object = object;
            this.type = type;
            this.nonNull = nonNull;
        }

        public Placeholder(ValueNode object, ResolvedJavaType type, boolean nonNull) {
            this(TYPE, object, type, nonNull);

        }

        public ValueNode[] makeReplacement() {
            ValueNode[] scalarizedValues = InlineTypeUtil.createScalarizationCFG(this, object, List.of(type.getInstanceFields(true)), nonNull, !nonNull);

            if (GraphUtil.unproxify(object) instanceof InlineTypeNode) {
                // no need to create a new InlineTypeNode
                this.replaceAtUsages(object);
                graph().removeFixed(this);
            } else {
                if (!hasNoUsages()) {
                    InlineTypeNode inlineTypeNode;
                    if (nonNull) {
                        inlineTypeNode = new InlineTypeNode(type, object, scalarizedValues, ConstantNode.forInt(1, graph()), true);
                    } else {
                        inlineTypeNode = new InlineTypeNode(type, object, Arrays.copyOfRange(scalarizedValues, 1, scalarizedValues.length), scalarizedValues[0], true);
                    }
                    graph().addOrUniqueWithInputs(inlineTypeNode);
                    graph().replaceFixedWithFixed(this, inlineTypeNode);
                } else {
                    graph().removeFixed(this);
                }
            }
            return scalarizedValues;

        }

        public void undo() {
            this.replaceAtUsages(object);
            graph().removeFixed(this);
        }
    }

}
