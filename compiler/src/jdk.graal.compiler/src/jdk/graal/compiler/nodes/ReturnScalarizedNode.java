package jdk.graal.compiler.nodes;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.ReturnResultDeciderNode;
import jdk.graal.compiler.nodes.extended.TagHubNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

/**
 * The {@link ReturnScalarizedNode} represents a return of a nullable scalarized inline object. see
 * Compile::return_values in parse.cpp of the C2 compiler. In case the scalarized inline object is
 * not null, either an existing oop is placed into the first register or the tagged hub. In case the
 * scalarized inline object is null, a null pointer is placed into the first register.
 */
@NodeInfo(nameTemplate = "ReturnScalarized")
public class ReturnScalarizedNode extends ReturnNode implements Virtualizable {
    public static final NodeClass<ReturnScalarizedNode> TYPE = NodeClass.create(ReturnScalarizedNode.class);


    @OptionalInput private NodeInputList<ValueNode> scalarizedInlineObject;

    public ReturnScalarizedNode(ValueNode result, List<ValueNode> scalarizedInlineObject) {
        super(TYPE, result);
        this.scalarizedInlineObject = new NodeInputList<>(this, scalarizedInlineObject);
    }

    public void setResult(ValueNode newResult) {
        updateUsages(this.result, newResult);
        this.result = newResult;
    }

    public ValueNode getField(int index) {
        return scalarizedInlineObject.get(index);
    }

    public static ReturnNode create(GraphBuilderContext b, ValueNode result, ResolvedJavaType type) {
        ResolvedJavaField[] fields = type.getInstanceFields(true);

        LogicNode isInit = b.add(new LogicNegationNode(b.add(new IsNullNode(result))));
        ValuePhiNode[] phis = genCFG(b, result, fields, isInit);

        // PEA will replace oop with tagged hub if it is virtual
        return b.add(new ReturnScalarizedNode(result, List.of(phis)));
    }


    public static ValuePhiNode[] genCFG(GraphBuilderContext b, ValueNode result, ResolvedJavaField[] fields, LogicNode isInit) {
        BeginNode trueBegin = b.getGraph().add(new BeginNode());
        BeginNode falseBegin = b.getGraph().add(new BeginNode());

        IfNode ifNode = b.add(new IfNode(b.add(isInit), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));

        // get a valid framestate for the merge node
        FrameState framestate = GraphUtil.findLastFrameState(ifNode);

        ValueNode[] loads = new ValueNode[fields.length];
        ValueNode[] consts = new ValueNode[fields.length];

        // true branch - inline object is non-null, load the field values

        ValueNode nonNull = PiNode.create(result, objectNonNull(), trueBegin);
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
        merge.setStateAfter(framestate.duplicate());

        // produces phi nodes
        ValuePhiNode[] phis = new ValuePhiNode[fields.length];
        for (int i = 0; i < fields.length; i++) {
            phis[i] = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), fields[i].getType(), false).getTrustedStamp(), merge, loads[i], consts[i]));
        }

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        return phis;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {

        // get stack kinds and operands of the scalarized inline object
        int size = scalarizedInlineObject.size();
        JavaKind[] stackKinds = new JavaKind[size];
        Value[] operands = new Value[size];
        for (int i = 0; i < size; i++) {
            ValueNode valueNode = getField(i);
            stackKinds[i] = valueNode.getStackKind();
            operands[i] = gen.operand(valueNode);
        }

        gen.getLIRGeneratorTool().emitScalarizedReturn(result.getStackKind(),
                        gen.operand(result), stackKinds, operands);

    }

    private boolean virtualize = true;

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (!virtualize)
            return;
        ValueNode alias = tool.getAlias(result);
        if (alias instanceof VirtualObjectNode virtualObjectNode) {
            // make sure oop stays virtual and instead return hub with bit zero set
            TypeReference type = StampTool.typeReferenceOrNull(alias);
            assert type != null && type.isExact() : "type should not be null for constant hub node in scalarized return";

            if (!StampTool.isPointerNonNull(alias) || !tool.hasNoExistingOop(virtualObjectNode)) {
                // nullable scalarized inline object or non-null scalarized inline object including
                // oop
                ValueNode existingOop = tool.getExistingOop((VirtualObjectNode) alias);
                ValueNode isNotNull = tool.getIsNotNull((VirtualObjectNode) alias);
                assert existingOop != null && isNotNull != null : "nullable scalarized object expected existingOop and isNotNull information to be set";

                // get hub
                ConstantNode hub = ConstantNode.forConstant(tool.getStampProvider().createHubStamp(((ObjectStamp) result.stamp(NodeView.DEFAULT))),
                                tool.getConstantReflection().asObjectHub(type.getType()), tool.getMetaAccess());
                tool.addNode(hub);

                ValueNode returnResultDecider = new ReturnResultDeciderNode(isNotNull, existingOop, hub);
                tool.ensureAdded(returnResultDecider);
                tool.replaceFirstInput(result, returnResultDecider);

                // The nullable virtual inline object contains correct values for both cases (null
                // and non-null). Therefore use its values to replace the input list.
                // At a later stage this will remove the CFG which was created for the scalarized
                // return.
                if (!StampTool.isPointerNonNull(virtualObjectNode)) {
                    ResolvedJavaField[] fields = type.getType().getInstanceFields(true);
                    List<ValueNode> list = new ArrayList<>(scalarizedInlineObject.size());
                    for (int i = 0; i < fields.length; i++) {
                        int fieldIndex = ((VirtualInstanceNode) alias).fieldIndex(fields[i]);
                        ValueNode enty = tool.getEntry(virtualObjectNode, fieldIndex);
                        list.add(enty);
                    }
                    Runnable updateList = () -> {
                        scalarizedInlineObject.clear();
                        scalarizedInlineObject.addAll(list);
                    };
                    tool.applyRunnable(this, updateList);
                }
                return;

            }

            // get hub
            ConstantNode hub = ConstantNode.forConstant(tool.getStampProvider().createHubStamp(((ObjectStamp) result.stamp(NodeView.DEFAULT))),
                            tool.getConstantReflection().asObjectHub(type.getType()), tool.getMetaAccess());
            tool.addNode(hub);

            // set bit zero to one
            ValueNode taggedHub = new TagHubNode(hub);
            tool.addNode(taggedHub);

            // replace the object with the hub to avoid materialization
            tool.replaceFirstInput(result, taggedHub);
        }
    }

}
