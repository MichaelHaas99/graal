package jdk.graal.compiler.hotspot.replacements;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;

@NodeInfo
public class ParametersAssignNode extends FixedWithNextNode implements LIRLowerable {

    public static final NodeClass<ParametersAssignNode> TYPE = NodeClass.create(ParametersAssignNode.class);

    @OptionalInput NodeInputList<ValueNode> oldParams;
    @OptionalInput NodeInputList<ValueNode> newParams;
    ResolvedJavaMethod targetMethod;

    @SuppressWarnings("this-escape")
    public ParametersAssignNode(ValueNode[] oldParams, ValueNode[] newParams, ResolvedJavaMethod targetMethod) {
        super(TYPE, StampFactory.forVoid());
        this.oldParams = new NodeInputList<>(this, newParams);
        this.newParams = new NodeInputList<>(this, newParams);
        this.targetMethod = targetMethod;
    }

    @Override
    public void generate(NodeLIRBuilderTool generator) {
        JavaType[] parameterTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true).toArray(new JavaType[0]);
        CallingConvention callingConvention = generator.getLIRGeneratorTool().getRegisterConfig().getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes,
                        generator.getLIRGeneratorTool());
        int i = 0;
        for (ValueNode param : newParams) {
            Value dst = callingConvention.getArgument(i++);
            assert dst.getValueKind().equals(generator.getLIRGeneratorTool().getLIRKind(param.stamp(NodeView.DEFAULT))) : dst + " " +
                            generator.getLIRGeneratorTool().getLIRKind(param.stamp(NodeView.DEFAULT));
            generator.getLIRGeneratorTool().emitMove((AllocatableValue) dst, generator.operand(param));
        }
    }
}
