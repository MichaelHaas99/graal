package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.NO_LOCATIONS;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypePattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.nodes.extended.HasIdentityNode.hasIdentity;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.ClassCastException;
import static jdk.vm.ci.meta.DeoptimizationReason.NullCheckException;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.api.replacements.Snippet.VarargsParameter;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Graph;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.FieldLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.extended.DelayedRawComparisonNode;
import jdk.graal.compiler.nodes.extended.FixedInlineTypeEqualityAnchorNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.ExplodeLoopNode;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.hotspot.ACmpDataAccessor;
import jdk.vm.ci.hotspot.SingleTypeEntry;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

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
        public void lower(FloatingNode objectEquals, LoweringTool tool) {
            StructuredGraph graph = objectEquals.graph();
            Graph.Mark newNodes = graph.getMark();
            super.lower(objectEquals, tool);
            for (Node n : graph.getNewNodes(newNodes)) {
                if (n instanceof DelayedRawComparisonNode delayedRawComparison) {
                    delayedRawComparison.lower(tool);
                }
            }
        }

        @Override
        protected SnippetTemplate.Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            ValueNode node = replacer.instanceOf;
            SnippetTemplate.Arguments args;
            ACmpDataAccessor profile = null;
            if (node instanceof ObjectEqualsNode objectEqualsNode) {
                profile = objectEqualsNode.getProfile();
                ValueNode x = objectEqualsNode.getX();
                ValueNode y = objectEqualsNode.getY();
                if (objectEqualsNode.getX() instanceof FixedInlineTypeEqualityAnchorNode xAnchorNode) {
                    x = xAnchorNode.object();
                }
                if (objectEqualsNode.getY() instanceof FixedInlineTypeEqualityAnchorNode yAnchorNode) {
                    y = yAnchorNode.object();
                }
                ResolvedJavaType type = null;
                boolean inlineComparison = true;
                long[] offsets = new long[0];
                JavaKind[] kinds = new JavaKind[0];
                Stamp[] stamps = new Stamp[0];
                LocationIdentity[] identities = new LocationIdentity[0];
                if (StampTool.isInlineTypeOrNull(x, tool.getValhallaOptionsProvider())) {
                    AbstractObjectStamp stamp = (AbstractObjectStamp) x.stamp(NodeView.DEFAULT);
                    type = stamp.type();
                } else if (StampTool.isInlineTypeOrNull(y, tool.getValhallaOptionsProvider())) {
                    AbstractObjectStamp stamp = (AbstractObjectStamp) y.stamp(NodeView.DEFAULT);
                    type = stamp.type();
                } else {
                    inlineComparison = false;
                }
                if (type != null) {
                    ResolvedJavaField[] fields = type.getInstanceFields(true);
                    offsets = new long[fields.length];
                    kinds = new JavaKind[fields.length];
                    identities = new LocationIdentity[fields.length];
                    stamps = new Stamp[fields.length];

                    for (int i = 0; i < fields.length; i++) {
                        offsets[i] = fields[i].getOffset();
                        kinds[i] = fields[i].getJavaKind();
                        if (fields[i].getType().equals(type)) {
                            // don't inline recursive comparisons
                            inlineComparison = false;
                            break;
                        } else if (fields[i].getJavaKind().isPrimitive() || fields[i].getJavaKind().isObject()) {
                            offsets[i] = fields[i].getOffset();
                            kinds[i] = fields[i].getJavaKind();
                            // inline type objects are immutable
                            identities[i] = new FieldLocationIdentity(fields[i], true);
                            stamps[i] = StampFactory.forDeclaredType(node.graph().getAssumptions(), fields[i].getType(), false).getTrustedStamp();

                        } else {
                            inlineComparison = false;
                            break;
                        }

                    }
                }

                if (profile != null) {
                    args = new SnippetTemplate.Arguments(objectEqualsSnippetWithProfile,
                                    node.graph().getGuardsStage(), tool.getLoweringStage());
                } else {
                    args = new SnippetTemplate.Arguments(objectEqualsSnippet, node.graph().getGuardsStage(),
                                    tool.getLoweringStage());
                }
                args.add("x", x);
                args.add("y", y);
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                args.add("trace", isTracingEnabledForMethod(node.graph()));
                args.add("inlineComparison", inlineComparison);
                args.addVarargs("offsets", long.class, StampFactory.forKind(JavaKind.Long), offsets);
                args.addVarargs("kinds", JavaKind.class, StampFactory.forKind(JavaKind.Object), kinds);
                args.addVarargs("identities", LocationIdentity.class, StampFactory.forKind(JavaKind.Object), identities);
                args.addVarargs("stamps", Stamp.class, StampFactory.forKind(JavaKind.Object), stamps);

                if (profile != null) {
                    SingleTypeEntry leftEntry = profile.getLeft();
                    SingleTypeEntry rightEntry = profile.getRight();
                    boolean xAlwaysNullProfile = leftEntry.alwaysNull();
                    boolean xInlineTypeProfile = leftEntry.inlineType();
                    boolean yAlwaysNullProfile = rightEntry.alwaysNull();
                    boolean yInlineTypeProfile = rightEntry.inlineType();
                    args.add("xAlwaysNullProfile", xAlwaysNullProfile);
                    args.add("xInlineTypeProfile", xInlineTypeProfile);
                    args.add("yAlwaysNullProfile", yAlwaysNullProfile);
                    args.add("yInlineTypeProfile", yInlineTypeProfile);
                }
                // The access flag read is not done at compile time see
                // Avoid crash when performing unaligned reads (JDK-8275645)
                // Therefore pass it as additional input to avoid a read from a hub at runtime
                args.add("xIsInlineType", StampTool.isInlineTypeOrNull(x,
                                tool.getValhallaOptionsProvider()));
                args.add("yIsInlineType", StampTool.isInlineTypeOrNull(y,
                                tool.getValhallaOptionsProvider()));

                return args;
            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer); // ExcludeFromJacocoGeneratedReport
            }
        }

        private static boolean isTracingEnabledForMethod(StructuredGraph graph) {
            String filter = HotspotSnippetsOptions.TraceSubstitutabilityCheckMethodFilter.getValue(graph.getOptions());
            if (filter == null) {
                return false;
            } else {
                if (filter.length() == 0) {
                    return true;
                }
                if (graph.method() == null) {
                    return false;
                }
                return (graph.method().format("%H.%n").contains(filter));
            }
        }
    }

    @Snippet
    protected static Object objectEquals(Object x, Object y, Object trueValue, Object falseValue, @ConstantParameter boolean trace,
                    @ConstantParameter boolean inlineComparison,
                    @VarargsParameter long[] offsets,
                    @VarargsParameter JavaKind[] kinds,
                    @VarargsParameter LocationIdentity[] identities, @VarargsParameter Stamp[] stamps, @ConstantParameter boolean xIsInlineType, @ConstantParameter boolean yIsInlineType) {
        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        trace(trace, "apply pointer comparison");
        if (xPointer.equal(yPointer)) {
            return trueValue;
        }

        return commonPart(x, y, trueValue, falseValue, xPointer, yPointer, trace, inlineComparison, offsets, kinds, identities, stamps, xIsInlineType, yIsInlineType);
    }

    @Snippet
    protected static Object objectEqualsWithProfile(Object x, Object y, Object trueValue, Object falseValue, @ConstantParameter boolean trace,
                    @ConstantParameter boolean inlineComparison,
                    @VarargsParameter long[] offsets,
                    @VarargsParameter JavaKind[] kinds,
                    @VarargsParameter LocationIdentity[] identities, @VarargsParameter Stamp[] stamps,
                    @ConstantParameter boolean xAlwaysNullProfile,
                    @ConstantParameter boolean xInlineTypeProfile, @ConstantParameter boolean yAlwaysNullProfile,
                    @ConstantParameter boolean yInlineTypeProfile, @ConstantParameter boolean xIsInlineType, @ConstantParameter boolean yIsInlineType) {

        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        trace(trace, "apply pointer comparison");
        if (xPointer.equal(yPointer)) {
            return trueValue;
        }

        if (xAlwaysNullProfile) {
            if (xPointer.isNull()) {
                return falseValue;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, NullCheckException);
            return falseValue;
        }

        if (yAlwaysNullProfile) {
            if (yPointer.isNull()) {
                return falseValue;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, NullCheckException);
            return falseValue;
        }

        if (!xInlineTypeProfile) {
            if (xPointer.isNull()) {
                return falseValue;
            }
            GuardingNode anchorNode = SnippetAnchorNode.anchor();
            x = PiNode.piCastNonNull(x, anchorNode);
            final Word xMark = loadWordFromObject(x, markOffset(INJECTED_VMCONFIG));
            if (xMark.and(inlineTypePattern(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG))) {
                return falseValue;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            return falseValue;
        }

        if (!yInlineTypeProfile) {
            if (yPointer.isNull()) {
                return falseValue;
            }
            GuardingNode anchorNode = SnippetAnchorNode.anchor();
            y = PiNode.piCastNonNull(y, anchorNode);
            final Word yMark = loadWordFromObject(y, markOffset(INJECTED_VMCONFIG));
            if (yMark.and(inlineTypePattern(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG))) {
                return falseValue;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            return falseValue;
        }

        return commonPart(x, y, trueValue, falseValue, xPointer, yPointer, trace, inlineComparison, offsets, kinds, identities, stamps, xIsInlineType, yIsInlineType);
    }

    private static Object commonPart(Object x, Object y, Object trueValue, Object falseValue, Word xPointer, Word yPointer, boolean trace,
                    boolean inlineComparison,
                    long[] offsets,
                    JavaKind[] kinds,
                    LocationIdentity[] identities, Stamp[] stamps, boolean xIsInlineType, boolean yIsInlineType) {
        trace(trace, "check both operands against null");
        if (xPointer.isNull() || yPointer.isNull())
            return falseValue;
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        x = PiNode.piCastNonNull(x, anchorNode);
        y = PiNode.piCastNonNull(y, anchorNode);

        trace(trace, "apply type comparison");
        KlassPointer xHub = loadHub(x);
        KlassPointer yHub = loadHub(y);

        trace(trace, "check both operands for inline type bit");
        if (!xIsInlineType && hasIdentity(x) || !yIsInlineType && hasIdentity(y)) {
            return falseValue;
        }

        if (xHub.notEqual(yHub)) {
            return falseValue;
        }

        // inline field comparison
        if (inlineComparison) {
            trace(trace, "inline comparison");
            ExplodeLoopNode.explodeLoop();
            for (int i = 0; i < offsets.length; i++) {
                JavaKind kind = kinds[i];
                if (!DelayedRawComparisonNode.load(x, y, offsets[i], kind, identities[i], stamps[i])) {
                    return falseValue;
                }

            }
            return trueValue;
        }

        trace(trace, "call to library for substitutability check");

        return substitutabilityCheckStubC(SUBSTITUTABILITYCHECK, x, y) ? trueValue : falseValue;
    }

    public static final HotSpotForeignCallDescriptor SUBSTITUTABILITYCHECK = new HotSpotForeignCallDescriptor(LEAF, NO_SIDE_EFFECT, NO_LOCATIONS, "substitutabilityCheck", boolean.class, Object.class,
                    Object.class);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native boolean substitutabilityCheckStubC(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object x, Object y);

    private static void trace(boolean enabled, String text) {
        if (enabled) {
            Log.println(text);
        }
    }
}
