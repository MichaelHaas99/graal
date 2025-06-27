package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.SAFEPOINT;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypePattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.nodes.extended.HasIdentityNode.hasIdentity;
import static jdk.graal.compiler.nodes.memory.MemoryKill.NO_LOCATION;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.ClassCastException;
import static jdk.vm.ci.meta.DeoptimizationReason.NullCheckException;

import java.util.Arrays;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.api.replacements.Snippet.VarargsParameter;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
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
import jdk.graal.compiler.nodes.extended.DelayedRawComparisonNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.ValhallaObjectEqualsNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.ExplodeLoopNode;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

public class ObjectEqualsSnippets implements Snippets {

    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo objectEqualsSnippet;
        private final SnippetTemplate.SnippetInfo objectEqualsSnippetWithProfile;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            objectEqualsSnippet = snippet(providers, ObjectEqualsSnippets.class, "objectEquals");
            objectEqualsSnippetWithProfile = snippet(providers, ObjectEqualsSnippets.class, "objectEqualsWithProfile");
        }

        public void lower(ValhallaObjectEqualsNode node, LoweringTool tool) {
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            Object profile = node.getProfile();
            ValueNode x = node.getX();
            ValueNode y = node.getY();
            ResolvedJavaType type = null;
            boolean inlineComparison = true;
            long[] offsets = new long[0];
            JavaKind[] kinds = new JavaKind[0];
            Stamp[] stamps = new Stamp[0];
            LocationIdentity[] identities = new LocationIdentity[0];
            if (StampTool.isNullableInlineType(x, tool.getValhallaOptionsProvider())) {
                AbstractObjectStamp stamp = (AbstractObjectStamp) x.stamp(NodeView.DEFAULT);
                type = stamp.type();
            } else if (StampTool.isNullableInlineType(y, tool.getValhallaOptionsProvider())) {
                AbstractObjectStamp stamp = (AbstractObjectStamp) y.stamp(NodeView.DEFAULT);
                type = stamp.type();
            } else {
                inlineComparison = false;
            }
            ResolvedJavaField[] fields = type == null ? null : type.getInstanceFields(true);

            if (type != null && !InlineTypeUtil.isCircularInlineType(type) &&
                            Arrays.stream(fields).allMatch(f -> f.getJavaKind().isPrimitive() || f.getJavaKind().isObject())) {

                offsets = new long[fields.length];
                kinds = new JavaKind[fields.length];
                identities = new LocationIdentity[fields.length];
                stamps = new Stamp[fields.length];

                for (int i = 0; i < fields.length; i++) {
                    ResolvedJavaField field = fields[i];
                    offsets[i] = field.getOffset();
                    kinds[i] = field.getJavaKind();
                    identities[i] = /* LocationIdentity.ANY_LOCATION; */ new FieldLocationIdentity(field, false);
                    stamps[i] = StampFactory.forDeclaredType(node.graph().getAssumptions(), field.getType(), false).getTrustedStamp();
                }
            } else {
                inlineComparison = false;
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
            args.add("trace", isTracingEnabledForMethod(node.graph()));
            args.add("inlineComparison", inlineComparison && inlineSubstitutabilityCheck(node.graph()));
            args.addVarargs("offsets", long.class, StampFactory.forKind(JavaKind.Long), offsets);
            args.addVarargs("kinds", JavaKind.class, StampFactory.forKind(JavaKind.Object), kinds);
            args.addVarargs("identities", LocationIdentity.class, StampFactory.forKind(JavaKind.Object), identities);
            args.addVarargs("stamps", Stamp.class, StampFactory.forKind(JavaKind.Object), stamps);

            if (profile != null) {
                Object leftEntry = GraalValhallaServices.getLeft(profile);
                Object rightEntry = GraalValhallaServices.getRight(profile);
                boolean xAlwaysNullProfile = GraalValhallaServices.getAlwaysNull(leftEntry);
                boolean xInlineTypeProfile = GraalValhallaServices.getInlineType(leftEntry);
                boolean yAlwaysNullProfile = GraalValhallaServices.getAlwaysNull(rightEntry);
                boolean yInlineTypeProfile = GraalValhallaServices.getInlineType(rightEntry);
                args.add("xAlwaysNullProfile", xAlwaysNullProfile);
                args.add("xInlineTypeProfile", xInlineTypeProfile);
                args.add("yAlwaysNullProfile", yAlwaysNullProfile);
                args.add("yInlineTypeProfile", yInlineTypeProfile);
            }
            // The access flag read is not done at compile time see
            // Avoid crash when performing unaligned reads (JDK-8275645)
            // Therefore pass it as additional input to avoid a read from a hub at runtime
            args.add("xIsInlineType", StampTool.isNullableInlineType(x,
                            tool.getValhallaOptionsProvider()));
            args.add("yIsInlineType", StampTool.isNullableInlineType(y,
                            tool.getValhallaOptionsProvider()));

            Graph.Mark newNodes = graph.getMark();
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
            for (Node n : graph.getNewNodes(newNodes)) {
                if (n instanceof DelayedRawComparisonNode delayedRawComparison) {
                    delayedRawComparison.lower(tool);
                }
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

        private static boolean inlineSubstitutabilityCheck(StructuredGraph graph) {
            return HotspotSnippetsOptions.InlineSubstitutabilityCheck.getValue(graph.getOptions());
        }
    }

    @Snippet
    protected static boolean objectEquals(Object x, Object y, @ConstantParameter boolean trace,
                    @ConstantParameter boolean inlineComparison,
                    @VarargsParameter long[] offsets,
                    @VarargsParameter JavaKind[] kinds,
                    @VarargsParameter LocationIdentity[] identities, @VarargsParameter Stamp[] stamps, @ConstantParameter boolean xIsInlineType, @ConstantParameter boolean yIsInlineType) {
        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        trace(trace, "apply pointer comparison");
        if (xPointer.equal(yPointer)) {
            return true;
        }

        return commonPart(x, y, xPointer, yPointer, trace, inlineComparison, offsets, kinds, identities, stamps, xIsInlineType, yIsInlineType);
    }

    @Snippet
    protected static boolean objectEqualsWithProfile(Object x, Object y, @ConstantParameter boolean trace,
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
            return true;
        }

        if (xAlwaysNullProfile) {
            if (xPointer.isNull()) {
                return false;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, NullCheckException);
            return false;
        }

        if (yAlwaysNullProfile) {
            if (yPointer.isNull()) {
                return false;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, NullCheckException);
            return false;
        }

        if (!xInlineTypeProfile) {
            if (xPointer.isNull()) {
                return false;
            }
            GuardingNode anchorNode = SnippetAnchorNode.anchor();
            Object nonNullX = PiNode.piCastNonNull(x, anchorNode);
            final Word xMark = loadWordFromObject(nonNullX, markOffset(INJECTED_VMCONFIG));
            if (xMark.and(inlineTypePattern(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG))) {
                return false;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            return false;
        }

        if (!yInlineTypeProfile) {
            if (yPointer.isNull()) {
                return false;
            }
            GuardingNode anchorNode = SnippetAnchorNode.anchor();
            Object nonNullY = PiNode.piCastNonNull(y, anchorNode);
            final Word yMark = loadWordFromObject(nonNullY, markOffset(INJECTED_VMCONFIG));
            if (yMark.and(inlineTypePattern(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG))) {
                return false;
            }
            DeoptimizeNode.deopt(InvalidateReprofile, ClassCastException);
            return false;
        }

        return commonPart(x, y, xPointer, yPointer, trace, inlineComparison, offsets, kinds, identities, stamps, xIsInlineType, yIsInlineType);
    }

    private static boolean commonPart(Object x, Object y, Word xPointer, Word yPointer, boolean trace,
                    boolean inlineComparison,
                    long[] offsets,
                    JavaKind[] kinds,
                    LocationIdentity[] identities, Stamp[] stamps, boolean xIsInlineType, boolean yIsInlineType) {
        trace(trace, "check both operands against null");
        if (xPointer.isNull() || yPointer.isNull()) {
            return false;
        }

        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        Object nonNullX = PiNode.piCastNonNull(x, anchorNode);
        Object nonNullY = PiNode.piCastNonNull(y, anchorNode);

        trace(trace, "check both operands for inline type bit");
        if (!xIsInlineType && hasIdentity(nonNullX) || !yIsInlineType && hasIdentity(nonNullY)) {
            return false;
        }

        trace(trace, "apply hub comparison");
        KlassPointer xHub = loadHub(nonNullX);
        KlassPointer yHub = loadHub(nonNullY);

        if (xHub.notEqual(yHub)) {
            return false;
        }

        if (inlineComparison) {
            // inline field comparison
            trace(trace, "inline substitutability check");
            ExplodeLoopNode.explodeLoop();
            for (int i = 0; i < offsets.length; i++) {
                JavaKind kind = kinds[i];
                if (!DelayedRawComparisonNode.compare(nonNullX, nonNullY, offsets[i], kind, identities[i], stamps[i])) {
                    return false;
                }

            }
            return true;
        } else {
            // do runtime call
            trace(trace, "call to library for substitutability check");
            return substitutabilityCheckStubC(SUBSTITUTABILITY_CHECK, nonNullX, nonNullY);
        }

    }

    // TODO: avoid a foreign call and emit an invoke see parse2.cpp Parse::do_acmp
    public static final HotSpotForeignCallDescriptor SUBSTITUTABILITY_CHECK = new HotSpotForeignCallDescriptor(SAFEPOINT, HAS_SIDE_EFFECT, NO_LOCATION, "substitutabilityCheck",
                    boolean.class, Object.class,
                    Object.class);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native boolean substitutabilityCheckStubC(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object x, Object y);

    private static void trace(boolean enabled, String text) {
        if (enabled) {
            Log.println(text);
        }
    }
}
