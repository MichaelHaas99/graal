package jdk.graal.compiler.nodes;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.ReturnResultDeciderNode;
import jdk.graal.compiler.nodes.extended.TagHubNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

/**
 * The {@link ReturnScalarizedNode} represents a return of a nullable scalarized inline object. see
 * Compile::return_values in parse.cpp of the C2 compiler. In case the scalarized inline object is
 * not null, either an non-null oop is placed into the first register or the tagged hub. In case the
 * scalarized inline object is null, a null pointer is placed into the first register.
 */
@NodeInfo(nameTemplate = "ReturnScalarized")
public class ReturnScalarizedNode extends ReturnNode implements Virtualizable {
    public static final NodeClass<ReturnScalarizedNode> TYPE = NodeClass.create(ReturnScalarizedNode.class);


    @OptionalInput private NodeInputList<ValueNode> fieldValues;

    public ReturnScalarizedNode(ValueNode result, List<ValueNode> fieldValues) {
        super(TYPE, result);
        this.fieldValues = new NodeInputList<>(this, fieldValues);
    }

    public void setResult(ValueNode newResult) {
        updateUsages(this.result, newResult);
        this.result = newResult;
    }

    public ValueNode getField(int index) {
        return fieldValues.get(index);
    }

    public static ReturnNode createAndAppend(GraphBuilderContext b, ValueNode result, ResolvedJavaType type) {
        ResolvedJavaField[] fields = type.getInstanceFields(true);

        LogicNode nonNull = b.add(new LogicNegationNode(b.add(new IsNullNode(result))));

        // PEA will replace oop with tagged hub if it is virtual
        ReturnScalarizedNode returnNode = b.add(new ReturnScalarizedNode(result, new ArrayList<ValueNode>(fields.length)));
        ValueNode[] phis = InlineTypeUtil.createScalarizationCFG(returnNode, result, nonNull, fields);
        returnNode.fieldValues.clear();
        returnNode.fieldValues.addAll(List.of(phis));
        return returnNode;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {

        // get stack kinds and operands of the scalarized inline object
        int size = fieldValues.size();
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

            if (!StampTool.isPointerNonNull(alias) || !tool.hasNullOop(virtualObjectNode)) {
                // nullable scalarized inline object or non-null scalarized inline object including
                // oop
                ValueNode oop = tool.getOop((VirtualObjectNode) alias);
                ValueNode nonNull = tool.getNonNull((VirtualObjectNode) alias);
                assert oop != null && nonNull != null : "nullable scalarized object expected oop and nonNull information to be set";

                // get hub
                ConstantNode hub = ConstantNode.forConstant(tool.getStampProvider().createHubStamp(((ObjectStamp) result.stamp(NodeView.DEFAULT))),
                                tool.getConstantReflection().asObjectHub(type.getType()), tool.getMetaAccess());
                tool.addNode(hub);

                ValueNode returnResultDecider = new ReturnResultDeciderNode(tool.getWordTypes().getWordKind(), nonNull, oop, hub);
                tool.ensureAdded(returnResultDecider);
                tool.replaceFirstInput(result, returnResultDecider);
// ForeignCallNode print = new ForeignCallNode(LOG_PRIMITIVE,
// ConstantNode.forInt(JavaKind.Long.getTypeChar(), graph()), returnResultDecider,
// ConstantNode.forBoolean(true, graph()));
// tool.addNode(print);

                // The nullable virtual inline object contains correct values for both cases (null
                // and non-null). Therefore use its values to replace the input list.
                // At a later stage this will remove the CFG which was created for the scalarized
                // return.
                if (!StampTool.isPointerNonNull(virtualObjectNode)) {
                    ResolvedJavaField[] fields = type.getType().getInstanceFields(true);
                    for (int i = 0; i < fields.length; i++) {
                        int fieldIndex = ((VirtualInstanceNode) alias).fieldIndex(fields[i]);
                        ValueNode entry = tool.getEntry(virtualObjectNode, fieldIndex);
                        tool.replaceFirstInput(fieldValues.get(i), entry);
                    }
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

// ForeignCallNode print = new ForeignCallNode(LOG_PRIMITIVE,
// ConstantNode.forInt(JavaKind.Long.getTypeChar(), graph()), taggedHub,
// ConstantNode.forBoolean(true, graph()));
// tool.addNode(print);
        }
    }

}
