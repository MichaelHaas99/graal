package jdk.graal.compiler.nodes.util;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class InlineTypeUtil {
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

}
