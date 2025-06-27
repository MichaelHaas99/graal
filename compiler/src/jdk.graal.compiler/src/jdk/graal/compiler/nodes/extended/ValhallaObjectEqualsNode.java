package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.InputType.State;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.calc.CanonicalCondition;
import jdk.graal.compiler.core.common.spi.ForeignCallSignature;
import jdk.graal.compiler.core.common.type.AbstractPointerStamp;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Graph;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.hotspot.nodes.ValueObjectMethodNode;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.AbstractStateSplit;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DeoptBciSupplier;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FieldLocationIdentity;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.ReturnNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.FloatNormalizeCompareNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.calc.PointerEqualsNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.memory.MemoryAccess;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.inlining.InliningUtil;
import jdk.graal.compiler.replacements.nodes.MacroInvokable;
import jdk.graal.compiler.replacements.nodes.MacroNode;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Determines if two objects are equal with Valhalla semantics. The node needs to be fixed, because
 * we may perform a substitutability check. The substitutability check compares the field values of
 * two inline objects recursively, which is therefore a memory access. It becomes floating if no
 * substitutability check needs to be performed.
 */
@NodeInfo(cycles = CYCLES_UNKNOWN, cyclesRationale = "We don't know statically the size of the inlined comparison.", size = SIZE_UNKNOWN, sizeRationale = "We don't know statically how much code for the inlined comparison will be generated")
public class ValhallaObjectEqualsNode extends AbstractStateSplit implements Lowerable, Canonicalizable, MemoryAccess, SingleMemoryKill, Virtualizable, DeoptBciSupplier {
    public static final NodeClass<ValhallaObjectEqualsNode> TYPE = NodeClass.create(ValhallaObjectEqualsNode.class);
    @Input protected ValueNode x;
    @Input protected ValueNode y;

    private int bci = BytecodeFrame.UNKNOWN_BCI;

    private static final ValhallaObjectEqualsOp OP = new ValhallaObjectEqualsOp();

    public ValueNode getX() {
        return x;
    }

    public ValueNode getY() {
        return y;
    }

    public void setX(ValueNode newX) {
        assert newX != null;
        updateUsages(x, newX);
        this.x = newX;
    }

    public void setY(ValueNode newY) {
        assert newY != null;
        updateUsages(y, newY);
        this.y = newY;
    }

    private Object profile;

    public Object getProfile() {
        return profile;
    }

    public ValhallaObjectEqualsNode(ValueNode x, ValueNode y, Object profile, ResolvedJavaType operandInlineType) {
        super(TYPE, StampFactory.forInteger(JavaKind.Int, 0, 1));
        assert x != null;
        assert y != null;
        this.x = x;
        this.y = y;
        this.profile = profile;
        this.operandInlineType = operandInlineType;
        updateOperandInlineType();
    }

    public ValhallaObjectEqualsNode(ValueNode x, ValueNode y, Object profile) {
        this(x, y, profile, null);
    }

    public static LogicNode create(GraphBuilderContext b, ValueNode x, ValueNode y, NodeView view, Object profile) {
        LogicNode result = OP.canonical(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, CanonicalCondition.EQ, false, x, y, view, b.getValhallaOptionsProvider());
        if (result != null) {
            return result;
        }

        result = CompareNode.tryConstantFold(CanonicalCondition.EQ, x, y, b.getConstantReflection(), false);
        if (result != null) {
            return result;
        } else {

            result = PointerEqualsNode.findSynonym(x, y, view);
            if (result != null) {
                return result;
            }

            if (!InlineTypeUtil.mayNeedSubstitutabilityCheck(x, y, b.getValhallaOptionsProvider())) {
                return new ObjectEqualsNode(x, y);
            }

            ValhallaObjectEqualsNode fixedEqualityCheck = new ValhallaObjectEqualsNode(x, y, profile);
            if (!fixedEqualityCheck.canInlineSubstitutabilityCheck()) {
                fixedEqualityCheck.setBci(b.bci());
                // push the return value such that the framestate includes ot
                fixedEqualityCheck = b.addPush(JavaKind.Int, fixedEqualityCheck);

                // insert a proxy as the framestate of the invoke shouldn't be used
                b.pop(JavaKind.Int);
                b.add(new StateSplitProxyNode());
            } else {
                fixedEqualityCheck = b.add(fixedEqualityCheck);
            }

            return b.add(IntegerEqualsNode.create(fixedEqualityCheck, ConstantNode.forInt(1, b.getGraph()), view));
        }
    }

    @Override
    public boolean hasSideEffect() {
        return !canInlineSubstitutabilityCheck();
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        NodeView view = NodeView.from(tool);

        LogicNode value = OP.canonical(tool.getConstantReflection(), tool.getMetaAccess(), tool.getOptions(), tool.smallestCompareWidth(), CanonicalCondition.EQ, false, x, y, view,
                        tool.getValhallaOptionsProvider());
        if (value != null) {
            return new ConditionalNode(value, ConstantNode.forInt(1), ConstantNode.forInt(0));
        }
        if (!InlineTypeUtil.mayNeedSubstitutabilityCheck(x, y, tool.getValhallaOptionsProvider())) {
            return new ConditionalNode(new ObjectEqualsNode(x, y), ConstantNode.forInt(1), ConstantNode.forInt(0));
        }
        updateOperandInlineType();
        return this;
    }

    @Override
    public int bci() {
        return bci;
    }

    @Override
    public void setBci(int bci) {
        this.bci = bci;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return canInlineSubstitutabilityCheck() ? MemoryKill.NO_LOCATION : LocationIdentity.any();
    }

    public static class ValhallaObjectEqualsOp extends PointerEqualsNode.PointerEqualsOp {

        @Override
        protected LogicNode canonicalizeSymmetricConstant(ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, Integer smallestCompareWidth,
                        CanonicalCondition condition, Constant constant, ValueNode nonConstant, boolean mirrored, boolean unorderedIsTrue, NodeView view,
                        ValhallaOptionsProvider valhallaOptionsProvider, ValueNode constantValue) {
            ResolvedJavaType type = constantReflection.asJavaType(constant);
            if (type != null && nonConstant instanceof GetClassNode getClassNode) {
                ValueNode object = getClassNode.getObject();
                assert ((ObjectStamp) object.stamp(view)).nonNull() : "getClassNode %s object %s should have a non-null stamp, got: %s".formatted(getClassNode, object, object.stamp(view));
                if (!type.isPrimitive() && (type.isConcrete() || type.isArray())) {
                    return InstanceOfNode.create(TypeReference.createExactTrusted(type), object);
                }
                return LogicConstantNode.forBoolean(false);
            }

            if (!InlineTypeUtil.mayNeedSubstitutabilityCheck(constantValue, nonConstant, valhallaOptionsProvider) &&
                            (nonConstant instanceof AbstractNewObjectNode || nonConstant instanceof AllocatedObjectNode)) {
                // guard against class hierarchy changes
                assert !(nonConstant instanceof BoxNode) : Assertions.errorMessageContext("nonConstant", nonConstant);
                // a constant can never be equals to a new object
                return LogicConstantNode.forBoolean(false);
            }
            return super.canonicalizeSymmetricConstant(constantReflection, metaAccess, options, smallestCompareWidth, condition, constant, nonConstant, mirrored, unorderedIsTrue, view,
                            valhallaOptionsProvider, constantValue);
        }

        @Override
        protected LogicNode duplicateModified(ValueNode newX, ValueNode newY, boolean unorderedIsTrue, NodeView view, ValhallaOptionsProvider valhallaOptionsProvider) {
            if (InlineTypeUtil.mayNeedSubstitutabilityCheck(newX, newY, valhallaOptionsProvider)) {
                return null;
            } else if (newX.stamp(view) instanceof ObjectStamp && newY.stamp(view) instanceof ObjectStamp) {
                return ObjectEqualsNode.create(newX, newY, view);
            } else if (newX.stamp(view) instanceof AbstractPointerStamp && newY.stamp(view) instanceof AbstractPointerStamp) {
                return PointerEqualsNode.create(newX, newY, view);
            }
            throw GraalError.shouldNotReachHereUnexpectedValue(newX.stamp(view) + " " + newY.stamp(view)); // ExcludeFromJacocoGeneratedReport
        }
    }

    public boolean canInlineSubstitutabilityCheck() {
        ResolvedJavaType type = getOperandInlineType();
        return type != null && !InlineTypeUtil.isCircularInlineType(type);
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        ResolvedJavaType type = getOperandInlineType();
        if (type != null) {
            ResolvedJavaField[] fields = type.getInstanceFields(true);
            if (fields.length == 1) {
                // allow GVN
                return new FieldLocationIdentity(fields[0], false);
            }
        }
        return LocationIdentity.any();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (true) {
            return;
        }
        LogicNode node = ObjectEqualsNode.virtualizeComparison(getX(), getY(), graph(), tool);
        if (node == null) {
            return;
        }

        ValueNode result = new ConditionalNode(node, ConstantNode.forInt(1), ConstantNode.forInt(0));
        tool.ensureAdded(result);
        tool.replaceWithValue(result);
    }

    private ResolvedJavaType operandInlineType;

    public ResolvedJavaType getOperandInlineType() {
        return operandInlineType;
    }

    public void updateOperandInlineType() {
        ResolvedJavaType temp;
        if (StampTool.isNullableInlineType(getX(), null)) {
            temp = StampTool.typeOrNull(getX());
        } else if (StampTool.isNullableInlineType(getY(), null)) {
            temp = StampTool.typeOrNull(getY());
        } else {
            temp = null;
        }
        if (operandInlineType != null && temp == null) {
            // don't update the type it it became weaker, TODO: is this possible?
            return;
        }
        operandInlineType = temp;
    }

    private PhiNode performEqualityCheck(LoweringTool tool, ValueNode x, ValueNode y, StructuredGraph newGraph, ResolvedJavaType type, boolean inlineSubstitutabilityCheck,
                    FixedWithNextNode startPrevious) {
        // create the merge and phi node
        MergeNode merge = newGraph.add(new MergeNode());
        merge.setStateAfter(newGraph.addOrUnique(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));
        ValuePhiNode phiNode = newGraph.addOrUnique(new ValuePhiNode(StampFactory.forKind(JavaKind.Boolean), merge));

        FixedWithNextNode previous = startPrevious;
        // do a pointer comparison
        previous = createIf(newGraph, ObjectEqualsNode.create(x, y, NodeView.DEFAULT), true, previous, merge, phiNode, true);

        // check if one operand is null
        previous = createIf(newGraph, IsNullNode.create(x), true, previous, merge, phiNode, false);
        previous = createIf(newGraph, IsNullNode.create(y), true, previous, merge, phiNode, false);

        // cast both operands to non-null
        x = newGraph.addOrUnique(PiNode.create(x, previous));
        y = newGraph.addOrUnique(PiNode.create(y, previous));

        // check if both operands have no identity
        if (!StampTool.isInlineType(x, tool.getValhallaOptionsProvider())) {
            previous = createIf(newGraph, new HasIdentityNode(x), true, previous, merge, phiNode, false);
        }
        if (!StampTool.isInlineType(y, tool.getValhallaOptionsProvider())) {
            previous = createIf(newGraph, new HasIdentityNode(y), true, previous, merge, phiNode, false);
        }

        // check if both operands are of the same type
        ValueNode xHub = LoadHubNode.create(x, tool.getStampProvider(), tool.getMetaAccess(), tool.getConstantReflection());
        xHub = newGraph.addOrUnique(xHub);
        ValueNode yHub = LoadHubNode.create(y, tool.getStampProvider(), tool.getMetaAccess(), tool.getConstantReflection());
        yHub = newGraph.addOrUnique(yHub);
        previous = createIf(newGraph, PointerEqualsNode.create(xHub, yHub, NodeView.DEFAULT), false, previous, merge, phiNode, false);

        if (inlineSubstitutabilityCheck) {
            ResolvedJavaField[] fields = type.getInstanceFields(true);
            for (int i = 0; i < fields.length; i++) {
                ResolvedJavaField field = fields[i];
                LoadFieldNode load0 = newGraph.add(LoadFieldNode.create(newGraph.getAssumptions(), x, field));
                newGraph.addAfterFixed(previous, load0);
                LoadFieldNode load1 = newGraph.add(LoadFieldNode.create(newGraph.getAssumptions(), y, field));
                newGraph.addAfterFixed(load0, load1);
                Stamp stamp = StampFactory.forDeclaredType(newGraph.getAssumptions(), field.getType(), false).getTrustedStamp();
                previous = load1;
                LogicNode logicNode = null;
                if (stamp.isIntegerStamp()) {
                    logicNode = IntegerEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
                                    newGraph.getOptions(), null, load0, load1, NodeView.DEFAULT);
                } else if (stamp.isObjectStamp()) {
                    if (!InlineTypeUtil.mayNeedSubstitutabilityCheck(load0, load1, tool.getValhallaOptionsProvider())) {
                        logicNode = ObjectEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
                                        newGraph.getOptions(), load0, load1, NodeView.DEFAULT);
                    } else {
                        PhiNode result = performEqualityCheck(tool, load0, load1, newGraph, (ResolvedJavaType) field.getType(), true, previous);
                        previous = result.merge();
                        logicNode = new IntegerEqualsNode(result, ConstantNode.forInt(1, newGraph));
                    }
                } else if (stamp.isFloatStamp()) {
                    ValueNode normalizeNode = FloatNormalizeCompareNode.create(load0, load1, true, JavaKind.Int,
                                    tool.getConstantReflection());
                    ValueNode constantZero = ConstantNode.forBoolean(false, newGraph);
                    logicNode = IntegerEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
                                    newGraph.getOptions(), null, normalizeNode, constantZero, NodeView.DEFAULT);
                } else {
                    throw GraalError.shouldNotReachHere("Unexpected stamp type");
                }
                if (i == fields.length - 1) {
                    // create conditional for last comparison
                    EndNode end = createEndNode(newGraph);
                    previous.setNext(end);
                    merge.addForwardEnd(end);
                    LogicNode comparison = newGraph.addOrUnique(logicNode);
                    phiNode.addInput(newGraph.addOrUnique(ConditionalNode.create(comparison, NodeView.DEFAULT)));
                } else {
                    previous = createIf(newGraph, logicNode, false, previous, merge, phiNode, false);
                }
            }

        } else {
            ResolvedJavaMethod substitutabilityMethod = GraalValhallaServices.getIsSubstitutableMethod(tool.getMetaAccess());
            FixedWithNextNode comparisonNode = null;
            if (bci() == BytecodeFrame.UNKNOWN_BCI) {
                // need to use foreign call
                comparisonNode = new ForeignCallNode(tool.getForeignCalls().lookupForeignCall(SUBSTITUTABILITY_CHECK).getDescriptor(), x, y);
            } else {
                comparisonNode = ValueObjectMethodNode.create(MacroNode.MacroParams.of(CallTargetNode.InvokeKind.Static, substitutabilityMethod,
                                substitutabilityMethod, bci(),
                                StampPair.createSingle(stamp(NodeView.DEFAULT)), x, y), newGraph.addOrUnique(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));
            }
            newGraph.add(comparisonNode);
            newGraph.addAfterFixed(previous, comparisonNode);
            EndNode end = createEndNode(newGraph);
            comparisonNode.setNext(end);
            merge.addForwardEnd(end);
            LogicNode comparison = newGraph.addOrUnique(IntegerEqualsNode.create(comparisonNode, ConstantNode.forBoolean(true, newGraph), NodeView.DEFAULT));
            phiNode.addInput(newGraph.addOrUnique(ConditionalNode.create(comparison, NodeView.DEFAULT)));
        }
        return phiNode;
    }

    private StructuredGraph getLoweredSnippetGraph(LoweringTool tool) {
        Assumptions assumptions = graph().getAssumptions();
        StructuredGraph newGraph = new StructuredGraph.Builder(graph().getOptions(), graph().getDebug(), StructuredGraph.AllowAssumptions.ifNonNull(assumptions)).name("<==>").build();

        // create the parameters
        ParameterNode param0 = newGraph.addWithoutUnique(new ParameterNode(0, StampPair.createSingle(getX().stamp(NodeView.DEFAULT))));
        ParameterNode param1 = newGraph.addWithoutUnique(new ParameterNode(1, StampPair.createSingle(getY().stamp(NodeView.DEFAULT))));

        PhiNode phiNode = performEqualityCheck(tool, param0, param1, newGraph, getOperandInlineType(), canInlineSubstitutabilityCheck(), newGraph.start());
        AbstractMergeNode merge = phiNode.merge();

        ReturnNode returnNode = newGraph.add(new ReturnNode(phiNode));
        newGraph.addAfterFixed(merge, returnNode);
        return MacroInvokable.lowerReplacement(graph(), newGraph, tool);

    }

    public static final ForeignCallSignature SUBSTITUTABILITY_CHECK = new ForeignCallSignature("substitutabilityCheck",
                    boolean.class, Object.class,
                    Object.class);

    private BeginNode createIf(StructuredGraph graph, LogicNode condition, boolean stopOnTrue, FixedWithNextNode previous, MergeNode merge, ValuePhiNode phiNode, boolean stopValue) {
        ConstantNode trueValue = ConstantNode.forBoolean(true, graph);
        ConstantNode falseValue = ConstantNode.forBoolean(false, graph);
        ConstantNode phiValue = stopValue ? trueValue : falseValue;

        BeginNode falseBegin = createBeginNode(graph);
        BeginNode trueBegin = createBeginNode(graph);
        IfNode ifNode = graph.add(new IfNode(graph.addOrUnique(condition), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        graph.addAfterFixed(previous, ifNode);
        ifNode.setTrueSuccessor(trueBegin);
        EndNode end = createEndNode(graph);
        merge.addForwardEnd(end);
        phiNode.addInput(phiValue);
        if (stopOnTrue) {
            trueBegin.setNext(end);
            return falseBegin;
        } else {
            falseBegin.setNext(end);
            return trueBegin;
        }

    }

    private BeginNode createBeginNode(StructuredGraph graph) {
        return graph.add(new BeginNode());
    }

    private EndNode createEndNode(StructuredGraph graph) {
        return graph.add(new EndNode());
    }

    @OptionalInput(State) protected FrameState stateDuring;

    public FrameState stateDuring() {
        return stateDuring;
    }

    public void setStateDuring(FrameState stateDuring) {
        updateUsages(this.stateDuring, stateDuring);
        this.stateDuring = stateDuring;
    }

    // TODO: implement full functionality like in the ObjectEqualsSnippet, remove the snippet
    @Override
    public void lower(LoweringTool tool) {
        if (false) {
            tool.getLowerer().lower(this, tool);
            return;
        }
        if (bci() == BytecodeFrame.UNKNOWN_BCI && !canInlineSubstitutabilityCheck()) {
            throw GraalError.shouldNotReachHere("Cannot lower substitutability check");
            // tool.getLowerer().lower(this, tool);
            // return;
        }
        StructuredGraph graph = graph();
        ResolvedJavaMethod substitutabilityMethod = GraalValhallaServices.getIsSubstitutableMethod(tool.getMetaAccess());
        if (true) {
            StructuredGraph replacementGraph = getLoweredSnippetGraph(tool);
            InvokeNode invoke;

            // create a dummy invoke node
            ValueNode[] arguments = {getX(), getY()};
            MethodCallTargetNode callTarget = graph.add(new MethodCallTargetNode(CallTargetNode.InvokeKind.Static, substitutabilityMethod, arguments,
                            StampPair.createSingle(stamp(NodeView.DEFAULT)), null));
            invoke = graph.add(new InvokeNode(callTarget, bci(), MemoryKill.NO_LOCATION));
            if (stateAfter() != null) {
                // replace the top of the stack with the invoke node
                invoke.setStateAfter(stateAfter().duplicate());
                invoke.stateAfter().replaceFirstInput(invoke.stateAfter().stackAt(invoke.stateAfter().stackSize() - 1), invoke);
            } else {
                // TODO: set correct context method, current argument used as a dummy
                invoke.setContextMethod(substitutabilityMethod);
            }

            FrameState frameState = stateAfter();
            if (frameState != null) {
                frameState = frameState.duplicate();
            }
            FrameState lastFrameState = GraphUtil.findLastFrameState((FixedNode) this.predecessor());
            if (lastFrameState != null) {
                lastFrameState = lastFrameState.duplicate();
            }
            graph.replaceFixedWithFixed(this, invoke);

            Graph.Mark newNodes = graph.getMark();
            InliningUtil.inline(invoke, replacementGraph, false, substitutabilityMethod, "Replace with graph.", "LoweringPhase");

            for (Node n : graph.getNewNodes(newNodes)) {
                if (n instanceof InvokeNode invokeNode && frameState != null) {
                    // assign a call to the library a framestate with the bci to the acmp bytecode,
                    // see SharedRuntime::find_callee_info_helper in sharedRuntime.cpp
                    FrameState localFrameState = frameState.duplicate();
                    invokeNode.setStateAfter(localFrameState);
                    localFrameState.replaceFirstInput(localFrameState.stackAt(localFrameState.stackSize() - 1), invokeNode);
                } else if (n instanceof MergeNode mergeNode) {
                    if (lastFrameState != null) {
                        mergeNode.setStateAfter(lastFrameState.duplicate());
                    }
                }
            }
            return;
        }

        // directly jump to the library
        ValueObjectMethodNode node = ValueObjectMethodNode.create(MacroNode.MacroParams.of(CallTargetNode.InvokeKind.Static, substitutabilityMethod,
                        substitutabilityMethod, stateDuring().bci,
                        StampPair.createSingle(stamp(NodeView.DEFAULT)), getX(), getY()), graph.addOrUnique(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));
        graph.add(node);
        node.setStateAfter(stateAfter().duplicate());
        // node.setStateDuring(stateDuring().duplicate());
        graph.addBeforeFixed(this, node);
        this.replaceAtUsages(node);
        node.lower(tool);
        graph.removeFixed(this);
    }

}
