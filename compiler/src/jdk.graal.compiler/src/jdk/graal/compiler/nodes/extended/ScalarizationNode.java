package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.IterableNodeType;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.spi.NodeWithIdentity;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.MultiValue;
import jdk.graal.compiler.nodes.memory.MemoryAccess;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Scalarizes a value object. It is useful to delay the insertion of a graph diamond, as the value
 * object may be virtual during PEA. In this case it is easy to avoid scalarization graphs by
 * deleting this node.
 */
@NodeInfo(cycles = CYCLES_UNKNOWN, cyclesRationale = "We don't know statically how many, and which, objects we are gonna scalarize.", size = SIZE_UNKNOWN, sizeRationale = "We don't know statically how much code for which scalarization has to be generated.")
public class ScalarizationNode extends FixedWithNextNode implements MemoryAccess, Virtualizable, MultiValue, IterableNodeType, NodeWithIdentity {

    public static final NodeClass<ScalarizationNode> TYPE = NodeClass.create(ScalarizationNode.class);
    @Input ValueNode object;
    private final ResolvedJavaType type;

    public ValueNode object() {
        return object;
    }

    public ResolvedJavaType getType() {
        return type;
    }

    private ScalarizationNode(NodeClass<? extends FixedWithNextNode> c, ValueNode object, ResolvedJavaType type) {
        super(c, StampFactory.object());
        this.object = object;
        this.type = type;
    }

    public ScalarizationNode(ValueNode object, ResolvedJavaType type) {
        this(TYPE, object, type);
    }

    public static Data appendReadMultiValueNodes(StructuredGraph graph, ScalarizationNode node) {

        // can also represent an oop or a null pointer
        ReadMultiValueNode oop = graph.addOrUnique(ReadMultiValueNode.createOop(node.getType(), graph.getAssumptions(), node, 0));

        ResolvedJavaField[] fields = node.getType().getInstanceFields(true);
        ReadMultiValueNode[] fieldValues = new ReadMultiValueNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            fieldValues[i] = graph.addOrUnique(ReadMultiValueNode.createFieldValue(fields[i].getType(), graph.getAssumptions(), node, i + 1));

        }

        ReadMultiValueNode nonNull = graph.addOrUnique(ReadMultiValueNode.createNonNull(
                        node, fields.length + 1));
        return new Data(oop, fieldValues, nonNull);
    }

    public record Data(ReadMultiValueNode oop, ReadMultiValueNode[] fieldValues, ReadMultiValueNode nonNull) {
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return LocationIdentity.any();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (tool.getAlias(object) instanceof VirtualObjectNode virtualObjectNode) {
            tool.replaceWithVirtual(virtualObjectNode);
        }

    }
}
