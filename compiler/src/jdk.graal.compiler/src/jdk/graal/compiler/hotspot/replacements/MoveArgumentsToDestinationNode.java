package jdk.graal.compiler.hotspot.replacements;

import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;

/**
 * Moves input arguments into their designated slots according to the calling convention.
 */
@NodeInfo
public class MoveArgumentsToDestinationNode extends FixedWithNextNode implements LIRLowerable {

    public static final NodeClass<MoveArgumentsToDestinationNode> TYPE = NodeClass.create(MoveArgumentsToDestinationNode.class);

    @OptionalInput NodeInputList<ValueNode> newArguments;
    ResolvedJavaMethod targetMethod;
    List<Value> values;

    @SuppressWarnings("this-escape")
    public MoveArgumentsToDestinationNode(List<ValueNode> newArguments, ResolvedJavaMethod targetMethod, List<Value> values) {
        super(TYPE, StampFactory.forVoid());
        this.newArguments = new NodeInputList<>(this, newArguments);
        this.targetMethod = targetMethod;
        this.values = values;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        // process in the reverse order as the stack is likely to be extended and slots are not
        // block by old arguments
        for (int i = newArguments.size() - 1; i >= 0; i--) {
            ValueNode param = newArguments.get(i);
            Value dst = values.get(i);
            assert dst.getValueKind().equals(generator.getLIRGeneratorTool().getLIRKind(param.stamp(NodeView.DEFAULT))) : dst + " " +
                            generator.getLIRGeneratorTool().getLIRKind(param.stamp(NodeView.DEFAULT));
            generator.getLIRGeneratorTool().emitMove((AllocatableValue) dst, generator.operand(param));
        }
    }
}
