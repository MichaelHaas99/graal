package jdk.graal.compiler.nodes;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.ReadMultiValueNode;
import jdk.graal.compiler.nodes.extended.ReturnResultDeciderNode;
import jdk.graal.compiler.nodes.extended.ScalarizationNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Simplifiable;
import jdk.graal.compiler.nodes.spi.SimplifierTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.Assumptions;
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
public class ReturnScalarizedNode extends ReturnNode implements Virtualizable, Lowerable, Simplifiable {
    public static final NodeClass<ReturnScalarizedNode> TYPE = NodeClass.create(ReturnScalarizedNode.class);

    @OptionalInput private NodeInputList<ValueNode> fieldValues;
    private final ResolvedJavaType returnType;

    @SuppressWarnings("this-escape")
    public ReturnScalarizedNode(ValueNode result, List<ValueNode> fieldValues, ResolvedJavaType returnType) {
        super(TYPE, result);
        this.fieldValues = new NodeInputList<>(this, fieldValues);
        this.returnType = returnType;
    }

    public void setResult(ValueNode newResult) {
        updateUsages(this.result, newResult);
        this.result = newResult;
    }

    public ValueNode getField(int index) {
        return fieldValues.get(index);
    }

    public static ReturnScalarizedNode create(ReturnScalarizedNode returnScalarizedNode, ValueNode result, ResolvedJavaType returnType, CoreProviders coreProviders, Assumptions assumptions,
                    List<FixedWithNextNode> fixedNodesToAdd) {
        return simplified(returnScalarizedNode, result, returnType, coreProviders, assumptions, fixedNodesToAdd, null);
    }

    private static ReturnScalarizedNode simplified(ReturnScalarizedNode returnScalarizedNode, ValueNode result, ResolvedJavaType returnType, CoreProviders coreProviders, Assumptions assumptions,
                    List<FixedWithNextNode> fixedNodesToAdd, SimplifierTool tool) {
        if (InlineTypeUtil.unproxify(result, tool) instanceof InlineTypeNode inlineTypeNode && result != inlineTypeNode.getOop()) {
            List<ValueNode> list = inlineTypeNode.getEntries();
            if (inlineTypeNode.isAllocatedOrNull()) {
                return new ReturnScalarizedNode(inlineTypeNode.getOop(), list, returnType);
            } else {
                ConstantNode hub = createHub(coreProviders, result, inlineTypeNode.getType());
                ValueNode nonNull = inlineTypeNode.getNonNull();
                if (StampTool.isPointerNonNull(result)) {
                    nonNull = ConstantNode.forInt(1, inlineTypeNode.graph());
                }
                ValueNode returnResultDecider = ReturnResultDeciderNode.create(coreProviders.getWordTypes().getWordKind(), nonNull, inlineTypeNode.getOop(), hub);
                if (returnResultDecider instanceof FixedWithNextNode fixedWithNextNode) {
                    fixedNodesToAdd.add(fixedWithNextNode);
                }
                return new ReturnScalarizedNode(returnResultDecider, list, returnType);
            }
        } else if (returnScalarizedNode == null) {
            ReturnScalarizedNode newReturnNode;
            ScalarizationNode scalarizationNode = new ScalarizationNode(result, returnType);
            fixedNodesToAdd.add(scalarizationNode);
            ReadMultiValueNode.MultiValues multiValues = ReadMultiValueNode.createNodes(scalarizationNode, assumptions);
            newReturnNode = new ReturnScalarizedNode(multiValues.oop(), List.of(multiValues.fieldValues()), returnType);
            return newReturnNode;
        } else {
            return returnScalarizedNode;
        }

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
        ReturnScalarizedNode returnNode = graph.addOrUnique(new ReturnScalarizedNode(result, new ArrayList<>(fields.length), type));
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

    @Override
    public boolean virtualizeHandlesNullableVirtualInputs() {
        return true;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(result);

        if (alias instanceof VirtualObjectNode virtualObjectNode) {
            ResolvedJavaType type = virtualObjectNode.type();
            ConstantNode hub = createHub(tool, result, type);
            tool.addNode(hub);

            ValueNode newFirstInput = ReturnResultDeciderNode.virtualizeReturnResultDecider(tool, virtualObjectNode, hub, true);
            tool.replaceFirstInput(result, newFirstInput);
        }
    }

    private static ConstantNode createHub(CoreProviders providers, ValueNode node, ResolvedJavaType type) {
        return ConstantNode.forConstant(providers.getStampProvider().createHubStamp(((ObjectStamp) node.stamp(NodeView.DEFAULT))),
                        providers.getConstantReflection().asObjectHub(type), providers.getMetaAccess());
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

    @Override
    public void simplify(SimplifierTool tool) {
        if (GraalOptions.PartialEscapeAnalysis.getValue(getOptions())) {
            return;
        }
        List<FixedWithNextNode> fixedNodesToAdd = new ArrayList<>();
        ReturnScalarizedNode newReturnNode = simplified(this, this.result, this.returnType, tool, tool.getAssumptions(), fixedNodesToAdd, tool);
        if (newReturnNode != this) {
            fixedNodesToAdd.forEach((FixedWithNextNode fixedWithNextNode) -> {
                graph().addOrUniqueWithInputs(fixedWithNextNode);
                graph().addBeforeFixed(this, fixedWithNextNode);
            });
            this.setResult(newReturnNode.result);
            this.fieldValues.clear();
            this.fieldValues.addAll(newReturnNode.fieldValues);
        }
    }
}
