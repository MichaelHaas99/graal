package jdk.graal.compiler.nodes.util;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.word.LocationIdentity;

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
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
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
                ValueNode[] scalarized = createScalarizatoinCFGForInvokeArg(callTargetNode, callTargetNode.arguments().get(argumentIndex), newMethod, signatureIndex);
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

    private static ValueNode[] createScalarizatoinCFGForInvokeArg(CallTargetNode callTargetNode, ValueNode arg, ResolvedJavaMethod targetMethod, int signatureIndex) {
        StructuredGraph graph = callTargetNode.graph();

        return createScalarizationCFG(callTargetNode.invoke().asFixedNode(), arg,
                        graph.addOrUnique(LogicNegationNode.create(graph.addOrUnique(IsNullNode.create(arg)))),
                        targetMethod.getScalarizedParameterFields(signatureIndex, true), targetMethod.isParameterNullFree(signatureIndex, true), true);
//
// StructuredGraph graph = callTargetNode.graph();
// boolean nullFree = targetMethod.isParameterNullFree(signatureIndex, true);
// ResolvedJavaField[] fields = targetMethod.getScalarizedParameterFields(signatureIndex, true);
// if (nullFree) {
// ValueNode[] loads = new ValueNode[fields.length];
// for (int i = 0; i < fields.length; i++) {
// LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), arg, fields[i]));
// loads[i] = load;
// graph.addBeforeFixed(callTargetNode.invoke().asFixedNode(), load);
// }
// return loads;
// }
//
// BeginNode trueBegin = graph.add(new BeginNode());
// BeginNode falseBegin = graph.add(new BeginNode());
//
// IfNode ifNode = graph.add(new
// IfNode(graph.addOrUnique(LogicNegationNode.create(graph.addOrUnique(new IsNullNode(arg)))),
// trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
// ((FixedWithNextNode) callTargetNode.invoke().asFixedNode().predecessor()).setNext(ifNode);
//
// // get a valid framestate for the merge node
// FrameState framestate = GraphUtil.findLastFrameState(ifNode);
//
// ValueNode[] loads = new ValueNode[fields.length];
// ValueNode[] consts = new ValueNode[fields.length];
//
// // true branch - inline object is non-null, load the field values
//
// ValueNode nonNull = PiNode.create(arg, objectNonNull(), trueBegin);
// FixedWithNextNode previous = trueBegin;
// for (int i = 0; i < fields.length; i++) {
// LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), nonNull, fields[i]));
// loads[i] = load;
// previous.setNext(load);
// previous = load;
// }
// EndNode trueEnd = graph.add(new EndNode());
// previous.setNext(trueEnd);
//
// // false branch - inline object is null, use default values of fields
//
// for (int i = 0; i < fields.length; i++) {
// ConstantNode load = graph.addOrUnique(ConstantNode.defaultForKind(fields[i].getJavaKind()));
// consts[i] = load;
// }
// EndNode falseEnd = graph.add(new EndNode());
// if (falseBegin.next() == null)
// falseBegin.setNext(falseEnd);
//
// // merge
// MergeNode merge = graph.add(new MergeNode());
// merge.setStateAfter(framestate);
//
// // produces phi nodes
// ValuePhiNode[] phis = new ValuePhiNode[fields.length + 1];
// phis[0] = graph.addOrUnique(new ValuePhiNode(StampFactory.forKind(JavaKind.Byte), merge,
// ConstantNode.forByte((byte) 1, graph), ConstantNode.forByte((byte) 0, graph)));
// for (int i = 0; i < fields.length; i++) {
// phis[i + 1] = graph.addOrUnique(new
// ValuePhiNode(StampFactory.forDeclaredType(graph.getAssumptions(), fields[i].getType(),
// false).getTrustedStamp(), merge, loads[i], consts[i]));
// }
//
// merge.addForwardEnd(trueEnd);
// merge.addForwardEnd(falseEnd);
// merge.setNext(callTargetNode.invoke().asFixedNode());
// return phis;
    }

    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, LogicNode isNotNull, ResolvedJavaField[] fields) {
        return createScalarizationCFG(addBefore, object, isNotNull, fields, false, false);
    }

    public static ValueNode[] createScalarizationCFG(FixedNode addBefore, ValueNode object, LogicNode isNotNull, ResolvedJavaField[] fields, boolean assumeObjectNonNull, boolean includeIsNotNullPhi) {
        StructuredGraph graph = addBefore.graph();
        if (isNotNull.isTautology() || assumeObjectNonNull) {
            ValueNode[] loads = new ValueNode[fields.length];
            for (int i = 0; i < fields.length; i++) {
                LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), object, fields[i]));
                loads[i] = load;
                graph.addBeforeFixed(addBefore, load);
            }
            return loads;
        }
        if (isNotNull.isContradiction()) {
            ValueNode[] loads = new ValueNode[fields.length];
            for (int i = 0; i < fields.length; i++) {
                ConstantNode load = graph.addOrUnique(ConstantNode.defaultForKind(fields[i].getJavaKind()));
                loads[i] = load;
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

    public static ValueNode[] scalarizeInvokeArgs(GraphBuilderContext b, ValueNode[] invokeArgs, ResolvedJavaMethod targetMethod, CallTargetNode.InvokeKind invokeKind) {
        ArrayList<ValueNode> scalarizedArgs = new ArrayList<>(invokeArgs.length);
        int signatureIndex = 0;
        if (targetMethod.hasReceiver()) {
            ValueNode nonNullReceiver = b.nullCheckedValue(invokeArgs[0]);
            if (targetMethod.hasScalarizedReceiver()) {
                assert invokeKind == CallTargetNode.InvokeKind.Special : "Invoke kind should be special if we know that we can scalarize the receiver";

// ForeignCallNode foreign = append(new ForeignCallNode(LOG_OBJECT, nonNullReceiver,
// ConstantNode.forBoolean(false,
// graph), ConstantNode.forBoolean(true, graph)));
                ValueNode[] scalarized = scalarizeInlineTypeArg(b, nonNullReceiver, targetMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
            } else {
                scalarizedArgs.add(nonNullReceiver);
            }
            signatureIndex++;
        }
        for (; signatureIndex < invokeArgs.length; signatureIndex++) {
            if (targetMethod.isScalarizedParameter(signatureIndex, true)) {
                ValueNode[] scalarized = scalarizeInlineTypeArg(b, invokeArgs[signatureIndex], targetMethod, signatureIndex);
                scalarizedArgs.addAll(List.of(scalarized));
            } else {
                scalarizedArgs.add(invokeArgs[signatureIndex]);
            }

        }
        return scalarizedArgs.toArray(new ValueNode[scalarizedArgs.size()]);
    }

    private static ValueNode[] scalarizeInlineTypeArg(GraphBuilderContext b, ValueNode arg, ResolvedJavaMethod targetMethod, int signatureIndex) {
        StructuredGraph graph = b.getGraph();
        boolean nullFree = targetMethod.isParameterNullFree(signatureIndex, true);

        ResolvedJavaField[] fields = targetMethod.getScalarizedParameterFields(signatureIndex, true);
        if (nullFree) {
            ValueNode[] loads = new ValueNode[fields.length];
            for (int i = 0; i < fields.length; i++) {
                LoadFieldNode load = b.add(LoadFieldNode.create(b.getAssumptions(), arg, fields[i]));
                loads[i] = load;
            }
            return loads;
        }

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

    public static void handleScalarizedReturnOnInvoke(GraphBuilderContext b, Invoke invoke, JavaKind resultType) {
        InlineTypeNode result = InlineTypeNode.createFromInvoke(b, invoke);

        // create virtual object representing nullable scalarized inline object in framestate
        VirtualObjectNode virtual = new VirtualInstanceNode(result.getType(), false);
        virtual.setObjectId(0);
        b.append(virtual);

        ValueNode[] newEntries = new ValueNode[result.getScalarizedInlineObject().size()];

        for (int i = 0; i < newEntries.length; i++) {
            ValueNode entry = result.getScalarizedInlineObject().get(i);
            newEntries[i] = entry;
        }

        // create framestate for invoke with virtual object
        b.push(resultType, virtual);
        b.setStateAfter(invoke);
        invoke.stateAfter().addVirtualObjectMapping(b.append(new VirtualObjectState(virtual, newEntries, result.getIsNotNull())));
        b.pop(resultType);

        // push the InlineTypeNode as result
        b.push(resultType, result);
    }

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

    public static TriState isAlreadyBuffered(StructuredGraph graph, ValueNode isNotNull, ValueNode existingOop) {
        if (createIsAlreadyBufferedCheck(graph, isNotNull, existingOop).isTautology()) {
            return TriState.TRUE;
        }
        return TriState.UNKNOWN;
    }

    public static ValueNode insertLoweredGraph(FixedNode addBefore, ValueNode isNotNull, ValueNode existingOop, List<WriteNode> writes, boolean addMembar, NewInstanceNode newInstanceNode,
                    ResolvedJavaType type) {
        StructuredGraph graph = addBefore.graph();
        assert newInstanceNode != null && newInstanceNode.isAlive() : "NewInstanceNode should be alive";

        LogicNode isAlreadyBuffered = createIsAlreadyBufferedCheck(graph, isNotNull, existingOop);

        assert !isAlreadyBuffered.isTautology() : "should have been checked for tautology before";

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
                MembarNode memBar = graph.add(new MembarNode(MembarNode.FenceKind.CONSTRUCTOR_FREEZE, LocationIdentity.init()));
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
            MembarNode memBar = graph.add(new MembarNode(MembarNode.FenceKind.CONSTRUCTOR_FREEZE, LocationIdentity.init()));
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
}
