package jdk.graal.compiler.nodes.util;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.GraphState;
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
import jdk.graal.compiler.nodes.extended.PublishWritesNode;
import jdk.graal.compiler.nodes.extended.ScalarizedReturnHandlerNode;
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
import jdk.graal.compiler.replacements.MethodHandlePlugin;
import jdk.graal.compiler.replacements.nodes.ResolvedMethodHandleCallTargetNode;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.UnresolvedJavaType;

/**
 * Contains utility functions often needed in conjunction with inline types.
 */
public class InlineTypeUtil {

    static Class<? extends Throwable> identityExceptionClass;

    private static boolean identityExceptionClassAvailable;

    static {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Throwable> temp = (Class<? extends Throwable>) Class.forName("java.lang.IdentityException");
            identityExceptionClass = temp;
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
            if (GraalValhallaServices.isScalarizedParameter(targetMethod, i, true)) {
                int scalarizedParametersLen = GraalValhallaServices.getScalarizedParameter(targetMethod, i, true).size();
                InlineTypeNode inlineTypeNode;
                if (GraalValhallaServices.isParameterNullFree(targetMethod, i, true)) {
                    inlineTypeNode = InlineTypeNode.createNonNullWithoutOop(getParameterType(targetMethod, i, true),
                                    arguments.subList(currentIndex, scalarizedParametersLen).toArray(new ValueNode[parameterLength]));
                } else {
                    inlineTypeNode = InlineTypeNode.createWithoutOop(getParameterType(targetMethod, i, true),
                                    arguments.subList(currentIndex + 1, scalarizedParametersLen).toArray(new ValueNode[parameterLength - 1]), arguments.get(parameterLength));
                }
                graph.addOrUniqueWithInputs(inlineTypeNode);
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

    @Deprecated
    private static ResolvedJavaType getParameterType(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        boolean includeReceiver = indexIncludesReceiverIfExists && !method.isStatic();
        int newIndex = index;
        if (includeReceiver) {
            if (index == 0) {
                return method.getDeclaringClass();
            } else {
                newIndex--;
            }
        }
        return method.getSignature().getParameterType(newIndex, method.getDeclaringClass()).resolve(method.getDeclaringClass());
    }

    /**
     * Responsible for the scalarization of the receiver after devirtualization happened. According
     * to the calling convention an inline type receiver is expected to be scalarized.
     *
     * @param callTargetNode the call target of whose receiver was devirtualized
     * @param oldMethod the old method before devirtualization
     * @param newMethod the method after devirtiualization
     * @param expectNothingScalarizedYet determines if no arguments of the old method were
     *            scalarized yet
     */
    public static void handleDevirtualizationOnCallTarget(MethodCallTargetNode callTargetNode, ResolvedJavaMethod oldMethod, ResolvedJavaMethod newMethod, boolean expectNothingScalarizedYet) {
        if (GraalValhallaServices.hasScalarizedParameters(oldMethod) && !GraalValhallaServices.hasCallingConventionMismatch(oldMethod) &&
                        !GraalValhallaServices.hasScalarizedParameters(newMethod)) {
            throw new GraalError("method parameters scalarization mismatch between" + oldMethod + " and " + newMethod);
        }
        if (!GraalValhallaServices.hasScalarizedParameters(newMethod) || oldMethod.equals(newMethod) && GraalValhallaServices.hasCallingConventionMismatch(oldMethod) ||
                        callTargetNode instanceof ResolvedMethodHandleCallTargetNode) {
            return;
        }

        boolean nothingScalarizedYet = expectNothingScalarizedYet | GraalValhallaServices.hasCallingConventionMismatch(oldMethod);

        StructuredGraph graph = callTargetNode.graph();
        int parameterLength = oldMethod.getSignature().getParameterCount(!oldMethod.isStatic());
        if (nothingScalarizedYet) {
            if (callTargetNode.arguments().size() != parameterLength)
                throw new GraalError("Expected actual argument size to be equal to signature parameter size" + callTargetNode.toString() + "\n" + callTargetNode.arguments() + "\n");
        }

        List<ValueNode> arguments;
        if (graph.getGraphState().isAfterStage(GraphState.StageFlag.VALHALLA_CALLING_CONVENTION)) {
            // directly operate on the call target arguments
            assert callTargetNode.getScalarizedArguments().isEmpty() : "should be empty after Valhalla Calling Convention phase";
            arguments = callTargetNode.arguments();
        } else {
            // safe the arguments in an extra list
            if (callTargetNode.getScalarizedArguments().isEmpty()) {
                callTargetNode.getScalarizedArguments().addAll(callTargetNode.arguments());
            }
            arguments = callTargetNode.getScalarizedArguments();

        }

        boolean[] scalarizeParameters = new boolean[parameterLength];
        int argumentIndex = 0;
        for (int i = 0; i < parameterLength; i++) {
            scalarizeParameters[i] = (!GraalValhallaServices.isScalarizedParameter(oldMethod, i, true) || nothingScalarizedYet) &&
                            GraalValhallaServices.isScalarizedParameter(newMethod, i, true);
        }
        ArrayList<ValueNode> scalarizedArgs = new ArrayList<>(parameterLength);
        for (int signatureIndex = 0; signatureIndex < parameterLength; signatureIndex++) {
            if (scalarizeParameters[signatureIndex]) {
                ValueNode[] scalarized = createScalarizationCFGForInvokeArg(callTargetNode, arguments.get(argumentIndex), newMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
                argumentIndex++;
            } else {
                if (GraalValhallaServices.isScalarizedParameter(oldMethod, signatureIndex, true) && !nothingScalarizedYet) {
                    int length = GraalValhallaServices.getScalarizedParameter(oldMethod, signatureIndex, true).size();
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
        boolean isNullFree = GraalValhallaServices.isParameterNullFree(targetMethod, signatureIndex, true);

        if (GraphUtil.unproxify(arg) instanceof InlineTypeNode inlineTypeNode && inlineTypeNode.canBeUsedInCanonicalization()) {
            List<ValueNode> list = new ArrayList<>(inlineTypeNode.getEntries());
            if (!isNullFree) {
                ValueNode nonNull = inlineTypeNode.getNonNull();
                if (StampTool.isPointerNonNull(arg)) {
                    nonNull = ConstantNode.forInt(1, inlineTypeNode.graph());
                }
                list.addFirst(nonNull);
            }
            return list.toArray(new ValueNode[list.size()]);
        }
        return createScalarizationCFG(addBefore, arg,
                        GraalValhallaServices.getScalarizedParameterFields(targetMethod, signatureIndex, true), isNullFree, !isNullFree);
    }

    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, List<ResolvedJavaField> fields, boolean assumeObjectNonNull,
                    boolean includeNonNullPhi) {
        return createScalarizationCFG(addBefore, object, fields, assumeObjectNonNull, includeNonNullPhi, ScalarizationNodes.SHOULD_CREATE);
    }

    public static ValueNode[] createScalarizationCFGReversed(FixedNode addBefore, ValueNode object, List<ResolvedJavaField> fields, boolean assumeObjectNonNull,
                    boolean includeNonNullPhi) {
        return createScalarizationCFG(addBefore, object, fields, assumeObjectNonNull, includeNonNullPhi, ScalarizationNodes.SHOULD_CREATE, true);
    }

    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, List<ResolvedJavaField> fields, boolean assumeObjectNonNull,
                    boolean includeNonNullPhi, ScalarizationNodes scalarizationNodes) {
        return createScalarizationCFG(addBefore, object, fields, assumeObjectNonNull, includeNonNullPhi, scalarizationNodes, false);
    }

    public static class ScalarizationNodes {
        ValuePhiNode[] phis;
        LoadFieldNode[] nonNullValues;
        ConstantNode[] nullValues;
        MergeNode mergeNode;

        public ScalarizationNodes(ConstantNode[] nullValues, LoadFieldNode[] nonNullValues, ValuePhiNode[] phis, MergeNode mergeNode) {
            this.nullValues = nullValues;
            this.nonNullValues = nonNullValues;
            this.phis = phis;
            this.mergeNode = mergeNode;
        }

        static final ScalarizationNodes SHOULD_CREATE = new ScalarizationNodes(null, null, null, null);

        public static ScalarizationNodes alreadyCreated(ConstantNode[] nullValues, LoadFieldNode[] nonNullValues, ValuePhiNode[] phis, MergeNode mergeNode) {
            return new ScalarizationNodes(nullValues, nonNullValues, phis, mergeNode);
        }
    }

    /**
     * Scalarizes an object into it's field values. In case the object is nullable a diamond is
     * which uses default values on the null branch.
     *
     * @param addBefore the node before the diamond should be inserted into the graph
     * @param object the object whose field values should be loaded
     * @param fields the resolved filed
     * @param assumeObjectNonNull true if no diamond should be created, because the default values
     *            are not valid
     * @param includeNonNullPhi true if the non-null information should be included in the returned
     *            phis at position zero
     * @return The field values of the object
     */
    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, List<ResolvedJavaField> fields, boolean assumeObjectNonNull,
                    boolean includeNonNullPhi, ScalarizationNodes scalarizationNodes, boolean appendReverse) {
        StructuredGraph graph = addBefore.graph();
        LogicNode nonNull = graph.addOrUniqueWithInputs(LogicNegationNode.create(IsNullNode.create(object)));
        ValuePhiNode[] phis = scalarizationNodes.phis;
        if (phis == null) {
            if (assumeObjectNonNull || nonNull.isTautology()) {
                assert StampTool.isPointerNonNull(object) : "no diamond should be created, insert a null check";
                ValueNode[] loads = new ValueNode[fields.size() + (includeNonNullPhi ? 1 : 0)];
                if (includeNonNullPhi) {
                    loads[0] = ConstantNode.forInt(1, graph);
                }
                if (!appendReverse) {
                    for (int i = 0; i < fields.size(); i++) {
                        LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), object, fields.get(i)));
                        loads[i + (includeNonNullPhi ? 1 : 0)] = load;
                        graph.addBeforeFixed(addBefore, load);
                    }
                } else {
                    for (int ri = fields.size() - 1; ri >= 0; ri--) {
                        LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), object, fields.get(ri)));
                        loads[ri + (includeNonNullPhi ? 1 : 0)] = load;
                        graph.addBeforeFixed(addBefore, load);
                    }
                    loads = reverseArray(loads);
                }
                return loads;
            }
            if (nonNull.isContradiction()) {
                ValueNode[] loads = new ValueNode[fields.size() + (includeNonNullPhi ? 1 : 0)];
                if (includeNonNullPhi) {
                    loads[0] = ConstantNode.forInt(0, graph);
                }
                for (int i = 0; i < fields.size(); i++) {
                    ConstantNode load = graph.addOrUnique(ConstantNode.defaultForKind(fields.get(i).getJavaKind()));
                    loads[i + (includeNonNullPhi ? 1 : 0)] = load;
                }
                if (appendReverse) {
                    loads = reverseArray(loads);
                }
                return loads;
            }
        }

        BeginNode trueBegin = graph.add(new BeginNode());
        BeginNode falseBegin = graph.add(new BeginNode());

        IfNode ifNode = graph.add(new IfNode(graph.addOrUnique(nonNull), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
        ((FixedWithNextNode) addBefore.predecessor()).setNext(ifNode);

        // get a valid framestate for the merge node
        FrameState framestate = GraphUtil.findLastFrameState(ifNode);

        ValueNode[] loads = new ValueNode[fields.size()];
        ValueNode[] consts = new ValueNode[fields.size()];

        // true branch - inline object is non-null, load the field values

        ValueNode nonNullObject = graph.addOrUnique(PiNode.create(object, objectNonNull(), trueBegin));
        FixedWithNextNode previous = trueBegin;
        LoadFieldNode[] nonNullValues = scalarizationNodes.nonNullValues;
        if (!appendReverse) {
            for (int i = 0; i < fields.size(); i++) {
                LoadFieldNode load;
                if (nonNullValues == null) {
                    load = graph.add(LoadFieldNode.create(graph.getAssumptions(), nonNullObject, fields.get(i)));
                } else {
                    load = graph.add(nonNullValues[i]);
                    load.setObject(nonNullObject);
                }

                loads[i] = load;
                previous.setNext(load);
                previous = load;
            }
        } else {
            for (int ri = fields.size() - 1; ri >= 0; ri--) {
                LoadFieldNode load;
                if (nonNullValues == null) {
                    load = graph.add(LoadFieldNode.create(graph.getAssumptions(), nonNullObject, fields.get(ri)));
                } else {
                    load = graph.add(nonNullValues[ri]);
                    load.setObject(nonNullObject);
                }
                loads[ri] = load;
                previous.setNext(load);
                previous = load;
            }
        }
        EndNode trueEnd = graph.add(new EndNode());
        previous.setNext(trueEnd);

        // false branch - inline object is null, use default values of fields

        ConstantNode[] nullValues = scalarizationNodes.nullValues;
        if (!appendReverse) {
            for (int i = 0; i < fields.size(); i++) {
                ConstantNode load;
                if (nullValues == null) {
                    load = graph.addOrUnique(ConstantNode.defaultForKind(fields.get(i).getJavaKind()));
                } else {
                    load = graph.addWithoutUnique(nullValues[i]);
                }
                consts[i] = load;
            }
        } else {
            // create const values in reverse (but place into consts array by their index)
            for (int ri = fields.size() - 1; ri >= 0; ri--) {
                ConstantNode load;
                if (nullValues == null) {
                    load = graph.addOrUnique(ConstantNode.defaultForKind(fields.get(ri).getJavaKind()));
                } else {
                    load = graph.addWithoutUnique(nullValues[ri]);
                }
                consts[ri] = load;
            }
        }
        EndNode falseEnd = graph.add(new EndNode());
        if (falseBegin.next() == null)
            falseBegin.setNext(falseEnd);

        // merge
        MergeNode mergeNode = scalarizationNodes.mergeNode;
        MergeNode merge = graph.add(mergeNode == null ? new MergeNode() : mergeNode);
        merge.setStateAfter(framestate);

        // produces phi nodes
        ValuePhiNode[] newPhis = phis;
        if (newPhis == null) {
            newPhis = new ValuePhiNode[fields.size() + (includeNonNullPhi ? 1 : 0)];
            if (includeNonNullPhi) {
                newPhis[0] = graph.addOrUnique(new ValuePhiNode(StampFactory.forKind(JavaKind.Int), merge, ConstantNode.forInt(1, graph), ConstantNode.forInt(0, graph)));
            }
            for (int i = 0; i < fields.size(); i++) {
                newPhis[i + (includeNonNullPhi ? 1 : 0)] = graph.addOrUnique(
                                new ValuePhiNode(StampFactory.forDeclaredType(graph.getAssumptions(), fields.get(i).getType(), false).getTrustedStamp(), merge, loads[i], consts[i]));
            }
        } else {
            // phi inputs set in after merge effects
        }

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        merge.setNext(addBefore);

        if (appendReverse) {
            // reverse the returned phis array as requested
            newPhis = reverseArray(newPhis);
        }

        return newPhis;
    }

    private static <T> T[] reverseArray(T[] arr) {
        return List.of(arr).reversed().toArray(arr);
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

        ValueNode[] newEntries = new ValueNode[result.getEntries().size()];

        for (int i = 0; i < newEntries.length; i++) {
            ValueNode entry = result.getEntries().get(i);
            newEntries[i] = entry;
        }

        // create a framestate for invoke with virtual object
        b.pop(JavaKind.Object);
        b.push(resultType, virtual);
        b.setStateAfter(invoke);
        invoke.stateAfter().addVirtualObjectMapping(b.append(new VirtualObjectState(virtual, newEntries, result.getNonNull())));
        b.pop(resultType);

        // push the InlineTypeNode as result
        b.push(resultType, result);
    }

    public static void handleUnresolvedReturnType(GraphBuilderContext b, Invoke invoke) {
        b.setStateAfter(invoke);
        b.pop(JavaKind.Object);
        ScalarizedReturnHandlerNode handlerNode = new ScalarizedReturnHandlerNode(invoke.asNode(), invoke.asNode().stamp(NodeView.DEFAULT));
        handlerNode.setBci(invoke.bci());
        b.append(handlerNode);
        b.push(JavaKind.Object, handlerNode);
        b.setStateAfter(handlerNode);
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

    public static boolean isAllocatedOrNull(StructuredGraph graph, ValueNode nonNull, ValueNode oop) {
        return isAllocatedOrNull(graph, nonNull, oop, false);
    }

    /**
     * Determines if it is known at compile time if a scalarized inline object is already allocated
     * or null. E.g. this can be the case if the inline object is constant null or was made virtual
     * again during parsing.
     */
    public static boolean isAllocatedOrNull(StructuredGraph graph, ValueNode nonNull, ValueNode oop, boolean isAllocatedOrNull) {
        if (isAllocatedOrNull) {
            return true;
        }
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

        if (isAllocatedOrNull.isContradiction()) {
            graph.addBeforeFixed(addBefore, newInstanceNode);
            for (WriteNode w : writes) {
                assert w != null && w.isAlive() : "WriteNode should be alive";
                graph.addBeforeFixed(addBefore, w);
            }
            PublishWritesNode anchor = graph.add(new PublishWritesNode(newInstanceNode));
            graph.addBeforeFixed(addBefore, anchor);
            if (addMembar) {
                // all fields implicitly final therefore use constructor freeze
                MembarNode memBar = graph.add(MembarNode.forInitialization());
                graph.addBeforeFixed(addBefore, memBar);
            }
            return anchor;
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
        PublishWritesNode anchor = graph.add(new PublishWritesNode(newInstanceNode));
        previous.setNext(anchor);
        anchor.setNext(falseEnd);
        previous = anchor;
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
                        oop, anchor));
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

    public static boolean mayNeedSubstitutabilityCheck(ValueNode x, ValueNode y, ValhallaOptionsProvider valhallaOptionsProvider) {
        return StampTool.canBeInlineType(x, valhallaOptionsProvider) && StampTool.canBeInlineType(y, valhallaOptionsProvider);
    }

    /**
     * Checks whether we can run into a circle when we try to recursively scalarize an inline
     * object.
     * 
     * @param type the inline type
     * @return true if a circle is possible
     */
    public static boolean isCircularInlineType(ResolvedJavaType type) {
        return circularInlineTypeTest(type).isCircular;
    }

    public static CircularTestResult circularInlineTypeTest(ResolvedJavaType type) {
        CircularTestResult result = circularTestCache.get(type);
        if (result == null) {
            result = isCircularInlineType(type, EconomicSet.create(Equivalence.DEFAULT));
            circularTestCache.put(type, result);
        }
        return result;
    }

    public record CircularTestResult(boolean isCircular, int depth) {
        public CircularTestResult(int depth) {
            this(true, depth);
        }
    }

    private static final CircularTestResult NON_CIRCULAR = new CircularTestResult(false, Integer.MAX_VALUE);
    private static final EconomicMap<ResolvedJavaType, CircularTestResult> circularTestCache = EconomicMap.create(Equivalence.IDENTITY);

    // TODO: use assumptions in combination with a TypeReference
    private static CircularTestResult isCircularInlineType(ResolvedJavaType type, EconomicSet<ResolvedJavaType> visitedTypes) {
        int depth = -1;
        if (GraalValhallaServices.isIdentity(type) || type.isPrimitive()) {
            return NON_CIRCULAR;
        }
        Queue<ResolvedJavaType> queue = new ArrayDeque<>();
        Queue<Integer> counters = new ArrayDeque<>();
        queue.add(type);
        EconomicSet<ResolvedJavaType> newVisited = EconomicSet.create(Equivalence.DEFAULT);
        int counter = 1;
        while (!queue.isEmpty()) {
            ResolvedJavaType t = queue.remove();

            if (t.isPrimitive()) {
                // not interested in primitives
                continue;
            }

            if (visitedTypes.contains(t)) {
                // type was already visited
                return new CircularTestResult(depth);
            }

            if (!type.equals(t)) {
                // object type
                if ((t.isInterface() || !GraalValhallaServices.isIdentity(t) && t.isAbstract()) || t.isJavaLangObject()) {
                    // inline type could be assignable to type, but we can't analyze its fields at
                    // compile time
                    return new CircularTestResult(depth);
                }
                if (GraalValhallaServices.isIdentity(t)) {
                    // not interested in non-inline types
                    continue;
                }
            }

            counter--;
            if (counter == 0) {
                newVisited.add(t);
                visitedTypes.addAll(newVisited);
                newVisited.clear();
            } else {
                newVisited.add(t);
            }

            ResolvedJavaField[] fields = t.getInstanceFields(true);
            counters.add(fields.length);

            if (counter == 0) {
                depth++;
                counter = counters.remove();
            }
            for (ResolvedJavaField field : fields) {
                JavaType fieldType = field.getType();
                if (fieldType instanceof UnresolvedJavaType unresolvedJavaType && unresolvedJavaType.getJavaKind() == JavaKind.Object) {
                    return new CircularTestResult(depth);
                }
                assert fieldType instanceof ResolvedJavaType : "Expected field type to be resolved";
                queue.add((ResolvedJavaType) fieldType);
            }

        }
        return NON_CIRCULAR;

    }

    public static boolean foreignCallAllocatesInlineType(ForeignCallLinkage foreignCall) {
        return foreignCallAllocatesInlineType(foreignCall.getDescriptor());
    }

    public static boolean foreignCallAllocatesInlineType(Node nodeAfterInvoke) {
        return nodeAfterInvoke instanceof ScalarizedReturnHandlerNode;
    }

    private static boolean foreignCallAllocatesInlineType(ForeignCallDescriptor foreignCall) {
        return foreignCall.getSignature().getName().contains(MethodHandlePlugin.STORE_INLINE_TYPE_FIELDS_TO.getName());
    }
}
