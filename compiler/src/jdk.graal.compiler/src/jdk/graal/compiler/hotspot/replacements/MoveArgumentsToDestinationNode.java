package jdk.graal.compiler.hotspot.replacements;

import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ControlSinkNode;
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
public class MoveArgumentsToDestinationNode extends ControlSinkNode implements LIRLowerable {

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
        GraalError.guarantee(newArguments.isEmpty() || newArguments.size() == values.size(), "size does not match");
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        for (int i = 0; i < newArguments.size(); i++) {
            ValueNode newArgument = newArguments.get(i);
            Value newValue = values.get(i);
            assert newValue.getValueKind().equals(generator.getLIRGeneratorTool().getLIRKind(newArgument.stamp(NodeView.DEFAULT))) : newValue + " " +
                            generator.getLIRGeneratorTool().getLIRKind(newArgument.stamp(NodeView.DEFAULT));
            generator.getLIRGeneratorTool().emitMove((AllocatableValue) newValue, generator.operand(newArgument));
        }
        generator.getLIRGeneratorTool().append(new StandardOp.EntryPointEndOp(values.toArray(new Value[values.size()])));
    }
}
