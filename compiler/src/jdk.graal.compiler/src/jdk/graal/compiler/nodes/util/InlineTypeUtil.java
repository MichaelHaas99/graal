package jdk.graal.compiler.nodes.util;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_IGNORED;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_IGNORED;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
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
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.ValhallaOptionsProvider;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectState;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.TriState;

public class InlineTypeUtil {

    public static void scalarizeInvokeArgs(Invoke invoke) {
        handleDevirtualizationOnCallTarget(invoke.callTarget(), invoke.getTargetMethod(), invoke.getTargetMethod(), true);
    }

    public static void boxScalarizedArgs(Invoke invoke) {
        StructuredGraph graph = invoke.asNode().graph();
        CallTargetNode callTarget = invoke.callTarget();
        // assert callTarget instanceof ResolvedMethodHandleCallTargetNode : "expected resolved
        // method handle call target";
        // ResolvedMethodHandleCallTargetNode methodHandleCallTargetNode =
        // (ResolvedMethodHandleCallTargetNode) callTarget;
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
                    inlineTypeNode = InlineTypeNode.createNullFreeWithoutOop(getParameterType(targetMethod, i, true),
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
        // return newArguments.toArray(new ValueNode[newArguments.size()]);
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
     * Responsible for the scalarization of the receiver after devirtualization of the method
     * happend. According to the calling convention an inline type receiver is expected to be
     * scalarized.
     *
     * @param callTargetNode the call target of whose receiver was devirtualized
     * @param oldMethod the old method before devirtualization
     * @param newMethod the method after devirtiualization
     * @param nothingScalarizedYet determines if no arguments of the old method was scalarized yet
     */
    public static void handleDevirtualizationOnCallTarget(CallTargetNode callTargetNode, ResolvedJavaMethod oldMethod, ResolvedJavaMethod newMethod, boolean nothingScalarizedYet) {
        int parameterLength = oldMethod.getSignature().getParameterCount(!oldMethod.isStatic());
        if (nothingScalarizedYet) {
            if (callTargetNode.arguments().size() != parameterLength)
                throw new GraalError("Expected actual argument size to be equal to signature parameter size" + callTargetNode.toString() + "\n" + callTargetNode.arguments() + "\n");
            // assert callTargetNode.arguments().size() == parameterLength : "Expected actual
            // argument size to be equal to signature parameter size";
        }
        boolean[] scalarizeParameters = new boolean[parameterLength];
        int argumentIndex = 0;
        for (int i = 0; i < parameterLength; i++) {
            scalarizeParameters[i] = (!oldMethod.isScalarizedParameter(i, true) || nothingScalarizedYet) && newMethod.isScalarizedParameter(i, true);
        }
        ArrayList<ValueNode> scalarizedArgs = new ArrayList<>(parameterLength);
        for (int signatureIndex = 0; signatureIndex < parameterLength; signatureIndex++) {
            if (scalarizeParameters[signatureIndex]) {
                ValueNode[] scalarized = createScalarizationCFGForInvokeArg(callTargetNode, callTargetNode.arguments().get(argumentIndex), newMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
                argumentIndex++;
            } else {
                if (oldMethod.isScalarizedParameter(signatureIndex, true) && !nothingScalarizedYet) {
                    int length = oldMethod.getScalarizedParameter(signatureIndex, true).length;
                    scalarizedArgs.addAll(callTargetNode.arguments().subList(argumentIndex, argumentIndex + length));
                    argumentIndex += length;
                } else {
                    scalarizedArgs.add(callTargetNode.arguments().get(argumentIndex));
                    argumentIndex++;
                }
            }

        }
        callTargetNode.arguments().clear();
        callTargetNode.arguments().addAll(scalarizedArgs);
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
     * @param arg the object who's field values should be loaded
     * @param targetMethod he argument's method
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
    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, LogicNode isNotNull, ResolvedJavaField[] fields) {
        return createScalarizationCFG(addBefore, object, isNotNull, fields, false, false);
    }

    /**
     *
     * Scalarizes an object into it's field values.
     *
     * @param addBefore the node before the diamond should be inserted into the graph
     * @param object the object who's field values should be loaded
     * @param isNotNull condition used as branch condition, indicating if the object is not null
     * @param fields the resolved filed
     * @param assumeObjectNonNull true if no diamond should be created
     * @param includeIsNotNullPhi true if the isNotNull information should be included in the return
     *            phis at position 1
     * @return The field values of the object
     */
    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, LogicNode isNotNull, ResolvedJavaField[] fields, boolean assumeObjectNonNull, boolean includeIsNotNullPhi) {
        StructuredGraph graph = addBefore.graph();
        if (isNotNull.isTautology() || assumeObjectNonNull) {
            assert StampTool.isPointerNonNull(object) : "expected parameter to be non-null, insert a null check";
            ValueNode[] loads = new ValueNode[fields.length + (includeIsNotNullPhi ? 1 : 0)];
            if (includeIsNotNullPhi) {
                loads[0] = ConstantNode.forByte((byte) 1, graph);
            }
            for (int i = 0; i < fields.length; i++) {
                LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), object, fields[i]));
                loads[i + (includeIsNotNullPhi ? 1 : 0)] = load;
                graph.addBeforeFixed(addBefore, load);
            }
            return loads;
        }
        if (isNotNull.isContradiction()) {
            ValueNode[] loads = new ValueNode[fields.length + (includeIsNotNullPhi ? 1 : 0)];
            if (includeIsNotNullPhi) {
                loads[0] = ConstantNode.forByte((byte) 0, graph);
            }
            for (int i = 0; i < fields.length; i++) {
                ConstantNode load = graph.addOrUnique(ConstantNode.defaultForKind(fields[i].getJavaKind()));
                loads[i + (includeIsNotNullPhi ? 1 : 0)] = load;
            }
            return loads;
        }

        BeginNode trueBegin = graph.add(new BeginNode());
        BeginNode falseBegin = graph.add(new BeginNode());

        IfNode ifNode = graph.add(new IfNode(graph.addOrUnique(isNotNull), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
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
        if (includeIsNotNullPhi) {
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
     * Can be used to scalarize the arguments of a method during parsing.
     *
     * @param callTargetNode the call target of whose arguments need to be scalarized.
     * @param method the old method before devirtualization
     *
     */
    public static void scalarizeInvokeArgs(CallTargetNode callTargetNode, ResolvedJavaMethod method) {
        handleDevirtualizationOnCallTarget(callTargetNode, method, method, true);
    }

    public static ValueNode[] scalarizeInvokeArgs(GraphBuilderContext b, ValueNode[] invokeArgs, ResolvedJavaMethod targetMethod, CallTargetNode.InvokeKind invokeKind) {
        FixedNode addBefore = null;
        ArrayList<ValueNode> scalarizedArgs = new ArrayList<>(invokeArgs.length);
        int signatureIndex = 0;
        if (targetMethod.hasReceiver()) {
            ValueNode nonNullReceiver = b.nullCheckedValue(invokeArgs[0]);
            if (targetMethod.hasScalarizedReceiver()) {
                assert invokeKind == CallTargetNode.InvokeKind.Special : "Invoke kind should be special if we know that we can scalarize the receiver";

// ForeignCallNode foreign = append(new ForeignCallNode(LOG_OBJECT, nonNullReceiver,
// ConstantNode.forBoolean(false,
// graph), ConstantNode.forBoolean(true, graph)));
                addBefore = b.add(new DummyScalarizationHandle());
                ValueNode[] scalarized = createScalarizationCFGForInvokeArg(addBefore, nonNullReceiver, targetMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
            } else {
                scalarizedArgs.add(nonNullReceiver);
            }
            signatureIndex++;
        }
        if (addBefore == null)
            addBefore = b.add(new DummyScalarizationHandle());
        for (; signatureIndex < invokeArgs.length; signatureIndex++) {
            if (targetMethod.isScalarizedParameter(signatureIndex, true)) {
                ValueNode[] scalarized = createScalarizationCFGForInvokeArg(addBefore, invokeArgs[signatureIndex], targetMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
            } else {
                scalarizedArgs.add(invokeArgs[signatureIndex]);
            }

        }
        return scalarizedArgs.toArray(new ValueNode[scalarizedArgs.size()]);
    }

    /**
     *
     *
     * @param b the context
     * @param arg the argument to be scalarized
     * @param targetMethod the argument's method
     * @param signatureIndex the argument's index in the method signature including the receiver if
     *            it exits.
     * @return the phi nodes representing the field values of the argument
     */
    private static ValueNode[] scalarizeInlineTypeArg(GraphBuilderContext b, ValueNode arg, ResolvedJavaMethod targetMethod, int signatureIndex) {
        StructuredGraph graph = b.getGraph();
        boolean nullFree = targetMethod.isParameterNullFree(signatureIndex, true);

        ResolvedJavaField[] fields = targetMethod.getScalarizedParameterFields(signatureIndex, true);

        if (nullFree) {
            // argument is null free, just directly produce loads

            ValueNode[] loads = new ValueNode[fields.length];
            for (int i = 0; i < fields.length; i++) {
                LoadFieldNode load = b.add(LoadFieldNode.create(b.getAssumptions(), arg, fields[i]));
                loads[i] = load;
            }
            return loads;
        }

        // argument can be null create diamond
        BeginNode trueBegin = b.getGraph().add(new BeginNode());
        BeginNode falseBegin = b.getGraph().add(new BeginNode());

        IfNode ifNode = b.add(new IfNode(b.add(LogicNegationNode.create(b.add(new IsNullNode(arg)))), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));

        // get a valid framestate for the merge node
        FrameState framestate = GraphUtil.findLastFrameState(ifNode);

        ValueNode[] loads = new ValueNode[fields.length];
        ValueNode[] consts = new ValueNode[fields.length];

        // true branch - inline object is non-null, load the field values

        ValueNode nonNull = PiNode.create(arg, objectNonNull(), trueBegin);
        for (int i = 0; i < fields.length; i++) {
            LoadFieldNode load = b.add(LoadFieldNode.create(b.getAssumptions(), nonNull, fields[i]));
            loads[i] = load;
            if (trueBegin.next() == null)
                trueBegin.setNext(load);
        }
        EndNode trueEnd = b.add(new EndNode());

        // check maybe needed for empty inline objects?
        if (trueBegin.next() == null)
            trueBegin.setNext(trueEnd);

        // false branch - inline object is null, use default values of fields

        for (int i = 0; i < fields.length; i++) {
            ConstantNode load = b.add(ConstantNode.defaultForKind(fields[i].getJavaKind()));
            consts[i] = load;
        }
        EndNode falseEnd = b.add(new EndNode());
        if (falseBegin.next() == null)
            falseBegin.setNext(falseEnd);

        // merge
        MergeNode merge = b.append(new MergeNode());
        merge.setStateAfter(framestate);

        // produces phi nodes
        ValuePhiNode[] phis = new ValuePhiNode[fields.length + 1];
        phis[0] = b.add(new ValuePhiNode(StampFactory.forKind(JavaKind.Byte), merge, ConstantNode.forByte((byte) 1, graph), ConstantNode.forByte((byte) 0, graph)));
        for (int i = 0; i < fields.length; i++) {
            phis[i + 1] = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), fields[i].getType(), false).getTrustedStamp(), merge, loads[i], consts[i]));
        }

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        return phis;
    }

    /**
     * This function handles the case that an {@link Invoke} returns a nullable scalarized inline
     * object. It appends multiple {@link jdk.graal.compiler.nodes.extended.ReadMultiValueNode} to
     * the invoke node and creates an {@link InlineTypeNode} which gets these nodes as input. To
     * model the concept of a nullable scalarized inline object after an invoke a
     * {@link VirtualInstanceNode} is pushed onto the framestate.
     */
    public static void handleScalarizedReturnOnInvoke(GraphBuilderContext b, Invoke invoke, JavaKind resultType) {
        InlineTypeNode result = InlineTypeNode.createFromInvoke(b, invoke);

        // create virtual object representing nullable scalarized inline object in the framestate
        VirtualObjectNode virtual = new VirtualInstanceNode(result.getType(), false);
        virtual.setObjectId(0);
        b.append(virtual);

        ValueNode[] newEntries = new ValueNode[result.getScalarizedInlineObject().size()];

        for (int i = 0; i < newEntries.length; i++) {
            ValueNode entry = result.getScalarizedInlineObject().get(i);
            newEntries[i] = entry;
        }

        // create a framestate for invoke with virtual object
        b.push(resultType, virtual);
        b.setStateAfter(invoke);
        invoke.stateAfter().addVirtualObjectMapping(b.append(new VirtualObjectState(virtual, newEntries, result.getIsNotNull())));
        b.pop(resultType);

        // push the InlineTypeNode as result
        b.push(resultType, result);
    }

    /**
     * Create as {@link LogicNode} indicating if an inline object is already allocated.
     */
    public static LogicNode createIsAlreadyBufferedCheck(StructuredGraph graph, ValueNode isNotNull, ValueNode existingOop) {
        assert isNotNull == null && existingOop == null || isNotNull != null && existingOop != null : "both should be either null or not null";
        if (isNotNull == null && existingOop == null) {
            return graph.addOrUnique(LogicConstantNode.contradiction());
        }
        LogicNode notNull = graph.addOrUnique(IntegerEqualsNode.create(isNotNull, ConstantNode.forInt(1, graph), NodeView.DEFAULT));
        LogicNode oopIsNull = graph.addOrUnique(IsNullNode.create(existingOop));
        LogicNode check = graph.addOrUniqueWithInputs(
                        LogicNegationNode.create(LogicNode.and(notNull, oopIsNull, ProfileData.BranchProbabilityData.unknown())));
        return check;

    }

    /**
     * Determines if it is known at compile time if a scalarized inline object is already buffered.
     * E.g. this can be the case if the inline object is constant null.
     */
    public static TriState isAlreadyBuffered(StructuredGraph graph, ValueNode isNotNull, ValueNode existingOop) {
        if (createIsAlreadyBufferedCheck(graph, isNotNull, existingOop).isTautology()) {
            return TriState.TRUE;
        }
        return TriState.UNKNOWN;
    }

    /**
     *
     * Creates a graph diamond in order to perform the materialization of an inline type. One branch
     * just reuses the existing oop and the other branch allocates the instance.
     *
     *
     * @param addBefore the node before the diamond should be inserted into the graph
     * @param isNotNull node indicating if the inline object is null or not
     * @param existingOop represents either an inline object or null at runtime
     * @param writes write operations that should be performed on the allocation branch
     * @param addMembar true if a membar should be inserted on the allocation branch
     * @param newInstanceNode does the allocation on the allocation branch
     * @param type the type of the inline object
     * @return the phi node of the diamond representing the inline object
     */
    public static ValueNode createAllocationDiamond(FixedNode addBefore, ValueNode isNotNull, ValueNode existingOop, List<WriteNode> writes, boolean addMembar, NewInstanceNode newInstanceNode,
                    ResolvedJavaType type) {
        StructuredGraph graph = addBefore.graph();


        LogicNode isAlreadyBuffered = createIsAlreadyBufferedCheck(graph, isNotNull, existingOop);

        assert !isAlreadyBuffered.isTautology() : "should have been checked for tautology before";
        assert newInstanceNode != null && newInstanceNode.isAlive() : "NewInstanceNode should be alive";

        if (newInstanceNode == null) {
            assert type != null : "type for lowering inline type expected";
            newInstanceNode = graph.add(new NewInstanceNode(type, true));
        }
        // addWrites(graph, writes);

        if (isAlreadyBuffered.isContradiction()) {
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
        IfNode ifNode = graph.add(new IfNode(isAlreadyBuffered, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        ((FixedWithNextNode) addBefore.predecessor()).setNext(ifNode);

        // true branch - inline object is already buffered (oop or null) or is null

        EndNode trueEnd = graph.add(new EndNode());
        trueBegin.setNext(trueEnd);

        // false branch - inline object is not buffered

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
                        existingOop, newInstanceNode));
        return phi;

    }

    public static void insertLateInitWrites(FixedNode addBefore, ValueNode isNotNull, ValueNode existingOop, List<WriteNode> writes) {
        StructuredGraph graph = addBefore.graph();

        LogicNode isAlreadyBuffered = createIsAlreadyBufferedCheck(graph, isNotNull, existingOop);
        assert !isAlreadyBuffered.isTautology() : "should have been checked for tautology before";
// if (isAlreadyBuffered.isTautology())
// return;

        // addWrites(graph, writes);

        if (isAlreadyBuffered.isContradiction()) {
            for (WriteNode w : writes) {
                assert w != null && w.isAlive() : "WriteNode should be alive";
                graph.addBeforeFixed(addBefore, w);
            }
            return;
        }

        FrameState framestate = GraphUtil.findLastFrameState(addBefore);

        BeginNode trueBegin = graph.add(new BeginNode());
        BeginNode falseBegin = graph.add(new BeginNode());

        IfNode ifNode = graph.add(new IfNode(isAlreadyBuffered, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        ((FixedWithNextNode) addBefore.predecessor()).setNext(ifNode);

        // true branch - inline object is already buffered (oop or null) or is null

        EndNode trueEnd = graph.add(new EndNode());
        trueBegin.setNext(trueEnd);

        // false branch - inline object is not buffered

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

    private static void addWrites(StructuredGraph graph, List<WriteNode> writes) {
        for (WriteNode w : writes) {
            if (!w.isAlive())
                graph.add(w);
        }
    }

    public static class InlineTypeInfo {
        public InlineTypeInfo(ValueNode isNotNull, ValueNode existingOop) {
            this.isNotNull = isNotNull;
            this.existingOop = existingOop;
        }

        private ValueNode isNotNull;
        private ValueNode existingOop;
        private List<WriteNode> writes = new ArrayList<>();

        public ValueNode getIsNotNull() {
            return isNotNull;
        }

        public ValueNode getExistingOop() {
            return existingOop;
        }

        public List<WriteNode> getWrites() {
            return writes;
        }
    }

    @NodeInfo(cycles = CYCLES_IGNORED, size = SIZE_IGNORED)
    static final class DummyScalarizationHandle extends FixedWithNextNode implements LIRLowerable, Canonicalizable {
        public static final NodeClass<DummyScalarizationHandle> TYPE = NodeClass.create(DummyScalarizationHandle.class);

        public DummyScalarizationHandle() {
            super(TYPE, StampFactory.forVoid());
        }

        @Override
        public Node canonical(CanonicalizerTool tool) {
            return null;
        }

        @Override
        public void generate(NodeLIRBuilderTool generator) {

        }
    }

    public static boolean needsSubstitutabilityCheck(ValueNode x, ValueNode y, ValhallaOptionsProvider valhallaOptionsProvider) {
        return StampTool.canBeInlineType(x.stamp(NodeView.DEFAULT), valhallaOptionsProvider) && StampTool.canBeInlineType(y.stamp(NodeView.DEFAULT), valhallaOptionsProvider);
    }
}
