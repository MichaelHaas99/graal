package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.NO_LOCATIONS;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypeMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypePattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.extended.FixedInlineTypeEqualityAnchorNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;

public class ObjectEqualsSnippets implements Snippets {

    public static class Templates extends InstanceOfSnippetsTemplates {
        private final SnippetTemplate.SnippetInfo objectEqualsSnippet;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            objectEqualsSnippet = snippet(providers, ObjectEqualsSnippets.class, "objectEquals");
        }

        @Override
        protected SnippetTemplate.Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            ValueNode node = replacer.instanceOf;
            SnippetTemplate.Arguments args;
            if (node instanceof ObjectEqualsNode objectEqualsNode) {
                args = new SnippetTemplate.Arguments(objectEqualsSnippet, node.graph().getGuardsStage(), tool.getLoweringStage());
                if (objectEqualsNode.getX() instanceof FixedInlineTypeEqualityAnchorNode xAnchorNode) {
                    args.add("x", xAnchorNode.object());
                } else {
                    args.add("x", objectEqualsNode.getX());
                }
                if (objectEqualsNode.getY() instanceof FixedInlineTypeEqualityAnchorNode yAnchorNode) {
                    args.add("y", yAnchorNode.object());
                } else {
                    args.add("y", objectEqualsNode.getY());
                }
// args.add("x", ((FixedInlineTypeEqualityAnchorNode) objectEqualsNode.getX()).object());
// args.add("y", ((FixedInlineTypeEqualityAnchorNode) objectEqualsNode.getY()).object());
            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer); // ExcludeFromJacocoGeneratedReport
            }
            args.add("trueValue", replacer.trueValue);
            args.add("falseValue", replacer.falseValue);
            return args;
        }
    }

    @Snippet
    protected static Object objectEquals(Object x, Object y, Object trueValue, Object falseValue) {
        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        if (xPointer.equal(yPointer))
            return trueValue;

        if (xPointer.isNull() || yPointer.isNull())
            return falseValue;
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        x = PiNode.piCastNonNull(x, anchorNode);
        y = PiNode.piCastNonNull(y, anchorNode);

        final Word xMark = loadWordFromObject(x, markOffset(INJECTED_VMCONFIG));
        final Word yMark = loadWordFromObject(y, markOffset(INJECTED_VMCONFIG));

        if (xMark.and(yMark).and(inlineTypeMaskInPlace(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG)))
            return falseValue;

        KlassPointer xHub = loadHub(x);
        KlassPointer yHub = loadHub(y);
        if (xHub.notEqual(yHub))
            return falseValue;

        return substitutabilityCheckStubC(SUBSTITUTABILITYCHECK, x, y) ? trueValue : falseValue;
    }

    public static final HotSpotForeignCallDescriptor SUBSTITUTABILITYCHECK = new HotSpotForeignCallDescriptor(LEAF, NO_SIDE_EFFECT, NO_LOCATIONS, "substitutabilityCheck", boolean.class, Object.class,
                    Object.class);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native boolean substitutabilityCheckStubC(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object x, Object y);
}
