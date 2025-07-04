package jdk.graal.compiler.replacements.nodes;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node.NodeIntrinsicFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.AbstractStateSplit;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.InliningLog;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Not implemented as a Macro node, as it maybe cannot be mapped to an invoke in bytecode.
 */
@NodeInfo(cycles = CYCLES_0, size = SIZE_0)
@NodeIntrinsicFactory
public class ValueObjectMethodNode extends AbstractStateSplit implements Invokable, Lowerable, SingleMemoryKill {
    public static final NodeClass<ValueObjectMethodNode> TYPE = NodeClass.create(ValueObjectMethodNode.class);

    @Input protected NodeInputList<ValueNode> arguments;

    private int bci;
    private final ResolvedJavaMethod callerMethod;
    private final ResolvedJavaMethod targetMethod;
    private final CallTargetNode.InvokeKind invokeKind;
    private final StampPair returnStamp;
    private final LocationIdentity killedLocationIdentity;

    @SuppressWarnings("this-escape")
    public ValueObjectMethodNode(MacroNode.MacroParams p, FrameState stateAfter) {
        this(TYPE, p, stateAfter);
    }

    @SuppressWarnings("this-escape")
    protected ValueObjectMethodNode(NodeClass<? extends ValueObjectMethodNode> c, MacroNode.MacroParams p, FrameState stateAfter) {
        super(c, p.returnStamp != null ? p.returnStamp.getTrustedStamp() : null);
        this.arguments = new NodeInputList<>(this, p.arguments);
        this.bci = p.bci;
        this.callerMethod = p.callerMethod;
        this.targetMethod = p.targetMethod;
        this.returnStamp = p.returnStamp;
        this.invokeKind = p.invokeKind;
        this.stateAfter = stateAfter;
        this.killedLocationIdentity = LocationIdentity.any();
    }

    public static ValueObjectMethodNode create(MacroNode.MacroParams p) {
        return new ValueObjectMethodNode(p, null);
    }

    public static ValueObjectMethodNode create(MacroNode.MacroParams p, FrameState stateAfter) {
        return new ValueObjectMethodNode(p, stateAfter);
    }

    @Override
    public final boolean inferStamp() {
        verifyStamp();
        return false;
    }

    protected void verifyStamp() {
        GraalError.guarantee(returnStamp.getTrustedStamp().equals(stamp(NodeView.DEFAULT)), "Stamp of replaced node %s must be the same as the original Invoke %s, but is %s ",
                        this, returnStamp.getTrustedStamp(), stamp(NodeView.DEFAULT));
    }

    @Override
    public ResolvedJavaMethod getContextMethod() {
        return callerMethod;
    }

    public NodeInputList<ValueNode> getArguments() {
        return arguments;
    }

    public ValueNode[] toArgumentArray() {
        return arguments.toArray(ValueNode.EMPTY_ARRAY);
    }

    @Override
    public int bci() {
        return bci;
    }

    @Override
    public void setBci(int bci) {
        this.bci = bci;
    }

    @Override
    public ResolvedJavaMethod getTargetMethod() {
        return targetMethod;
    }

    public CallTargetNode.InvokeKind getInvokeKind() {
        return invokeKind;
    }

    public StampPair getReturnStamp() {
        return returnStamp;
    }

    public static boolean intrinsify(GraphBuilderContext b, ValueNode argument) {
        GraphBuilderContext nonIntrinsicAncestor = b.getNonIntrinsicAncestor();
        int bci = nonIntrinsicAncestor == null ? BytecodeFrame.UNKNOWN_BCI : b.bci();
        b.addPush(JavaKind.Int, ValueObjectMethodNode.create(MacroNode.MacroParams.of(b.getInvokeKind(), b.getMethod(), GraalValhallaServices.getValueObjectHashCodeMethod(b.getMetaAccess()), bci,
                        b.getInvokeReturnStamp(b.getAssumptions()), argument)));
        return true;
    }

    @NodeIntrinsic
    public static native int valueObjectHashCodeMethod(Object argument);

    @SuppressWarnings("try")
    public Invoke replaceWithInvoke() {
        try (DebugCloseable context = withNodeSourcePosition(); InliningLog.UpdateScope updateScope = InliningLog.openUpdateScopeTrackingReplacement(graph().getInliningLog(), this)) {
            InvokeNode invoke = createInvoke();
            graph().replaceFixedWithFixed(this, invoke);
            assert invoke.verify();
            return invoke;
        }
    }

    public InvokeNode createInvoke() {
        return createInvoke(graph());
    }

    public InvokeNode createInvoke(StructuredGraph graph) {
        MethodCallTargetNode callTarget = createCallTarget(graph);
        InvokeNode in = new InvokeNode(callTarget, bci(), getKilledLocationIdentity());
        InvokeNode invoke = graph.add(in);
        if (stateAfter() != null) {
            invoke.setStateAfter(stateAfter().duplicate());
            invoke.stateAfter().replaceFirstInput(this, invoke);
        }
        verifyStamp();
        return invoke;
    }

    private MethodCallTargetNode createCallTarget(StructuredGraph graph) {
        return graph.add(new MethodCallTargetNode(getInvokeKind(), getTargetMethod(), toArgumentArray(), getReturnStamp(), null));
    }

    @Override
    public void lower(LoweringTool tool) {
        Invoke invoke = replaceWithInvoke();
        assert invoke.asNode().verify();
        invoke.lower(tool);
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return killedLocationIdentity;
    }

}
