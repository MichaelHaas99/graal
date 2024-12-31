package jdk.graal.compiler.nodes.extended;

import java.lang.ref.Reference;
import java.util.Collections;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@code InlineTypeNode} represents possible the allocation of an inline object.
 */
@NodeInfo(nameTemplate = "New InlineType")
public class InlineTypeNode extends FixedWithNextNode implements Lowerable, StateSplit, SingleMemoryKill, VirtualizableAllocation, Canonicalizable, Node.IndirectInputChangedCanonicalization {

    public static final NodeClass<InlineTypeNode> TYPE = NodeClass.create(InlineTypeNode.class);

    @Input ValueNode oop;
    @Input NodeInputList<ValueNode> scalarizedInlineType;
    // @Input(InputType.Guard) GuardingNode nullCheck;

    @OptionalInput(InputType.State) FrameState stateAfter;

    @Override
    public FrameState stateAfter() {
        return stateAfter;
    }

    @Override
    public void setStateAfter(FrameState x) {
        assert x == null || x.isAlive() : "frame state must be in a graph";
        updateUsages(stateAfter, x);
        stateAfter = x;
    }

    private boolean hasSideEffect = true;

    @Override
    public boolean hasSideEffect() {
        return hasSideEffect;
    }

    public void noSideEffect() {
        hasSideEffect = false;
    }

    private final ResolvedJavaType type;

    public InlineTypeNode(ResolvedJavaType type, boolean fillContents, ValueNode oop, ValueNode[] scalarizedInlineType) {
        super(TYPE, StampFactory.object(TypeReference.createExactTrusted(type)));
        this.oop = oop;
        this.scalarizedInlineType = new NodeInputList<>(this, scalarizedInlineType);
        this.type = type;
    }

    public ValueNode getOop() {
        return oop;
    }

    public List<ValueNode> getScalarizedInlineType() {
        return scalarizedInlineType.subList(0, scalarizedInlineType.size() - 1);
    }

    public ResolvedJavaType getType() {
        return type;
    }

    public ValueNode getField(int index) {
        return scalarizedInlineType.get(index);
    }

    public static InlineTypeNode createFromInvoke(GraphBuilderContext b, InvokeNode invoke) {
        ResolvedJavaType returnType = invoke.callTarget().returnStamp().getTrustedStamp().javaType(b.getMetaAccess());
        ProjNode oop = b.add(new ProjNode(StampFactory.object(), invoke));

        ResolvedJavaField[] fields = returnType.getInstanceFields(true);
        ProjNode[] projs = new ProjNode[fields.length + 1];

        for (int i = 0; i < fields.length; i++) {
            projs[i] = b.add(new ProjNode(fields[i].getType(), b.getAssumptions(), invoke));

        }
        projs[projs.length - 1] = b.add(new ProjNode(StampFactory.forKind(JavaKind.Boolean), invoke));
        // LogicNode isInit = b.add(new IntegerEqualsNode(projs[projs.length - 1],
        // ConstantNode.forByte((byte) 1, b.getGraph())));
        // LogicNode isInit = b.add(LogicNegationNode.create(b.add(new IsNullNode(oop))));
        // GuardingNode guard = b.add(new FixedGuardNode(isInit,
        // DeoptimizationReason.TransferToInterpreter, DeoptimizationAction.None));

        FixedProjAnchorNode anchor = b.add(new FixedProjAnchorNode());
        anchor.objects().addAll(List.of(projs));
        InlineTypeNode newInstance = b.append(new InlineTypeNode(returnType, false, oop, projs));
        // b.append(new ForeignCallNode(LOG_OBJECT, oop, ConstantNode.forBoolean(true,
        // b.getGraph()), ConstantNode.forBoolean(true, b.getGraph())));
        // b.append(new FixedGuardNode(isInit, DeoptimizationReason.TransferToInterpreter,
        // DeoptimizationAction.None));
        return newInstance;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.init();
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (!oop.getNodeClass().equals(ProjNode.TYPE)) {
            return oop;
        }
        return this;
    }

    @NodeInfo(nameTemplate = "ProjNode")
    public static class ProjNode extends ValueNode implements LIRLowerable, Canonicalizable {
        public static final NodeClass<ProjNode> TYPE = NodeClass.create(ProjNode.class);

        @Input ValueNode src;

        protected ProjNode(NodeClass<? extends ProjNode> c, Stamp stamp) {
            super(c, stamp);
        }

        public ProjNode(Stamp stamp, InvokeNode src) {
            this(TYPE, stamp);
            this.src = src;
        }

        public ProjNode(JavaType type, Assumptions assumptions, InvokeNode src) {
            this(StampFactory.forDeclaredType(assumptions, type, false).getTrustedStamp(), src);
        }

        @Override
        public void generate(NodeLIRBuilderTool generator) {
            ((InvokeNode) src).generate(generator);
        }

        @Override
        public Node canonical(CanonicalizerTool tool) {
            if (!src.getNodeClass().equals(InvokeNode.TYPE)) {
                return src;
            }
            return this;
        }
    }

// public void lowerSuper(LoweringTool tool) {
// super.lower(tool);
// }

/*
 * @Override public void lower(LoweringTool tool) { StructuredGraph graph = graph(); FixedNode next
 * = this.next();
 * 
 * WordCastNode oopOrHub = graph.addOrUnique(WordCastNode.addressToWord(oop,
 * tool.getWordTypes().getWordKind())); graph.addBeforeFixed(this, oopOrHub); // set bit 0 to 1, to
 * indicate a scalarized return value ValueNode result = graph.addOrUnique(new AndNode(oopOrHub,
 * graph.addOrUnique(ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1)))); LogicNode
 * isAlreadyBuffered = graph.addOrUnique(new IntegerEqualsNode(result,
 * ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1, graph))); //
 * graph.replaceFixed(this, taggedHub);
 * 
 * BeginNode trueBegin = graph.add(new BeginNode()); BeginNode falseBegin = graph.add(new
 * BeginNode());
 * 
 * IfNode ifNode = graph.add(new IfNode(isAlreadyBuffered, trueBegin, falseBegin,
 * ProfileData.BranchProbabilityData.unknown())); ((FixedWithNextNode)
 * this.predecessor()).setNext(ifNode);
 * 
 * // true branch - inline object is already buffered
 * 
 * EndNode trueEnd = graph.add(new EndNode()); trueBegin.setNext(trueEnd);
 * 
 * // false branch - inline object is not buffered
 * 
 * EndNode falseEnd = graph.add(new EndNode()); ResolvedJavaField[] fields =
 * type.getInstanceFields(true); for (int i = 0; i < fields.length; i++) {
 * 
 * StoreFieldNode storeField = nodes.get(i); ResolvedJavaField field = storeField.field(); ValueNode
 * object = storeField.object(); assert StampTool.isPointerNonNull(object) :
 * "store to null-restricted flat field should include null check";
 * 
 * ValueNode value = implicitStoreConvert(graph, getStorageKind(storeField.field()),
 * storeField.value());
 * 
 * AddressNode address = createFieldAddress(graph, object, field); BarrierType barrierType =
 * barrierSet.fieldWriteBarrierType(field, getStorageKind(field)); WriteNode memoryWrite = new
 * WriteNode(address, overrideFieldLocationIdentity(storeFlatField.getLocationIdentity()), value,
 * barrierType, storeField.getMemoryOrder());
 * 
 * memoryWrite = graph.add(memoryWrite);
 * 
 * if (i != nodes.size() - 1) { // assign invalid framestate because writes don't exist in bytecode
 * memoryWrite.setStateAfter(graph.addOrUnique(new
 * FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI))); graph.addBeforeFixed(storeFlatField,
 * memoryWrite); } else { // only last write operation gets a vaild framestate
 * memoryWrite.setStateAfter(storeFlatField.stateAfter()); graph.replaceFixed(storeFlatField,
 * memoryWrite); }
 * 
 * } if (falseBegin.next() == null) falseBegin.setNext(falseEnd);
 * 
 * // falseBegin.setNext(falseEnd);
 * 
 * // merge MergeNode merge = graph.add(new MergeNode());
 * 
 * graph.add(new ValuePhiNode(StampFactory.objectNonNull(), merge, oop, this));
 * 
 * merge.addForwardEnd(trueEnd); merge.addForwardEnd(falseEnd); merge.setNext(next);
 * 
 * super.lower(tool); }
 */

    @Override
    public void virtualize(VirtualizerTool tool) {
        /*
         * Reference objects can escape into their ReferenceQueue at any safepoint, therefore
         * they're excluded from escape analysis.
         */
        if (!tool.getMetaAccess().lookupJavaType(Reference.class).isAssignableFrom(type) &&
                        tool.getMetaAccessExtensionProvider().canVirtualize(type)) {
// IsNullNode isNullNode = new IsNullNode(oop);
// tool.addNode(isNullNode);
// LogicNode isInit = LogicNegationNode.create(isNullNode);
// tool.addNode(isInit);
            LogicNode isInit = LogicNegationNode.create(new IsNullNode(oop));
            tool.addNode(isInit);
            tool.addNode(new FixedGuardNode(isInit, DeoptimizationReason.TransferToInterpreter, DeoptimizationAction.None));
            VirtualInstanceNode virtualObject = new VirtualInstanceNode(type, false);
            ResolvedJavaField[] fields = virtualObject.getFields();
            ValueNode[] state = new ValueNode[fields.length];
            for (int i = 0; i < state.length; i++) {
                state[i] = scalarizedInlineType.get(i);
            }
            tool.createVirtualObject(virtualObject, state, Collections.emptyList(), getNodeSourcePosition(), false, oop);
            tool.replaceWithVirtual(virtualObject);
        }
    }

}
