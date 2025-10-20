package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.GraalCompiler.emitFrontEnd;
import static jdk.graal.compiler.core.common.CompilationIdentifier.INVALID_COMPILATION_ID;
import static jdk.graal.compiler.core.common.GraalOptions.RegisterPressure;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.core.phases.EconomyHighTier;
import jdk.graal.compiler.core.target.Backend;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.DebugOptions;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.HotSpotMarkId;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.stubs.HotSpotGraphKit;
import jdk.graal.compiler.lir.asm.CompilationResultBuilderFactory;
import jdk.graal.compiler.lir.phases.LIRPhase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.lir.phases.PostAllocationOptimizationPhase;
import jdk.graal.compiler.lir.profiling.MoveProfilingPhase;
import jdk.graal.compiler.nodes.DummyControlSinkNode;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.Speculative;
import jdk.graal.compiler.phases.common.DisableOverflownCountedLoopsPhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.printer.GraalDebugHandlersFactory;
import jdk.graal.compiler.replacements.GraphKit;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.meta.DefaultProfilingInfo;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.TriState;

public class ScalarizationEntryPoint {

    protected final OptionValues options;
    protected final HotSpotProviders providers;
    protected final ResolvedJavaMethod targetMethod;

    public ScalarizationEntryPoint(OptionValues options, HotSpotProviders providers, ResolvedJavaMethod targetMethod) {
        this.options = new OptionValues(options, GraalOptions.TraceInlining, GraalOptions.TraceInliningForStubsAndSnippets.getValue(options), RegisterPressure, null,
                        DebugOptions.OptimizationLog, null);
        this.providers = providers;
        this.targetMethod = targetMethod;
    }

    protected final StructuredGraph getGraph(DebugContext debug, HotSpotMarkId markId) {
        try {
            HotSpotGraphKit kit = new HotSpotGraphKit(debug, targetMethod, providers, providers.getGraphBuilderPlugins(), INVALID_COMPILATION_ID, null, false, true);
            StructuredGraph graph = kit.getGraph();
            graph.getGraphState().forceDisableFrameStateVerification();
            List<ValueNode> parameters = createParameters(kit, markId);
            ParametersAssignNode parameterAssignNode = kit.append(new ParametersAssignNode(new ValueNode[0], new ValueNode[0], targetMethod));
            List<ValueNode> scalarized = new ArrayList<>();
            if (markId == HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO) {
                ValueNode[] scalarizedReceiver = InlineTypeUtil.createScalarizationCFG(parameterAssignNode, parameters.get(0), targetMethod.getDeclaringClass().getInstanceFields(true));
                scalarized.addAll(List.of(scalarizedReceiver));
                scalarized.addAll(parameters.subList(1, parameters.size()));
            } else {
                int parameterLength = targetMethod.getSignature().getParameterCount(!targetMethod.isStatic());
                for (int signatureIndex = 0; signatureIndex < parameterLength; signatureIndex++) {
                    boolean isNullFree = GraalValhallaServices.isParameterNullFree(targetMethod, signatureIndex, true);
                    ValueNode[] scalarizedParam = InlineTypeUtil.createScalarizationCFG(parameterAssignNode, parameters.get(signatureIndex),
                                    GraalValhallaServices.getScalarizedParameterFields(targetMethod, signatureIndex, true), isNullFree, !isNullFree);
                    scalarized.addAll(List.of(scalarizedParam));
                }
            }
            parameterAssignNode.oldParams.addAll(parameters);
            parameterAssignNode.newParams.addAll(scalarized);
            kit.append(new DummyControlSinkNode());
            debug.dump(DebugContext.VERBOSE_LEVEL, graph, "Entry point graph before compilation");
            return graph;
        } catch (Exception e) {
            throw GraalError.shouldNotReachHere(e); // ExcludeFromJacocoGeneratedReport
        }
    }

    public CompilationResult getCode(final Backend backend, HotSpotMarkId markId) {
        try (DebugContext debug = openDebugContext(DebugContext.forCurrentThread())) {
            try (DebugContext.Scope d = debug.scope("CompilingStub", providers.getCodeCache(), debugScopeContext())) {
                CompilationIdentifier compilationId = INVALID_COMPILATION_ID;
                final StructuredGraph graph = getGraph(debug, markId);
                CompilationResult compResult = buildCompilationResult(debug, backend, graph, compilationId);
                try (DebugContext.Scope s = debug.scope("CodeInstall", compResult);
                                DebugContext.Activation a = debug.activate()) {
                    return compResult;
                } catch (Throwable e) {
                    throw debug.handle(e);
                }
            } catch (Throwable e) {
                throw debug.handle(e);
            }
        }
    }

    private CompilationResult buildCompilationResult(DebugContext debug, final Backend backend, StructuredGraph graph, CompilationIdentifier compilationId) {
        CompilationResult compResult = new CompilationResult(compilationId, toString());

        // Stubs cannot be recompiled so they cannot be compiled with assumptions
        assert graph.getAssumptions() == null;

        try (DebugContext.Scope s0 = debug.scope("EntryPointCompilation", graph, providers.getCodeCache())) {
            Suites suites = createSuites();
            emitFrontEnd(providers, backend, graph, providers.getSuites().getDefaultGraphBuilderSuite(), OptimisticOptimizations.ALL, DefaultProfilingInfo.get(TriState.UNKNOWN), suites);
            LIRSuites lirSuites = createLIRSuites();
            backend.emitBackEnd(graph, null, targetMethod, compResult, CompilationResultBuilderFactory.Default, null, null, lirSuites);
        } catch (Throwable e) {
            throw debug.handle(e);
        }
        return compResult;
    }

    protected Suites createSuites() {
        Suites defaultSuites = providers.getSuites().getDefaultSuites(options, providers.getLowerer().getTarget().arch).copy();

        PhaseSuite<HighTierContext> emptyHighTier = new PhaseSuite<>();
        emptyHighTier.appendPhase(new DisableOverflownCountedLoopsPhase());
        emptyHighTier.appendPhase(new EconomyHighTier());

        defaultSuites.getMidTier().removeSubTypePhases(Speculative.class);
        defaultSuites.getLowTier().removeSubTypePhases(Speculative.class);

        return new Suites(emptyHighTier, defaultSuites.getMidTier(), defaultSuites.getLowTier());
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
        if (true) {
            DebugContext.Description description = new DebugContext.Description(targetMethod, "Entry_Point_" + targetMethod.getName());
            GraalDebugHandlersFactory factory = new GraalDebugHandlersFactory(providers.getSnippetReflection());
            return new DebugContext.Builder(options, factory).globalMetrics(outer.getGlobalMetrics()).description(description).build();
        }
        return DebugContext.disabled(options);
    }

    protected final Object debugScopeContext() {
        return targetMethod;
    }

    protected List<ValueNode> createParameters(GraphKit kit, HotSpotMarkId markId) {
        List<ValueNode> params = new ArrayList<>();
        List<JavaType> targetTypes = GraalValhallaServices.getScalarizedParameters(targetMethod, true);
        List<JavaType> types;
        if (markId == HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO) {
            types = new ArrayList<>(GraalValhallaServices.getScalarizedParameters(targetMethod, false));

        } else {
            types = new ArrayList<>();
            if (!targetMethod.isStatic()) {
                types.add(targetMethod.getDeclaringClass());
            }
            for (int i = 0; i < targetMethod.getSignature().getParameterCount(false); i++) {
                types.add(targetMethod.getSignature().getParameterType(i, targetMethod.getDeclaringClass()));
            }
        }

        for (int i = 0; i < types.size(); i++) {
            JavaType type = types.get(i);
            StampPair stamp = StampFactory.forDeclaredType(kit.getGraph().getAssumptions(), type, false);
            ParameterNode param = kit.unique(new ParameterNode(i, stamp));
            params.add(param);
        }
        // dummy parameter nodes
        for (int i = types.size(); i < targetTypes.size(); i++) {
            StampPair stamp = StampFactory.forDeclaredType(kit.getGraph().getAssumptions(), targetTypes.get(i), false);
            types.add(targetTypes.get(i));
            ParameterNode param = kit.unique(new ParameterNode(i, stamp));
            params.add(param);
        }
        kit.getGraph().setEntryPointCFG(true);
        kit.getGraph().setEntryPointOriginalParameterTypes(types);
        return params;
    }
}
