package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.nodes.FloatingGuardedNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import org.graalvm.collections.Pair;
import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.IterableNodeType;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.MultiValue;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.MemoryAccess;
import jdk.graal.compiler.nodes.spi.Simplifiable;
import jdk.graal.compiler.nodes.spi.SimplifierTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Scalarizes a value object. It is useful to delay the insertion of a graph diamond, as the value
 * object may be virtual during PEA. In this case it is easy to avoid scalarization graphs by
 * deleting this node.
 */
@NodeInfo(cycles = CYCLES_UNKNOWN, cyclesRationale = "We don't know statically how many, and which, objects we are gonna scalarize.", size = SIZE_UNKNOWN, sizeRationale = "We don't know statically how much code for which scalarization has to be generated.")
public class ScalarizationNode extends FloatingGuardedNode implements Virtualizable, MultiValue, IterableNodeType, Simplifiable, Lowerable {

    public static final NodeClass<ScalarizationNode> TYPE = NodeClass.create(ScalarizationNode.class);
    @Input ValueNode object;
    private final ResolvedJavaType type;

    public ValueNode object() {
        return object;
    }

    public ResolvedJavaType getType() {
        return type;
    }

    private ScalarizationNode(NodeClass<? extends FloatingGuardedNode> c, ValueNode object, ResolvedJavaType type, GuardingNode guard) {
        super(c, StampFactory.object(), guard);
        this.object = object;
        this.type = type;
    }

    protected ScalarizationNode(ValueNode object, ResolvedJavaType type, GuardingNode guard) {
        this(TYPE, object, type, guard);
    }

    public static Pair<ScalarizationNode, ReadMultiValueNode.MultiValues> create(ValueNode object, ResolvedJavaType type, Assumptions assumptions) {
        return simplified(null, object, type, assumptions, null, null);
    }

    public static Pair<ScalarizationNode, ReadMultiValueNode.MultiValues> create(ValueNode object, ResolvedJavaType type, Assumptions assumptions, GuardingNode guard) {
        return simplified(null, object, type, assumptions, null, guard);
    }

    public static Pair<ScalarizationNode, ReadMultiValueNode.MultiValues> simplified(ScalarizationNode scalarizationNode, ValueNode object, ResolvedJavaType type, Assumptions assumptions,
                    SimplifierTool tool, GuardingNode guard) {
        ResolvedJavaField[] fields = type.getInstanceFields(true);
        if (StampTool.isPointerAlwaysNull(object)) {
            ValueNode oop = object;
            ValueNode nonNull = ConstantNode.forInt(0);
            ValueNode[] fieldValues = new ValueNode[fields.length];
            for (int i = 0; i < fields.length; i++) {
                fieldValues[i] = ConstantNode.defaultForKind(fields[i].getJavaKind());
            }
            return Pair.create(null, new ReadMultiValueNode.MultiValues(oop, fieldValues, nonNull));
        }

        if (InlineTypeUtil.unproxify(object, tool) instanceof InlineTypeNode inlineTypeNode) {
            boolean equalStamps = object.stamp(NodeView.DEFAULT).equals(inlineTypeNode.stamp(NodeView.DEFAULT));
            ValueNode oop = equalStamps ? inlineTypeNode : object;
            ValueNode nonNull = inlineTypeNode.getNonNull();
            if (StampTool.isPointerNonNull(object)) {
                nonNull = ConstantNode.forInt(1);
            }
            ValueNode[] fieldValues = inlineTypeNode.getEntries().toArray(ValueNode.EMPTY_ARRAY);
            return Pair.create(null, new ReadMultiValueNode.MultiValues(oop, fieldValues, nonNull));
        }
        if (scalarizationNode == null) {
            ScalarizationNode newScalarizationNode = new ScalarizationNode(object, type, guard);
            ReadMultiValueNode.MultiValues multiValues = ReadMultiValueNode.createNodes(newScalarizationNode, assumptions);
            return Pair.create(newScalarizationNode, multiValues);
        } else {
            return Pair.create(scalarizationNode, null);
        }
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (tool.getAlias(object) instanceof VirtualObjectNode virtualObjectNode) {
            tool.replaceWithVirtual(virtualObjectNode);
        }

    }

    @Override
    public void simplify(SimplifierTool tool) {
        if (GraalOptions.PartialEscapeAnalysis.getValue(getOptions())) {
            return;
        }

        List<Node> objectUsages = object.usages().snapshot();
        Pair<ScalarizationNode, ReadMultiValueNode.MultiValues> pair = simplified(this, object, type, tool.getAssumptions(), tool, null);
        ScalarizationNode newScalarizationNode = pair.getLeft();
        if (newScalarizationNode != this) {
            StructuredGraph graph = graph();
            ReadMultiValueNode.MultiValues newMultiValues = pair.getRight();
            ReadMultiValueNode oop = getOop();
            if (oop != null) {
                getOop().replaceAndDelete(graph.addOrUnique(newMultiValues.oop()));
            }
            ReadMultiValueNode nonNull = getNonNull();
            if (nonNull != null) {
                getNonNull().replaceAndDelete(graph.addOrUnique(newMultiValues.nonNull()));
            }
            ValueNode[] newFieldValues = newMultiValues.fieldValues();
            for (ReadMultiValueNode fieldValue : getFieldValues()) {
                fieldValue.replaceAndDelete(graph.addOrUnique(newFieldValues[fieldValue.getIndex() - 1]));
            }
            tool.addToWorkList(objectUsages);
            // add to worklist again in case it has no usages now
            tool.addToWorkList(this);
        }
    }

    public void lower(LoweringTool loweringTool) {
        if (loweringTool.getLoweringStage() == LoweringTool.StandardLoweringStage.HIGH_TIER){
            return;
        }
        List<ReadMultiValueNode> fieldValues = getFieldValues();
        ArrayList<ResolvedJavaField> fields = new ArrayList<>(fieldValues.size());
        ResolvedJavaField[] instanceFields = this.getType().getInstanceFields(true);
        for (ReadMultiValueNode fieldValue : fieldValues) {
            fields.add(instanceFields[fieldValue.getIndex() - 1]);
        }
        ValueNode[] scalarizedValues = InlineTypeUtil.createScalarizationCFG(loweringTool.lastFixedNode().next(), this.object(), fields, false, true);
        ReadMultiValueNode nonNull = this.getNonNull();
        if (nonNull != null) {
            nonNull.replaceAndDelete(scalarizedValues[0]);
        }
        ReadMultiValueNode oop = this.getOop();
        if (oop != null) {
            oop.replaceAndDelete(this.object());
        }
        List<ReadMultiValueNode> entries = this.getFieldValues();
        for (int i = 0; i < entries.size(); i++) {
            // The lowest index for a field value is 1. As the field values in
            // scalarizedValues also start at index 1, no index correction is necessary.
            entries.get(i).replaceAndDelete(scalarizedValues[i + 1]);
        }

        for (int i = 0; i < scalarizedValues.length; i++) {
            ValueNode entry = scalarizedValues[i];
            if (entry instanceof Lowerable lowerable) {
                lowerable.lower(loweringTool);
            }
            if (entry instanceof ValuePhiNode phiNode) {
                for (ValueNode input : phiNode.values()) {
                    if (input instanceof Lowerable lowerable) {
                        lowerable.lower(loweringTool);
                    }
                }
            }
        }
    }
}
