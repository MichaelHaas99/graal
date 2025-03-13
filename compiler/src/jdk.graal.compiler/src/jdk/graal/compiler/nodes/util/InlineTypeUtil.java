package jdk.graal.compiler.nodes.util;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.MembarNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectState;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Contains utility functions often needed in conjunction with inline types.
 */
public class InlineTypeUtil {

    static Class<? extends Throwable> identityExceptionClass;

    private static boolean identityExceptionClassAvailable;

    static {
        try {
            identityExceptionClass = (Class<? extends Throwable>) Class.forName("java.lang.IdentityException");
            identityExceptionClassAvailable = true;
        } catch (Exception e) {
            // just use the null pointer exception class as dummy which shouldn't be used
            identityExceptionClass = NullPointerException.class;
            identityExceptionClassAvailable = false;
        }
    }

    public static boolean isIdentityExceptionClassAvailable() {
        return identityExceptionClassAvailable;
    }

    public static Class<? extends Throwable> getIdentityExceptionClass() {
        return identityExceptionClass;
    }

    public static RuntimeException createIdentityExceptionInstance() {
        try {
            return (RuntimeException) identityExceptionClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new GraalError(e);
        }
    }

    public static void scalarizeInvokeArgs(Invoke invoke) {
        handleDevirtualizationOnCallTarget((MethodCallTargetNode) invoke.callTarget(), invoke.getTargetMethod(), invoke.getTargetMethod(), true);
    }

    public static void boxScalarizedArgs(Invoke invoke) {
        StructuredGraph graph = invoke.asNode().graph();
        CallTargetNode callTarget = invoke.callTarget();

        ResolvedJavaMethod targetMethod = callTarget.targetMethod();
        int parameterLength = targetMethod.getSignature().getParameterCount(!targetMethod.isStatic());
        List<ValueNode> arguments = callTarget.arguments();
        ArrayList<ValueNode> newArguments = new ArrayList<>(parameterLength);
        int currentIndex = 0;
        for (int i = 0; i < parameterLength; i++) {
            if (targetMethod.isScalarizedParameter(i, true)) {
                int scalarizedParametersLen = targetMethod.getScalarizedParameter(i, true).length;
                InlineTypeNode inlineTypeNode;
                if (targetMethod.isParameterNullFree(i, true)) {
                    inlineTypeNode = InlineTypeNode.createNonNullWithoutOop(getParameterType(targetMethod, i, true),
                                    arguments.subList(currentIndex, scalarizedParametersLen).toArray(new ValueNode[parameterLength]));
                } else {
                    inlineTypeNode = InlineTypeNode.createWithoutOop(getParameterType(targetMethod, i, true),
                                    arguments.subList(currentIndex + 1, scalarizedParametersLen).toArray(new ValueNode[parameterLength - 1]), arguments.get(parameterLength));
                }
                graph.add(inlineTypeNode);
                graph.addBeforeFixed(invoke.asFixedNode(), inlineTypeNode);
                currentIndex += parameterLength;
                newArguments.add(inlineTypeNode);
            } else {
                newArguments.add(arguments.get(currentIndex++));
            }
        }
        callTarget.arguments().clear();
        callTarget.arguments().addAll(newArguments);
    }

    private static ResolvedJavaType getParameterType(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        boolean includeReceiver = indexIncludesReceiverIfExists && !method.isStatic();
        if (includeReceiver) {
            if (index == 0) {
                return method.getDeclaringClass();
            } else {
                index--;
            }
        }
        return method.getSignature().getParameterType(index, method.getDeclaringClass()).resolve(method.getDeclaringClass());
    }

    /**
     * Responsible for the scalarization of the receiver after devirtualization happened. According
     * to the calling convention an inline type receiver is expected to be scalarized.
     *
     * @param callTargetNode the call target of whose receiver was devirtualized
     * @param oldMethod the old method before devirtualization
     * @param newMethod the method after devirtiualization
     * @param nothingScalarizedYet determines if no arguments of the old method were scalarized yet
     */
    public static void handleDevirtualizationOnCallTarget(MethodCallTargetNode callTargetNode, ResolvedJavaMethod oldMethod, ResolvedJavaMethod newMethod, boolean nothingScalarizedYet) {
        int parameterLength = oldMethod.getSignature().getParameterCount(!oldMethod.isStatic());
        if (nothingScalarizedYet) {
            if (callTargetNode.arguments().size() != parameterLength)
                throw new GraalError("Expected actual argument size to be equal to signature parameter size" + callTargetNode.toString() + "\n" + callTargetNode.arguments() + "\n");
        }

        if (callTargetNode.getScalarizedArguments().isEmpty()) {
            callTargetNode.getScalarizedArguments().addAll(callTargetNode.arguments());
        }
        List<ValueNode> arguments = callTargetNode.getScalarizedArguments();
        boolean[] scalarizeParameters = new boolean[parameterLength];
        int argumentIndex = 0;
        for (int i = 0; i < parameterLength; i++) {
            scalarizeParameters[i] = (!oldMethod.isScalarizedParameter(i, true) || nothingScalarizedYet) && newMethod.isScalarizedParameter(i, true);
        }
        ArrayList<ValueNode> scalarizedArgs = new ArrayList<>(parameterLength);
        for (int signatureIndex = 0; signatureIndex < parameterLength; signatureIndex++) {
            if (scalarizeParameters[signatureIndex]) {
                ValueNode[] scalarized = createScalarizationCFGForInvokeArg(callTargetNode, arguments.get(argumentIndex), newMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
                argumentIndex++;
            } else {
                if (oldMethod.isScalarizedParameter(signatureIndex, true) && !nothingScalarizedYet) {
                    int length = oldMethod.getScalarizedParameter(signatureIndex, true).length;
                    scalarizedArgs.addAll(arguments.subList(argumentIndex, argumentIndex + length));
                    argumentIndex += length;
                } else {
                    scalarizedArgs.add(arguments.get(argumentIndex));
                    argumentIndex++;
                }
            }

        }
        arguments.clear();
        arguments.addAll(scalarizedArgs);
    }

    /**
     * Can be used to scalarize the arguments of a method during parsing.
     *
     * @param callTargetNode the call target of whose arguments need to be scalarized.
     * @param method the old method before devirtualization
     *
     */
    public static void scalarizeInvokeArgs(MethodCallTargetNode callTargetNode, ResolvedJavaMethod method) {
        handleDevirtualizationOnCallTarget(callTargetNode, method, method, true);
    }

    /**
     *
     * Similar to
     * {@link #createScalarizationCFGForInvokeArg(FixedNode, ValueNode, ResolvedJavaMethod, int)}
     * but expects a call target as parameter and inserts all nodes before
     * {@link CallTargetNode#invoke()}.
     */
    private static ValueNode[] createScalarizationCFGForInvokeArg(CallTargetNode callTargetNode, ValueNode arg, ResolvedJavaMethod targetMethod, int signatureIndex) {
        return createScalarizationCFGForInvokeArg(callTargetNode.invoke().asFixedNode(), arg, targetMethod, signatureIndex);
    }

    /**
     *
     * @param addBefore the node before the diamond should be inserted into the graph
     * @param arg the object whose field values should be loaded
     * @param targetMethod the argument's method
     * @param signatureIndex the argument's index in the method signature including the receiver if
     *            it exits.
     * @return the phi nodes representing the field values of the argument
     */
    private static ValueNode[] createScalarizationCFGForInvokeArg(FixedNode addBefore, ValueNode arg, ResolvedJavaMethod targetMethod, int signatureIndex) {
        StructuredGraph graph = addBefore.graph();
        boolean isNullFree = targetMethod.isParameterNullFree(signatureIndex, true);

        return createScalarizationCFG(addBefore, arg,
                        graph.addOrUnique(LogicNegationNode.create(graph.addOrUnique(IsNullNode.create(arg)))),
                        targetMethod.getScalarizedParameterFields(signatureIndex, true), isNullFree, !isNullFree);
    }

    /**
     *
     * See
     * {@link #createScalarizationCFG(FixedNode, ValueNode, LogicNode, ResolvedJavaField[], boolean, boolean)}
     */
    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, LogicNode nonNull, ResolvedJavaField[] fields) {
        return createScalarizationCFG(addBefore, object, nonNull, fields, false, false);
    }

    /**
     * Scalarizes an object into it's field values.
     *
     * @param addBefore the node before the diamond should be inserted into the graph
     * @param object the object whose field values should be loaded
     * @param nonNullCheck condition used as branch condition, indicating if the object is not null
     * @param fields the resolved filed
     * @param assumeObjectNonNull true if no diamond should be created
     * @param includeNonNullPhi true if the non-null information should be included in the return
     *            phis at position zero
     * @return The field values of the object
     */
    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, LogicNode nonNullCheck, ResolvedJavaField[] fields, boolean assumeObjectNonNull,
                    boolean includeNonNullPhi) {
        StructuredGraph graph = addBefore.graph();
        if (nonNullCheck.isTautology() || assumeObjectNonNull) {
            assert StampTool.isPointerNonNull(object) : "expected parameter to be non-null, insert a null check";
            ValueNode[] loads = new ValueNode[fields.length + (includeNonNullPhi ? 1 : 0)];
            if (includeNonNullPhi) {
                loads[0] = ConstantNode.forByte((byte) 1, graph);
            }
            for (int i = 0; i < fields.length; i++) {
                LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), object, fields[i]));
                loads[i + (includeNonNullPhi ? 1 : 0)] = load;
                graph.addBeforeFixed(addBefore, load);
            }
            return loads;
        }
        if (nonNullCheck.isContradiction()) {
            ValueNode[] loads = new ValueNode[fields.length + (includeNonNullPhi ? 1 : 0)];
            if (includeNonNullPhi) {
                loads[0] = ConstantNode.forByte((byte) 0, graph);
            }
            for (int i = 0; i < fields.length; i++) {
                ConstantNode load = graph.addOrUnique(ConstantNode.defaultForKind(fields[i].getJavaKind()));
                loads[i + (includeNonNullPhi ? 1 : 0)] = load;
            }
            return loads;
        }

        BeginNode trueBegin = graph.add(new BeginNode());
        BeginNode falseBegin = graph.add(new BeginNode());

        IfNode ifNode = graph.add(new IfNode(graph.addOrUnique(nonNullCheck), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        ((FixedWithNextNode) addBefore.predecessor()).setNext(ifNode);

        // get a valid framestate for the merge node
        FrameState framestate = GraphUtil.findLastFrameState(ifNode);

        ValueNode[] loads = new ValueNode[fields.length];
        ValueNode[] consts = new ValueNode[fields.length];

        // true branch - inline object is non-null, load the field values

        ValueNode nonNull = graph.addOrUnique(PiNode.create(object, objectNonNull(), trueBegin));
        FixedWithNextNode previous = trueBegin;
        for (int i = 0; i < fields.length; i++) {
            LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), nonNull, fields[i]));
            loads[i] = load;
            previous.setNext(load);
            previous = load;
        }
        EndNode trueEnd = graph.add(new EndNode());
        previous.setNext(trueEnd);

        // false branch - inline object is null, use default values of fields

        for (int i = 0; i < fields.length; i++) {
            ConstantNode load = graph.addOrUnique(ConstantNode.defaultForKind(fields[i].getJavaKind()));
            consts[i] = load;
        }
        EndNode falseEnd = graph.add(new EndNode());
        if (falseBegin.next() == null)
            falseBegin.setNext(falseEnd);

        // merge
        MergeNode merge = graph.add(new MergeNode());
        merge.setStateAfter(framestate);

        // produces phi nodes
        ValuePhiNode[] phis;
        if (includeNonNullPhi) {
            phis = new ValuePhiNode[fields.length + 1];
            phis[0] = graph.addOrUnique(new ValuePhiNode(StampFactory.forKind(JavaKind.Byte), merge, ConstantNode.forByte((byte) 1, graph), ConstantNode.forByte((byte) 0, graph)));
            for (int i = 0; i < fields.length; i++) {
                phis[i + 1] = graph.addOrUnique(new ValuePhiNode(StampFactory.forDeclaredType(graph.getAssumptions(), fields[i].getType(), false).getTrustedStamp(), merge, loads[i], consts[i]));
            }
        } else {
            phis = new ValuePhiNode[fields.length];
            for (int i = 0; i < fields.length; i++) {
                phis[i] = graph.addOrUnique(new ValuePhiNode(StampFactory.forDeclaredType(graph.getAssumptions(), fields[i].getType(), false).getTrustedStamp(), merge, loads[i], consts[i]));
            }
        }

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        merge.setNext(addBefore);
        return phis;
    }


    /**
     * This function handles the case that an {@link Invoke} returns a nullable scalarized inline
     * object. It appends multiple {@link jdk.graal.compiler.nodes.extended.ReadMultiValueNode} to
     * the invoke node and creates an {@link InlineTypeNode} which gets these nodes as input. To
     * model the concept of a nullable scalarized inline object after an invoke, a
     * {@link VirtualInstanceNode} is pushed onto the framestate.
     */
    public static void handleScalarizedReturnOnInvoke(GraphBuilderContext b, Invoke invoke, JavaKind resultType) {
        InlineTypeNode result = InlineTypeNode.createFromInvoke(b, invoke);

        // create virtual object representing nullable scalarized inline object in the framestate
        VirtualObjectNode virtual = new VirtualInstanceNode(result.getType(), false);
        virtual.setObjectId(0);
        b.append(virtual);

        ValueNode[] newEntries = new ValueNode[result.getFieldValues().size()];

        for (int i = 0; i < newEntries.length; i++) {
            ValueNode entry = result.getFieldValues().get(i);
            newEntries[i] = entry;
        }

        // create a framestate for invoke with virtual object
        b.push(resultType, virtual);
        b.setStateAfter(invoke);
        invoke.stateAfter().addVirtualObjectMapping(b.append(new VirtualObjectState(virtual, newEntries, result.getNonNull())));
        b.pop(resultType);

        // push the InlineTypeNode as result
        b.push(resultType, result);
    }

    /**
     * Create as {@link LogicNode} indicating if an inline object is already allocated.
     */
    public static LogicNode createIsAllocatedOrNullCheck(StructuredGraph graph, ValueNode nonNull, ValueNode oop) {
        assert nonNull == null && oop == null || nonNull != null && oop != null : "both should be either null or not null";
        if (nonNull == null && oop == null) {
            return graph.addOrUnique(LogicConstantNode.contradiction());
        }
        LogicNode notNull = graph.addOrUnique(IntegerEqualsNode.create(nonNull, ConstantNode.forInt(1, graph), NodeView.DEFAULT));
        LogicNode oopIsNull = graph.addOrUnique(IsNullNode.create(oop));
        LogicNode check = graph.addOrUniqueWithInputs(
                        LogicNegationNode.create(LogicNode.and(notNull, oopIsNull, ProfileData.BranchProbabilityData.unknown())));
        return check;

    }

    /**
     * Determines if it is known at compile time if a scalarized inline object is already allocated
     * or null. E.g. this can be the case if the inline object is constant null.
     */
    public static boolean isAllocatedOrNull(StructuredGraph graph, ValueNode nonNull, ValueNode oop) {
        return createIsAllocatedOrNullCheck(graph, nonNull, oop).isTautology();
    }

    /**
     *
     * Creates a graph diamond in order to perform the materialization of an inline type. One branch
     * just reuses the oop and the other branch allocates the instance.
     *
     *
     * @param addBefore the node before the diamond should be inserted into the graph
     * @param nonNull node indicating if the inline object is null or not
     * @param oop represents either an inline object or null at runtime
     * @param writes write operations that should be performed on the allocation branch
     * @param addMembar true if a membar should be inserted on the allocation branch
     * @param newInstanceNode does the allocation on the allocation branch
     * @param type the type of the inline object
     * @return the phi node of the diamond representing the inline object
     */
    public static ValueNode createAllocationDiamond(FixedNode addBefore, ValueNode nonNull, ValueNode oop, List<WriteNode> writes, boolean addMembar, NewInstanceNode newInstanceNode,
                    ResolvedJavaType type) {
        StructuredGraph graph = addBefore.graph();


        LogicNode isAllocatedOrNull = createIsAllocatedOrNullCheck(graph, nonNull, oop);

        assert !isAllocatedOrNull.isTautology() : "should have been checked for tautology before";
        assert newInstanceNode != null && newInstanceNode.isAlive() : "NewInstanceNode should be alive";

        if (newInstanceNode == null) {
            assert type != null : "type for lowering inline type expected";
            newInstanceNode = graph.add(new NewInstanceNode(type, true));
        }


        if (isAllocatedOrNull.isContradiction()) {
            graph.addBeforeFixed(addBefore, newInstanceNode);
            for (WriteNode w : writes) {
                assert w != null && w.isAlive() : "WriteNode should be alive";
                graph.addBeforeFixed(addBefore, w);
            }
            if (addMembar) {
                // all fields implicitly final therefore use constructor freeze
                MembarNode memBar = graph.add(MembarNode.forInitialization());
                graph.addBeforeFixed(addBefore, memBar);
            }
            return newInstanceNode;
        }

        FrameState framestate = GraphUtil.findLastFrameState(addBefore);

        BeginNode trueBegin = graph.add(new BeginNode());
        BeginNode falseBegin = graph.add(new BeginNode());
        IfNode ifNode = graph.add(new IfNode(isAllocatedOrNull, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        ((FixedWithNextNode) addBefore.predecessor()).setNext(ifNode);

        // true branch - inline object is already allocated or is null

        EndNode trueEnd = graph.add(new EndNode());
        trueBegin.setNext(trueEnd);

        // false branch - inline object is not yet allocated

        EndNode falseEnd = graph.add(new EndNode());

        falseBegin.setNext(newInstanceNode);
        newInstanceNode.setNext(falseEnd);
        FixedWithNextNode previous = newInstanceNode;
        for (WriteNode w : writes) {
            assert w != null && w.isAlive() : "WriteNode should be alive";
            previous.setNext(w);
            w.setNext(falseEnd);
            previous = w;
        }
        if (addMembar) {
            // all fields implicitly final therefore use constructor freeze
            MembarNode memBar = graph.add(MembarNode.forInitialization());
            previous.setNext(memBar);
            memBar.setNext(falseEnd);
        }

        // merge
        MergeNode merge = graph.add(new MergeNode());
        merge.setStateAfter(framestate.duplicate());

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        merge.setNext(addBefore);
        ValuePhiNode phi = graph.addOrUnique(new ValuePhiNode(StampFactory.object(TypeReference.create(graph.getAssumptions(), type)), merge,
                        oop, newInstanceNode));
        return phi;

    }

    public static void insertLateInitWrites(FixedNode addBefore, ValueNode nonNull, ValueNode oop, List<WriteNode> writes) {
        StructuredGraph graph = addBefore.graph();

        LogicNode isAllocatedOrNull = createIsAllocatedOrNullCheck(graph, nonNull, oop);
        assert !isAllocatedOrNull.isTautology() : "should have been checked for tautology before";

        if (isAllocatedOrNull.isContradiction()) {
            for (WriteNode w : writes) {
                assert w != null && w.isAlive() : "WriteNode should be alive";
                graph.addBeforeFixed(addBefore, w);
            }
            return;
        }

        FrameState framestate = GraphUtil.findLastFrameState(addBefore);

        BeginNode trueBegin = graph.add(new BeginNode());
        BeginNode falseBegin = graph.add(new BeginNode());

        IfNode ifNode = graph.add(new IfNode(isAllocatedOrNull, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        ((FixedWithNextNode) addBefore.predecessor()).setNext(ifNode);

        // true branch - inline object is already allocated or is null

        EndNode trueEnd = graph.add(new EndNode());
        trueBegin.setNext(trueEnd);

        // false branch - inline object is not yet allocated

        EndNode falseEnd = graph.add(new EndNode());

        FixedWithNextNode previous = falseBegin;
        for (WriteNode w : writes) {
            previous.setNext(w);
            w.setNext(falseEnd);
            previous = w;
        }

        if (falseBegin.next() == null)
            falseBegin.setNext(falseEnd);

        // merge
        MergeNode merge = graph.add(new MergeNode());
        merge.setStateAfter(framestate.duplicate());

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        merge.setNext(addBefore);
    }


    public static class InlineTypeInfo {
        public InlineTypeInfo(ValueNode nonNull, ValueNode oop) {
            this.nonNull = nonNull;
            this.oop = oop;
        }

        private ValueNode nonNull;
        private ValueNode oop;
        private List<WriteNode> writes = new ArrayList<>();

        public ValueNode getNonNull() {
            return nonNull;
        }

        public ValueNode getOop() {
            return oop;
        }

        public List<WriteNode> getWrites() {
            return writes;
        }
    }


    public static boolean needsSubstitutabilityCheck(ValueNode x, ValueNode y, ValhallaOptionsProvider valhallaOptionsProvider) {
        return StampTool.canBeInlineType(x, valhallaOptionsProvider) && StampTool.canBeInlineType(y, valhallaOptionsProvider);
    }
}
