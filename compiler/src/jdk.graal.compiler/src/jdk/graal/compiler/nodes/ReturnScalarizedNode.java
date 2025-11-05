package jdk.graal.compiler.nodes;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.ReturnResultDeciderNode;
import jdk.graal.compiler.nodes.extended.TagHubNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

/**
 * The {@link ReturnScalarizedNode} represents a return of a nullable scalarized inline object. see
 * Compile::return_values in parse.cpp of the C2 compiler. In case the scalarized inline object is
 * not null, either an non-null oop is placed into the first register or the tagged hub. In case the
 * scalarized inline object is null, a null pointer is placed into the first register. TODO: phases
 * which insert safepoints are not allowed to directly insert them before this node. Instead they
 * need to insert them before {@link ReturnResultDeciderNode}. As
 * {@code LoopSafepointInsertionPhase} only inserts them in loops we are fine for the Graal JIT.
 * This not the case for substrate.
 */
@NodeInfo(nameTemplate = "ReturnScalarized")
public class ReturnScalarizedNode extends ReturnNode implements Virtualizable, Lowerable {
    public static final NodeClass<ReturnScalarizedNode> TYPE = NodeClass.create(ReturnScalarizedNode.class);

    @OptionalInput private NodeInputList<ValueNode> fieldValues;

    @SuppressWarnings("this-escape")
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

        ReturnScalarizedNode returnNode;
        if (GraphUtil.unproxify(result) instanceof InlineTypeNode inlineTypeNode) {
            List<ValueNode> list = inlineTypeNode.getEntries();
            if (inlineTypeNode.isAllocatedOrNull()) {
                returnNode = b.add(new ReturnScalarizedNode(inlineTypeNode.getOop(), list));
            } else {
                ConstantNode hub = b.add(createHub(b, result, inlineTypeNode.getType()));
                ValueNode nonNull = inlineTypeNode.getNonNull();
                if (StampTool.isPointerNonNull(result)) {
                    nonNull = ConstantNode.forInt(1, inlineTypeNode.graph());
                }
                ValueNode returnResultDecider = b.add(ReturnResultDeciderNode.create(b.getWordTypes().getWordKind(), nonNull, inlineTypeNode.getOop(), hub));
                returnNode = b.add(new ReturnScalarizedNode(returnResultDecider, list));
            }
        } else {
            // need to add the return node here as the util adds the cfg before a fixed node
            returnNode = b.add(new ReturnScalarizedNode(result, new ArrayList<>(fields.length)));
            returnNode.fieldValues.clear();
            ValueNode[] phis = InlineTypeUtil.createScalarizationCFG(returnNode, result, List.of(fields), false, false);
            returnNode.fieldValues.addAll(List.of(phis));
        }
        return returnNode;
    }

    /**
     * Replaces an oop return with a scalrized return. Not used at the moment.
     */

    public static void replaceReturn(ReturnNode oldReturn) {
        StructuredGraph graph = oldReturn.graph();
        ValueNode result = oldReturn.result();
        ResolvedJavaMethod method = graph.method();
        ResolvedJavaType type = method.getSignature().getReturnType(method.getDeclaringClass()).resolve(method.getDeclaringClass());
        ResolvedJavaField[] fields = type.getInstanceFields(true);

        // PEA will replace oop with tagged hub if it is virtual
        ReturnScalarizedNode returnNode = graph.addOrUnique(new ReturnScalarizedNode(result, new ArrayList<>(fields.length)));
        FixedWithNextNode previous = (FixedWithNextNode) oldReturn.predecessor();
        previous.setNext(returnNode);
        oldReturn.replaceAtUsages(returnNode);
        oldReturn.safeDelete();

        ValueNode[] phis = InlineTypeUtil.createScalarizationCFG(returnNode, result, List.of(fields), false, false);
        returnNode.fieldValues.clear();
        returnNode.fieldValues.addAll(List.of(phis));
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
    public boolean virtualizeHandlesNullableVirtualInputs() {
        return true;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (!virtualize) {
            return;
        }

        ValueNode alias = tool.getAlias(result);

        if (alias instanceof VirtualObjectNode virtualObjectNode) {
            // make sure oop stays virtual and instead return hub with bit zero set
            TypeReference typeReference = StampTool.typeReferenceOrNull(alias);
            assert typeReference != null && typeReference.isExact() : "type should not be null for constant hub node in scalarized return";

            if (!tool.isNonNull(virtualObjectNode) || !tool.hasNullOop(virtualObjectNode)) {
                // nullable scalarized inline object or non-null scalarized inline object including
                // oop
                ValueNode oop = tool.getOop((VirtualObjectNode) alias);
                ValueNode nonNull = tool.getNonNull((VirtualObjectNode) alias);
                assert oop != null && nonNull != null : "nullable scalarized object expected oop and non-null information to be set";

                // get hub
                ConstantNode hub = createHub(tool, result, typeReference.getType());
                tool.addNode(hub);

// ForeignCallNode print = new ForeignCallNode(LOG_PRIMITIVE,
// ConstantNode.forInt(JavaKind.Long.getTypeChar(), graph()), returnResultDecider,
// ConstantNode.forBoolean(true, graph()));
// tool.addNode(print);

                // The nullable virtual inline object contains correct values for both cases (null
                // and non-null). Therefore use its values to replace the input list.
                // At a later stage this will remove the CFG which was created for the scalarized
                // return.
                replaceAndMaterializeFields(tool, (VirtualInstanceNode) virtualObjectNode, typeReference.getType());

                if (tool.isAllocatedOrNull(virtualObjectNode)) {
                    tool.replaceFirstInput(result, oop);
                } else {
                    ValueNode returnResultDecider = ReturnResultDeciderNode.create(tool.getWordTypes().getWordKind(), nonNull, oop, hub);
                    tool.ensureAdded(returnResultDecider);
                    tool.replaceFirstInput(result, returnResultDecider);
                }
                return;

            }

            // materialize before tagged hub node, a klass pointer in a register is dangerous, make
            // sure it comes last
            replaceAndMaterializeFields(tool, (VirtualInstanceNode) virtualObjectNode, typeReference.getType());

            // get hub
            ConstantNode hub = createHub(tool, result, typeReference.getType());
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
        } else {
            // materialize the field values if they are virtual
            materializeFields(tool);
        }
    }

    private static ConstantNode createHub(CoreProviders providers, ValueNode node, ResolvedJavaType type) {
        return ConstantNode.forConstant(providers.getStampProvider().createHubStamp(((ObjectStamp) node.stamp(NodeView.DEFAULT))),
                        providers.getConstantReflection().asObjectHub(type), providers.getMetaAccess());
    }

    private void materializeFields(VirtualizerTool tool) {
        for (int i = 0; i < fieldValues.size(); i++) {
            ValueNode field = tool.getAlias(fieldValues.get(i));
            if (field instanceof VirtualObjectNode virtualObjectNode) {
                tool.ensureMaterialized(virtualObjectNode);
            }
        }
    }

    private void replaceAndMaterializeFields(VirtualizerTool tool, VirtualInstanceNode alias, ResolvedJavaType type) {
        ResolvedJavaField[] fields = type.getInstanceFields(true);
        for (int i = 0; i < fields.length; i++) {
            int fieldIndex = alias.fieldIndex(fields[i]);
            ValueNode entry = tool.getEntry(alias, fieldIndex);
            replaceInputWithMaterializedValue(tool, entry, i);
        }
    }

    private void replaceInputWithMaterializedValue(VirtualizerTool tool, ValueNode entry, int index) {
        if (entry instanceof VirtualObjectNode virtualObjectNode) {
            tool.ensureMaterialized(virtualObjectNode);
        }
        ValueNode alias = tool.getAlias(entry);
        if (alias instanceof VirtualObjectNode virtualObjectNode) {
            tool.replaceFirstInput(fieldValues.get(index), tool.getOop(virtualObjectNode));
        }
        tool.replaceFirstInput(fieldValues.get(index), tool.getAlias(entry));
    }

    @Override
    public void lower(LoweringTool tool) {
        if (tool.getLoweringStage() == LoweringTool.StandardLoweringStage.HIGH_TIER && result instanceof ReturnResultDeciderNode returnResultDeciderNode) {
            /*
             * Make sure the ReturnResultDeciderNode node is the last node before the return node.
             * An earlier PEA may insert an allocation below it. The allocation needs a frame state.
             * This would lead to an unknown reference alive across safepoint as the
             * ReturnResultDeciderNode merges a klass pointer with a tracked oop.
             */
            if (this.predecessor() != result) {
                StructuredGraph graph = graph();
                FixedNode next = returnResultDeciderNode.next();
                returnResultDeciderNode.setNext(null);
                ((FixedWithNextNode) returnResultDeciderNode.predecessor()).setNext(next);
                graph.addBeforeFixed(this, returnResultDeciderNode);
            }
        }
    }

}
