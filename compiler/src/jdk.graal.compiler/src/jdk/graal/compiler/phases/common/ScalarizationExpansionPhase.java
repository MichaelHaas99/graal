package jdk.graal.compiler.phases.common;

import java.util.List;
import java.util.Optional;

import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ReadMultiValueNode;
import jdk.graal.compiler.nodes.extended.ScalarizationNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.phases.BasePhase;

/**
 * Creates scalarization graphs where it is requested by an {@link ScalarizationNode}.
 */
public class ScalarizationExpansionPhase extends BasePhase<CoreProviders> {

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return NotApplicable.ifAny(
                        NotApplicable.unlessRunBefore(this, GraphState.StageFlag.HIGH_TIER_LOWERING, graphState));
    }

    @SuppressWarnings("try")
    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {

        for (ScalarizationNode n : graph.getNodes(ScalarizationNode.TYPE)) {
            try (DebugCloseable scope = n.graph().withNodeSourcePosition(n)) {
                ValueNode[] scalarizedValues = InlineTypeUtil.createScalarizationCFG(n, n.object(), List.of(n.getType().getInstanceFields(true)), false, true);
                ReadMultiValueNode nonNull = n.getNonNull();
                if (nonNull != null) {
                    nonNull.replaceAndDelete(scalarizedValues[0]);
                }
                ReadMultiValueNode oop = n.getOop();
                if (oop != null) {
                    oop.replaceAndDelete(n.object());
                }
                List<ReadMultiValueNode> entries = n.getFieldValues();
                for (ReadMultiValueNode entry : entries) {
                    // The lowest index for a field value is 1. As the field values in
                    // scalarizedValues also start at index 1, no index correction is necessary.
                    entry.replaceAndDelete(scalarizedValues[entry.getIndex()]);
                }
                graph.removeFixed(n);

            }
        }
    }
}
