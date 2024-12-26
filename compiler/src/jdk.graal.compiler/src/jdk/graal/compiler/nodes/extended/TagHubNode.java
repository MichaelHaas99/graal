package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_2;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_1;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.OrNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.word.WordCastNode;

@NodeInfo(cycles = CYCLES_2, size = SIZE_1)
public class TagHubNode extends FixedWithNextNode implements Lowerable {

    public static final NodeClass<TagHubNode> TYPE = NodeClass.create(TagHubNode.class);
    @Input ValueNode value;

    public TagHubNode(ValueNode value) {
        super(TYPE, value.stamp(NodeView.DEFAULT));
        this.value = value;
    }

    public ValueNode getValue() {
        return value;
    }

    @Override
    public void lower(LoweringTool tool) {
        StructuredGraph graph = graph();

        WordCastNode hub = graph.addOrUnique(WordCastNode.addressToWord(value, tool.getWordTypes().getWordKind()));
        graph.addBeforeFixed(this, hub);
        // set bit 0 to 1, to indicate a scalarized return value
        ValueNode taggedHub = graph.addOrUnique(new OrNode(hub, graph.addOrUnique(ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1))));
        graph.replaceFixed(this, taggedHub);
    }

}
