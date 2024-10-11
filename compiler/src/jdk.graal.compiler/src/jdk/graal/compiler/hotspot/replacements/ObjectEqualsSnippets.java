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
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.ClassCastException;
import static jdk.vm.ci.meta.DeoptimizationReason.NullCheckException;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.DeoptimizeNode;
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
import jdk.vm.ci.hotspot.ACmpDataAccessor;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.hotspot.SingleTypeEntry;

public class ObjectEqualsSnippets implements Snippets {

    public static class Templates extends InstanceOfSnippetsTemplates {
        private final SnippetTemplate.SnippetInfo objectEqualsSnippet;
        private final SnippetTemplate.SnippetInfo objectEqualsSnippetWithProfile;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            objectEqualsSnippet = snippet(providers, ObjectEqualsSnippets.class, "objectEquals");
            objectEqualsSnippetWithProfile = snippet(providers, ObjectEqualsSnippets.class, "objectEqualsWithProfile");
        }

        @Override
        protected SnippetTemplate.Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            ValueNode node = replacer.instanceOf;
            SnippetTemplate.Arguments args;
            ACmpDataAccessor profile = null;
            if (node instanceof ObjectEqualsNode objectEqualsNode) {
                Object x = objectEqualsNode.getX();
                Object y = objectEqualsNode.getY();
                if (objectEqualsNode.getX() instanceof FixedInlineTypeEqualityAnchorNode xAnchorNode) {
                    profile = xAnchorNode.getProfile();
                    x = xAnchorNode.object();
                }
                if (objectEqualsNode.getY() instanceof FixedInlineTypeEqualityAnchorNode yAnchorNode) {
                    y = yAnchorNode.object();
                }

// args = new SnippetTemplate.Arguments(objectEqualsSnippet, node.graph().getGuardsStage(),
// tool.getLoweringStage());
// args.add("x", x);
// args.add("y", y);
// args.add("trueValue", replacer.trueValue);
// args.add("falseValue", replacer.falseValue);
                if (profile != null) {
                    args = new SnippetTemplate.Arguments(objectEqualsSnippetWithProfile,
                                    node.graph().getGuardsStage(), tool.getLoweringStage());
                    args.add("x", x);
                    args.add("y", y);
                    args.add("trueValue", replacer.trueValue);
                    args.add("falseValue", replacer.falseValue);
                    SingleTypeEntry leftEntry = profile.getLeft();
                    SingleTypeEntry rightEntry = profile.getRight();
                    boolean xAlwaysNull = leftEntry.alwaysNull();
                    boolean xInlineType = leftEntry.inlineType();
                    boolean yAlwaysNull = rightEntry.alwaysNull();
                    boolean yInlineType = rightEntry.inlineType();
                    HotSpotResolvedObjectType test = leftEntry.getValidType();
                    HotSpotResolvedObjectType test2 = rightEntry.getValidType();
                    args.addConst("xAlwaysNull", xAlwaysNull);
                    args.addConst("xInlineType", xInlineType);
                    args.addConst("yAlwaysNull", yAlwaysNull);
                    args.addConst("yInlineType", yInlineType);

// (Object x, Object y, Object trueValue, Object falseValue, KlassPointer xProfileHub,
// @ConstantParameter boolean xProfileHubInline,
// @ConstantParameter boolean xAlwaysNull,
// @ConstantParameter boolean xInlineType, KlassPointer yProfileHub,
// @ConstantParameter boolean yProfileHubInline,
// @ConstantParameter boolean yInlineType, @ConstantParameter boolean yAlwaysNull)

// @ConstantParameter boolean xAlwaysNull,
// @ConstantParameter boolean xInlineType,
// @ConstantParameter boolean yInlineType, @ConstantParameter boolean yAlwaysNull)

                } else {
                    args = new SnippetTemplate.Arguments(objectEqualsSnippet, node.graph().getGuardsStage(),
                                    tool.getLoweringStage());
                    args.add("x", x);
                    args.add("y", y);
                    args.add("trueValue", replacer.trueValue);
                    args.add("falseValue", replacer.falseValue);
                }

                return args;
            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer); // ExcludeFromJacocoGeneratedReport
            }
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

    @Snippet
    protected static Object objectEqualsWithProfile(Object x, Object y, Object trueValue, Object falseValue,
                    @ConstantParameter boolean xAlwaysNull,
                    @ConstantParameter boolean xInlineType, @ConstantParameter boolean yAlwaysNull,
                    @ConstantParameter boolean yInlineType) {

        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        if (xPointer.equal(yPointer))
            return trueValue;

        if (xAlwaysNull) {
            if (xPointer.isNull())
                return falseValue;
            DeoptimizeNode.deopt(InvalidateReprofile, NullCheckException);
            return falseValue;
        }

        if (yAlwaysNull) {
            if (yPointer.isNull())
                return falseValue;
            DeoptimizeNode.deopt(InvalidateReprofile, NullCheckException);
            return falseValue;
        }

// if (!xProfileHub.isNull() && xProfileHubInline) {
// if (xPointer.isNull())
// return falseValue;
// GuardingNode anchorNode = SnippetAnchorNode.anchor();
// KlassPointer xKlassPointer = loadHubIntrinsic(PiNode.piCastNonNull(xPointer, anchorNode));
// if (xKlassPointer.equal(xProfileHub))
// return falseValue;
// DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
// return falseValue;
// }
//
// if (!yProfileHub.isNull() && yProfileHubInline) {
// if (yPointer.isNull())
// return falseValue;
// GuardingNode anchorNode = SnippetAnchorNode.anchor();
// KlassPointer yKlassPointer = loadHubIntrinsic(PiNode.piCastNonNull(yPointer, anchorNode));
// if (yKlassPointer.equal(yProfileHub))
// return falseValue;
// DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
// return falseValue;
// }

        if (!xInlineType) {
            if (xPointer.isNull())
                return falseValue;
            GuardingNode anchorNode = SnippetAnchorNode.anchor();
            x = PiNode.piCastNonNull(x, anchorNode);
            final Word xMark = loadWordFromObject(x, markOffset(INJECTED_VMCONFIG));
            if (xMark.and(inlineTypePattern(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG))) {
                return falseValue;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            return falseValue;
        }

        if (!yInlineType) {
            if (yPointer.isNull())
                return falseValue;
            GuardingNode anchorNode = SnippetAnchorNode.anchor();
            y = PiNode.piCastNonNull(y, anchorNode);
            final Word yMark = loadWordFromObject(y, markOffset(INJECTED_VMCONFIG));
            if (yMark.and(inlineTypePattern(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG))) {
                return falseValue;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            return falseValue;
        }

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
