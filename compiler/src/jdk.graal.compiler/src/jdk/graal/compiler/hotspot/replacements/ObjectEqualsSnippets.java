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

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.api.replacements.Snippet.VarargsParameter;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.extended.FixedInlineTypeEqualityAnchorNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.ExplodeLoopNode;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.hotspot.ACmpDataAccessor;
import jdk.vm.ci.hotspot.SingleTypeEntry;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
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
                int[] offsets = new int[0];
                JavaKind[] kinds = new JavaKind[0];
                if (x.stamp(NodeView.DEFAULT).isInlineType()) {
                    AbstractObjectStamp stamp = (AbstractObjectStamp) x.stamp(NodeView.DEFAULT);
                    type = stamp.type();
                } else if (y.stamp(NodeView.DEFAULT).isInlineType()) {
                    AbstractObjectStamp stamp = (AbstractObjectStamp) y.stamp(NodeView.DEFAULT);
                    type = stamp.type();
                } else {
                    inlineComparison = false;
                }
                if (type != null) {
                    ResolvedJavaField[] fields = type.getInstanceFields(true);
                    offsets = new int[fields.length];
                    kinds = new JavaKind[fields.length];

                    for (int i = 0; i < fields.length; i++) {
                        offsets[i] = fields[i].getOffset();
                        kinds[i] = fields[i].getJavaKind();
                        if (fields[i].getJavaKind().isPrimitive() || fields[i].getJavaKind().isObject()) {
                            offsets[i] = fields[i].getOffset();
                            kinds[i] = fields[i].getJavaKind();
                        } else {
                            inlineComparison = false;
                            break;
                        }

                    }
                }

                if (profile != null) {
                    args = new SnippetTemplate.Arguments(objectEqualsSnippetWithProfile,
                                    node.graph().getGuardsStage(), tool.getLoweringStage());
                    args.add("x", x);
                    args.add("y", y);
                    args.add("trueValue", replacer.trueValue);
                    args.add("falseValue", replacer.falseValue);
                    args.addConst("trace", isTracingEnabledForMethod(node.graph()));
                    args.addConst("inlineComparison", inlineComparison);
                    args.addVarargs("offsets", int.class, StampFactory.forKind(JavaKind.Int), offsets);
                    args.addVarargs("kinds", JavaKind.class, StampFactory.forKind(JavaKind.Object), kinds);
                    SingleTypeEntry leftEntry = profile.getLeft();
                    SingleTypeEntry rightEntry = profile.getRight();
                    boolean xAlwaysNull = leftEntry.alwaysNull();
                    boolean xInlineType = leftEntry.inlineType();
                    boolean yAlwaysNull = rightEntry.alwaysNull();
                    boolean yInlineType = rightEntry.inlineType();
                    // HotSpotResolvedObjectType test = leftEntry.getValidType();
                    // HotSpotResolvedObjectType test2 = rightEntry.getValidType();
                    args.addConst("xAlwaysNull", xAlwaysNull);
                    args.addConst("xInlineType", xInlineType);
                    args.addConst("yAlwaysNull", yAlwaysNull);
                    args.addConst("yInlineType", yInlineType);

                } else {
                    args = new SnippetTemplate.Arguments(objectEqualsSnippet, node.graph().getGuardsStage(),
                                    tool.getLoweringStage());
                    args.add("x", x);
                    args.add("y", y);
                    args.add("trueValue", replacer.trueValue);
                    args.add("falseValue", replacer.falseValue);
                    args.addConst("trace", isTracingEnabledForMethod(node.graph()));
                    args.addConst("inlineComparison", inlineComparison);
                    args.addVarargs("offsets", int.class, StampFactory.forKind(JavaKind.Int), offsets);
                    args.addVarargs("kinds", JavaKind.class, StampFactory.forKind(JavaKind.Object), kinds);
                }

                return args;
            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer); // ExcludeFromJacocoGeneratedReport
            }
        }

        private static boolean noInlineType(JavaType type) {
            return !StampFactory.forDeclaredType(new Assumptions(), type, false).getTrustedStamp().canBeInlineType();
        }

        private static boolean canBeInlineType(JavaType type) {
            return !noInlineType(type);
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
                    @VarargsParameter int[] offsets,
                    @VarargsParameter JavaKind[] kinds) {
        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        trace(trace, "apply pointer comparison");
        if (xPointer.equal(yPointer))
            return trueValue;

        return commonPart(x, y, trueValue, falseValue, xPointer, yPointer, trace, inlineComparison, offsets, kinds);
    }

    @Snippet
    protected static Object objectEqualsWithProfile(Object x, Object y, Object trueValue, Object falseValue, @ConstantParameter boolean trace,
                    @ConstantParameter boolean inlineComparison,
                    @VarargsParameter int[] offsets,
                    @VarargsParameter JavaKind[] kinds,
                    @ConstantParameter boolean xAlwaysNull,
                    @ConstantParameter boolean xInlineType, @ConstantParameter boolean yAlwaysNull,
                    @ConstantParameter boolean yInlineType) {

        Word xPointer = Word.objectToTrackedPointer(x);
        Word yPointer = Word.objectToTrackedPointer(y);

        trace(trace, "apply pointer comparison");
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

        return commonPart(x, y, trueValue, falseValue, xPointer, yPointer, trace, inlineComparison, offsets, kinds);
    }

    private static Object commonPart(Object x, Object y, Object trueValue, Object falseValue, Word xPointer, Word yPointer, boolean trace,
                    boolean inlineComparison,
                    int[] offsets,
                    JavaKind[] kinds) {
        trace(trace, "check both operands against null");
        if (xPointer.isNull() || yPointer.isNull())
            return falseValue;
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        x = PiNode.piCastNonNull(x, anchorNode);
        y = PiNode.piCastNonNull(y, anchorNode);

        final Word xMark = loadWordFromObject(x, markOffset(INJECTED_VMCONFIG));
        final Word yMark = loadWordFromObject(y, markOffset(INJECTED_VMCONFIG));

        trace(trace, "check both operands for inline type bit");
        if (xMark.and(yMark).and(inlineTypeMaskInPlace(INJECTED_VMCONFIG)).notEqual(inlineTypePattern(INJECTED_VMCONFIG)))
            return falseValue;

        trace(trace, "apply type comparison");
        KlassPointer xHub = loadHub(x);
        KlassPointer yHub = loadHub(y);
        if (xHub.notEqual(yHub))
            return falseValue;

        // inline field comparison
        if (inlineComparison) {
            trace(trace, "inline type comparison");
            ExplodeLoopNode.explodeLoop();
            for (int i = 0; i < offsets.length; i++) {
                JavaKind kind = kinds[i];
                Object xLoad = null;
                Object yLoad = null;
                if (kind == JavaKind.Boolean) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Char, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Char, LocationIdentity.any());
                } else if (kind == JavaKind.Byte) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Byte, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Byte, LocationIdentity.any());
                } else if (kind == JavaKind.Char) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Char, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Char, LocationIdentity.any());
                } else if (kind == JavaKind.Short) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Short, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Short, LocationIdentity.any());
                } else if (kind == JavaKind.Int) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Int, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Int, LocationIdentity.any());
                } else if (kind == JavaKind.Long) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Long, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Long, LocationIdentity.any());
                } else if (kind == JavaKind.Float) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Float, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Float, LocationIdentity.any());
                } else if (kind == JavaKind.Double) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Double, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Double, LocationIdentity.any());
                } else if (kind == JavaKind.Object) {
                    xLoad = RawLoadNode.load(x, offsets[i], JavaKind.Object, LocationIdentity.any());
                    yLoad = RawLoadNode.load(y, offsets[i], JavaKind.Object, LocationIdentity.any());
                }
                if (xLoad != yLoad)
                    return falseValue;
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
