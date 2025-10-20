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
import jdk.graal.compiler.core.target.Backend;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.DebugOptions;
import jdk.graal.compiler.debug.GraalError;
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
import jdk.graal.compiler.phases.Speculative;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.printer.GraalDebugHandlersFactory;
import jdk.graal.compiler.replacements.GraphKit;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.meta.DefaultProfilingInfo;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
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

    protected final StructuredGraph getGraph(DebugContext debug, boolean receiverOnly) {
        try {
            HotSpotGraphKit kit = new HotSpotGraphKit(debug, targetMethod, providers, providers.getGraphBuilderPlugins(), INVALID_COMPILATION_ID, null, false, true);
            StructuredGraph graph = kit.getGraph();
            graph.getGraphState().forceDisableFrameStateVerification();
            List<ValueNode> oldArguments = createParameters(kit, receiverOnly);
            ParametersAssignNode parameterAssignNode = kit.append(new ParametersAssignNode(new ValueNode[0], new ValueNode[0], targetMethod));
            List<ValueNode> newArguments = new ArrayList<>();
            if (receiverOnly) {
                // for the receiver only entry point we only need to scalarize the receiver, the
                // rest is already scalarized
                ValueNode[] scalarizedReceiver = InlineTypeUtil.createScalarizationCFG(parameterAssignNode, oldArguments.get(0), targetMethod.getDeclaringClass().getInstanceFields(true));
                newArguments.addAll(List.of(scalarizedReceiver));
                // add the rest
                newArguments.addAll(oldArguments.subList(1, oldArguments.size()));
            } else {
                int parameterLength = targetMethod.getSignature().getParameterCount(!targetMethod.isStatic());
                for (int signatureIndex = 0; signatureIndex < parameterLength; signatureIndex++) {
                    boolean nonNull = GraalValhallaServices.isParameterNullFree(targetMethod, signatureIndex, true);
                    if (GraalValhallaServices.isScalarizedParameter(targetMethod, signatureIndex, true)) {
                        List<ResolvedJavaField> fields = GraalValhallaServices.getScalarizedParameterFields(targetMethod, signatureIndex, true);
                        ValueNode[] scalarizedParam = InlineTypeUtil.createScalarizationCFG(parameterAssignNode, oldArguments.get(signatureIndex),
                                        fields, nonNull, !nonNull);
                        newArguments.addAll(List.of(scalarizedParam));
                    } else {
                        newArguments.add(oldArguments.get(signatureIndex));
                    }

                }
            }
            parameterAssignNode.oldArguments.addAll(oldArguments);
            parameterAssignNode.newArguments.addAll(newArguments);
            kit.append(new DummyControlSinkNode());
            debug.dump(DebugContext.VERBOSE_LEVEL, graph, "Verified inline entry point%s graph before compilation", receiverOnly ? " receiver only" : "");
            return graph;
        } catch (Exception e) {
            throw GraalError.shouldNotReachHere(e); // ExcludeFromJacocoGeneratedReport
        }
    }

    public CompilationResult getCode(final Backend backend, boolean receiverOnly) {
        try (DebugContext debug = openDebugContext(DebugContext.forCurrentThread())) {
            try (DebugContext.Scope d = debug.scope("Compiling entry point", providers.getCodeCache(), debugScopeContext())) {
                CompilationIdentifier compilationId = INVALID_COMPILATION_ID;
                final StructuredGraph graph = getGraph(debug, receiverOnly);
                CompilationResult compResult = buildCompilationResult(debug, backend, graph, compilationId);
                return compResult;
            } catch (Throwable e) {
                throw debug.handle(e);
            }
        }
    }

    private CompilationResult buildCompilationResult(DebugContext debug, final Backend backend, StructuredGraph graph, CompilationIdentifier compilationId) {
        CompilationResult compResult = new CompilationResult(compilationId, toString());

        // Entry points cannot be recompiled so they cannot be compiled with assumptions
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

        defaultSuites.getMidTier().removeSubTypePhases(Speculative.class);
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
        return parameters;
    }
}
