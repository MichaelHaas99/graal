package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.GraalCompiler.emitFrontEnd;
import static jdk.graal.compiler.core.common.CompilationIdentifier.INVALID_COMPILATION_ID;
import static jdk.graal.compiler.debug.DebugOptions.DebugValhallaEntryPoints;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.core.target.Backend;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.HotSpotEntryPointFrameMap;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.stubs.HotSpotGraphKit;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.asm.CompilationResultBuilderFactory;
import jdk.graal.compiler.lir.framemap.FrameMap;
import jdk.graal.compiler.lir.phases.LIRPhase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.lir.phases.PostAllocationOptimizationPhase;
import jdk.graal.compiler.lir.profiling.MoveProfilingPhase;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DummyControlSinkNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.extended.ValueAnchorNode;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import jdk.graal.compiler.phases.Speculative;
import jdk.graal.compiler.phases.common.FloatingReadPhase;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.printer.GraalDebugHandlersFactory;
import jdk.graal.compiler.replacements.GraphKit;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.DefaultProfilingInfo;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.TriState;
import jdk.vm.ci.meta.Value;

/**
 * A utility class which helps with scalarization of value objects in entry points. In particular
 * for the {@code HotSpotMarkId#VERIFIED_INLINE_ENTRY} and
 * {@code HotSpotMarkId#VERIFIED_INLINE_ENTRY_RO}. To do so it first creates a graph which performs
 * the scalarization of certain parameters. After that the graph gets compiled and the machine code
 * can be extracted. The big advantage is that (compared to C2) we don't need to use the assembler
 * in the backend to create the entry point. So we also don't need to think about different GCs when
 * accessing memory or different underlying architectures. Also new field flattening features can be
 * implemented on a high-level and the implementation can be reused for the entry point.
 */
public class ValhallaEntryPointCreator {

    protected final OptionValues options;
    protected final HotSpotProviders providers;
    protected final ResolvedJavaMethod targetMethod;

    public ValhallaEntryPointCreator(OptionValues options, HotSpotProviders providers, ResolvedJavaMethod targetMethod) {
        this.options = options;
        this.providers = providers;
        this.targetMethod = targetMethod;
    }

    protected final StructuredGraph getGraph(DebugContext debug, boolean receiverOnly, Backend backend) {
        try {

            HotSpotGraphKit kit = new HotSpotGraphKit(debug, targetMethod, providers, providers.getGraphBuilderPlugins(), INVALID_COMPILATION_ID, null, false, true);
            StructuredGraph graph = kit.getGraph();
            graph.getGraphState().forceDisableFrameStateVerification();
            List<ValueNode> oldArguments = createParameters(kit, receiverOnly);
            FixedNode addBefore = kit.append(new ValueAnchorNode());

            JavaType[] parameterTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true).toArray(new JavaType[0]);
            CallingConvention callingConvention = backend.getCodeCache().getRegisterConfig().getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes,
                            backend);
            Value[] newValues = callingConvention.getArguments();
            for (int i = 0; i < newValues.length; i++) {
                Value dst = newValues[i];
                if (ValueUtil.isStackSlot(dst)) {
                    StackSlot slot = ValueUtil.asStackSlot(dst);
                    slot.setNewArgument(true);
                }
            }
            if (receiverOnly) {
                // For the RO entry point we only need to scalarize the receiver, the
                // rest is already scalarized
                kit.append(new MoveArgumentsToDestinationNode(oldArguments.subList(1, oldArguments.size()), targetMethod, List.of(newValues).subList(1, newValues.length)));
                addBefore = kit.append(new ValueAnchorNode());
                ValueNode[] scalarizedReceiver = InlineTypeUtil.createScalarizationCFG(addBefore, oldArguments.getFirst(), targetMethod.getDeclaringClass().getInstanceFields(true));
                kit.append(new MoveArgumentsToDestinationNode(List.of(scalarizedReceiver), targetMethod, List.of(newValues).subList(0, 1)));
            } else {
                int parameterLength = targetMethod.getSignature().getParameterCount(!targetMethod.isStatic());
                int index = newValues.length;
                // iterate in reverse order
                for (int signatureIndex = parameterLength - 1; signatureIndex >= 0; signatureIndex--) {
                    boolean nonNull = GraalValhallaServices.isParameterNullFree(targetMethod, signatureIndex, true);
                    if (GraalValhallaServices.isScalarizedParameter(targetMethod, signatureIndex, true)) {
                        // argument needs to be scalarized
                        List<ResolvedJavaField> fields = GraalValhallaServices.getScalarizedParameterFields(targetMethod, signatureIndex, true);
                        ValueNode[] scalarizedParam = InlineTypeUtil.createScalarizationCFG(addBefore, oldArguments.get(signatureIndex),
                                        fields, nonNull, !nonNull);

                        boolean leftIsNullBranch = false;
                        // try to duplicate into the branches to decrease life intervals
                        for (int i = 0; i < scalarizedParam.length; i++) {
                            ValueNode node = scalarizedParam[i];
                            boolean onlyDefine = false;
                                if (node instanceof ValuePhiNode phi) {
                                    for (int j = 0; j < 2; j++) {
                                        MergeNode merge = (MergeNode) phi.merge();
                                        EndNode end = merge.forwardEndAt(j);
                                        ValueNode phiValue = phi.valueAt(j);
                                        // check the non-null info which is the first phi, determine
                                        // the null branch
                                        if (i == 0 && j == 0 && phiValue == ConstantNode.forInt(0, graph)) {
                                            leftIsNullBranch = true;
                                        }
                                        if (i > 0 && (leftIsNullBranch && j == 0 || !leftIsNullBranch && j == 1)) {
                                            // currently processing the null branch
                                            if (phiValue.stamp(NodeView.DEFAULT).getStackKind() != JavaKind.Object) {
                                                /*
                                                 * Only oop slots need to be zeroed out to avoid
                                                 * problems with the gc. So effectively only the
                                                 * non-null info and oop fields will be set to zero
                                                 * on the null branch. We need to define the value
                                                 * though otherwise we get a problem with merging
                                                 * values with different types. E.g. the type of an
                                                 * original parameter is different to the value we
                                                 * replace the parameter value with. TODO: do we
                                                 * really want this?
                                                 */
                                                onlyDefine = true;
                                            }
                                        }
                                        MoveArgumentsToDestinationNode mover = graph.add(new MoveArgumentsToDestinationNode(onlyDefine ? List.of() : List.of(phiValue), targetMethod,
                                                        List.of(newValues).subList(index - scalarizedParam.length + i, index - scalarizedParam.length + i + 1)));
                                        if (phiValue instanceof FixedWithNextNode fixedNode) {
                                            graph.addAfterFixed(fixedNode, mover);
                                        } else {
                                            graph.addBeforeFixed(end, mover);
                                        }
                                    }
                                } else {
                                    kit.append(new MoveArgumentsToDestinationNode(List.of(node), targetMethod,
                                                    List.of(newValues[index - scalarizedParam.length + i])));
                                }
                        }
                        index -= scalarizedParam.length;
                        addBefore = kit.append(new ValueAnchorNode());
                    } else {
                        // no need to scalarize just take the old value
                        kit.append(new MoveArgumentsToDestinationNode(List.of(oldArguments.get(signatureIndex)), targetMethod, List.of(newValues[index - 1])));
                        index--;
                        addBefore = kit.append(new ValueAnchorNode());
                    }
                }
            }
            kit.append(new DummyControlSinkNode(newValues));
            debug.dump(DebugContext.VERBOSE_LEVEL, graph, "Verified inline entry point%s graph before compilation", receiverOnly ? " receiver only" : "");
            return graph;
        } catch (Exception e) {
            throw GraalError.shouldNotReachHere(e); // ExcludeFromJacocoGeneratedReport
        }
    }


    public static ValhallaEntryPointCreator create(OptionValues options, HotSpotProviders providers, ResolvedJavaMethod targetMethod) {
        return new ValhallaEntryPointCreator(options, providers, targetMethod);

    }

    public int emitCode(final Backend backend, boolean receiverOnly, CompilationResultBuilder builder) {
        try (DebugContext debug = openDebugContext(DebugContext.forCurrentThread())) {
            try (DebugContext.Scope d = debug.scope("Compiling entry point", providers.getCodeCache(), debugScopeContext())) {
                final StructuredGraph graph = getGraph(debug, receiverOnly, backend);
                return buildCompilationResult(debug, backend, graph, builder);
            } catch (Throwable e) {
                throw debug.handle(e);
            }
        }
    }

    private static class FrameMapSaver {
        FrameMap frameMap;
    }

    private int buildCompilationResult(DebugContext debug, final Backend backend, StructuredGraph graph, CompilationResultBuilder crb) {

        // Entry points cannot be recompiled so they cannot be compiled with assumptions
        assert graph.getAssumptions() == null;

        try (DebugContext.Scope s0 = debug.scope("EntryPointCompilation", graph, providers.getCodeCache())) {
            Suites suites = createSuites();
            emitFrontEnd(providers, backend, graph, providers.getSuites().getDefaultGraphBuilderSuite(), OptimisticOptimizations.ALL, DefaultProfilingInfo.get(TriState.UNKNOWN), suites);
            LIRSuites lirSuites = createLIRSuites();
            // we want to directly add the entry point code to the nmethod so use the asm of the crb
            // parameter instead of creating a new assembler
            final FrameMapSaver frameMapSaver = new FrameMapSaver();
            CompilationResultBuilderFactory entryPointFactory = (providers, frameMap, asm, dataBuilder, frameContext, options, debug1, compilationResult, nullRegister,
                            lir) -> {
                frameMapSaver.frameMap = frameMap;
                return CompilationResultBuilderFactory.Default.createBuilder(providers, frameMap, crb.asm, dataBuilder, frameContext, options, debug1, compilationResult, nullRegister,
                                lir);
            };
            backend.emitBackEnd(graph, null, targetMethod, crb.compilationResult, entryPointFactory, null, null, lirSuites);
            return ((HotSpotEntryPointFrameMap) frameMapSaver.frameMap).getStackIncrement();
        } catch (Throwable e) {
            throw debug.handle(e);
        }
    }

    protected Suites createSuites() {
        Suites defaultSuites = providers.getSuites().getDefaultSuites(options, providers.getLowerer().getTarget().arch).copy();

        defaultSuites.getMidTier().removeSubTypePhases(Speculative.class);
        defaultSuites.getMidTier().removeSubTypePhases(FloatingReadPhase.class);
        defaultSuites.getLowTier().removeSubTypePhases(Speculative.class);

        return new Suites(defaultSuites.getHighTier(), defaultSuites.getMidTier(), defaultSuites.getLowTier());
    }

    protected LIRSuites createLIRSuites() {
        LIRSuites lirSuites = new LIRSuites(providers.getSuites().getDefaultLIRSuites(options));
        ListIterator<LIRPhase<PostAllocationOptimizationPhase.PostAllocationOptimizationContext>> moveProfiling = lirSuites.getPostAllocationOptimizationStage().findPhase(MoveProfilingPhase.class);
        if (moveProfiling != null) {
            moveProfiling.remove();
        }
        return lirSuites;
    }

    private DebugContext openDebugContext(DebugContext outer) {
        if (DebugValhallaEntryPoints.getValue(options)) {
            DebugContext.Description description = new DebugContext.Description(targetMethod, "Entry_Point_" + targetMethod.getName());
            GraalDebugHandlersFactory factory = new GraalDebugHandlersFactory(providers.getSnippetReflection());
            return new DebugContext.Builder(options, factory).globalMetrics(outer.getGlobalMetrics()).description(description).build();
        }
        return DebugContext.disabled(options);
    }

    protected final Object debugScopeContext() {
        return targetMethod;
    }

    protected List<ValueNode> createParameters(GraphKit kit, boolean receiverOnly) {
        List<ValueNode> parameters = new ArrayList<>();

        // the types of the current arguments
        List<JavaType> oldTypes;
        if (receiverOnly) {
            // the receiver is in non scalarized form
            oldTypes = new ArrayList<>(GraalValhallaServices.getScalarizedParameters(targetMethod, false));

        } else {
            oldTypes = new ArrayList<>();
            if (!targetMethod.isStatic()) {
                // add receiver type
                oldTypes.add(targetMethod.getDeclaringClass());
            }
            // add the other types
            for (int i = 0; i < targetMethod.getSignature().getParameterCount(false); i++) {
                oldTypes.add(targetMethod.getSignature().getParameterType(i, targetMethod.getDeclaringClass()));
            }
        }

        // create a parameter node for each current argument
        for (int i = 0; i < oldTypes.size(); i++) {
            JavaType type = oldTypes.get(i);
            boolean nonNull = GraalValhallaServices.isParameterNullFree(targetMethod, i, true);
            ;
            StampPair stamp = StampFactory.forDeclaredType(kit.getGraph().getAssumptions(), type, nonNull);
            ParameterNode param = kit.unique(new ParameterNode(i, stamp));
            parameters.add(param);
        }
        kit.getGraph().setEntryPointCFG(true);
        kit.getGraph().setEntryPointOriginalParameterTypes(oldTypes);
        kit.getGraph().setReceiverOnly(receiverOnly);
        return parameters;
    }
}
