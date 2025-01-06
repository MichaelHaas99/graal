package jdk.graal.compiler.nodes.extended;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;

@NodeInfo(nameTemplate = "ProjWithInputNode")
public class ProjWithInputNode extends ProjNode {
    public static final NodeClass<ProjWithInputNode> TYPE = NodeClass.create(ProjWithInputNode.class);

    @Input ValueNode input;

    public ValueNode getInput() {
        return input;
    }

    public ProjWithInputNode(Stamp stamp, ValueNode multiNode, int index, ValueNode input) {
        super(TYPE, stamp, multiNode, index);
        this.input = input;
    }
}