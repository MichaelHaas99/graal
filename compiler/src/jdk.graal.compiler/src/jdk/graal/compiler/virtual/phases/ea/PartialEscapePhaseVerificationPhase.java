package jdk.graal.compiler.virtual.phases.ea;

import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.ScalarizationNode;
import jdk.graal.compiler.nodes.java.FinalFieldBarrierNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.phases.VerifyPhase;

public class PartialEscapePhaseVerificationPhase extends VerifyPhase<CoreProviders> {

    @Override
    protected void verify(StructuredGraph graph, CoreProviders context) {

        for (ScalarizationNode scalarizationNode : graph.getNodes(ScalarizationNode.TYPE)) {
            GuardingNode guard = scalarizationNode.getGuard();
            // TestCallingConvention.test56_verifier has object return from method handle which is
            // scalarized argument
            if (guard == null || guard instanceof Invoke || guard instanceof FinalFieldBarrierNode ||
                            !StampTool.isNullableInlineType(scalarizationNode.object(), context.getValhallaOptionsProvider())) {
                continue;
            }
            throw new VerificationError("%s should have no anchor",
                            scalarizationNode);
        }

        for (LoadFieldNode loadFieldNode : graph.getNodes(LoadFieldNode.TYPE)) {
            ValueNode object = loadFieldNode.object();
            // TestLWorld.test44 has type mismatch
            if (object != null && StampTool.isNullableInlineType(object, context.getValhallaOptionsProvider()) && loadFieldNode.field().getContainerClass().equals(StampTool.typeOrNull(object))) {
                throw new VerificationError("%s should have been replaced by a scalarized value object",
                                loadFieldNode);
            }
        }
    }
}
