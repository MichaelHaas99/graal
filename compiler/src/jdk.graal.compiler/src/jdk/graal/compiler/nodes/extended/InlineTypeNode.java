package jdk.graal.compiler.nodes.extended;

import java.lang.ref.Reference;
import java.util.Collections;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.VirtualizableAllocation;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@link InlineTypeNode} represents a (nullable) scalarized inline object. It takes an optional
 * object {@link #oopOrHub} (in C2 it is called Oop) and the scalarized field values
 * {@link #scalarizedInlineObject} as well as an isNotNull information as input. If the object
 * represents a null value then the input {@link #oopOrHub} will be null at runtime. If the bit 0 of
 * {@link #oopOrHub} is set at runtime, no oop exists and the object needs to be reconstructed by
 * the scalarized field values, if needed. If an oop exists it is up to the compiler to either use
 * the oop or the scalarized field values. The isNotNull information indicates if the inline object
 * is null or not, and can be used e.g. for the debugInfo (in C2 it is called isInit).
 */
@NodeInfo(nameTemplate = "InlineTypeNode")
public class InlineTypeNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill, VirtualizableAllocation {

    public static final NodeClass<InlineTypeNode> TYPE = NodeClass.create(InlineTypeNode.class);

    @OptionalInput ProjNode oopOrHub;
    @Input NodeInputList<ProjNode> scalarizedInlineObject;
    @OptionalInput ProjNode isNotNull;

    private final ResolvedJavaType type;

    public InlineTypeNode(ResolvedJavaType type, ProjNode oopOrHub, ProjNode[] scalarizedInlineObject, ProjNode isNotNull) {
        super(TYPE, StampFactory.object(TypeReference.createExactTrusted(type)));
        this.oopOrHub = oopOrHub;
        this.scalarizedInlineObject = new NodeInputList<>(this, scalarizedInlineObject);
        this.type = type;
        this.isNotNull = isNotNull;
    }

    public ValueNode getOopOrHub() {
        return oopOrHub;
    }

    public ValueNode getIsNotNull() {
        return isNotNull;
    }

    public List<ProjNode> getScalarizedInlineObject() {
        return scalarizedInlineObject;
    }

    public ResolvedJavaType getType() {
        return type;
    }


    public ValueNode getField(int index) {
        return scalarizedInlineObject.get(index);
    }

    public static InlineTypeNode createFromInvoke(GraphBuilderContext b, Invoke invoke) {
        ResolvedJavaType returnType = invoke.callTarget().returnStamp().getTrustedStamp().javaType(b.getMetaAccess());

        // can also represent a hub therefore stamp is of type object
        ProjNode oopOrHub = b.add(new ProjNode(StampFactory.object(), invoke.asNode(), 0));

        ResolvedJavaField[] fields = returnType.getInstanceFields(true);
        ProjNode[] projs = new ProjNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            projs[i] = b.add(new ProjNode(fields[i].getType(), b.getAssumptions(), invoke.asNode(), i + 1));

        }

        ProjNode isNotNull = b.add(new ProjNode(StampFactory.forKind(JavaKind.Int), invoke.asNode(), fields.length + 1));

        InlineTypeNode newInstance = b.append(new InlineTypeNode(returnType, oopOrHub, projs, isNotNull));
        // b.append(new ForeignCallNode(LOG_OBJECT, oop, ConstantNode.forBoolean(true,
        // b.getGraph()), ConstantNode.forBoolean(true, b.getGraph())));

        return newInstance;
    }

    public void removeOnInlining() {
        ValueNode invoke = oopOrHub.getMultiNode();
        assert invoke instanceof Invoke : "should only be called on inlining of invoke nodes";
        replaceAtUsages(invoke);

        // remove inputs of ProjNodes to MultiNode
        oopOrHub.delete();
        isNotNull.delete();
        for (ProjNode p : scalarizedInlineObject) {
            p.delete();
        }

        // set control flow correctly and delete
        graph().removeFixed(this);

    }

    /**
     * Needed for replacement with a {@link CommitAllocationNode}
     */
    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.init();
    }




    @Override
    public void virtualize(VirtualizerTool tool) {
        /*
         * Reference objects can escape into their ReferenceQueue at any safepoint, therefore
         * they're excluded from escape analysis.
         */
        if (!tool.getMetaAccess().lookupJavaType(Reference.class).isAssignableFrom(type) &&
                        tool.getMetaAccessExtensionProvider().canVirtualize(type)) {

            // Because the node can represent a null value, insert a guard before we virtualize
            LogicNode isInit = LogicNegationNode.create(new IsNullNode(oopOrHub));
            tool.addNode(isInit);
            tool.addNode(new FixedGuardNode(isInit, DeoptimizationReason.TransferToInterpreter, DeoptimizationAction.None));

            // virtualize
            VirtualInstanceNode virtualObject = new VirtualInstanceNode(type, false);
            ResolvedJavaField[] fields = virtualObject.getFields();
            ValueNode[] state = new ValueNode[fields.length];
            for (int i = 0; i < state.length; i++) {
                state[i] = getField(i);
            }

            // create virtual object and hand over oopOrHub
            tool.createVirtualObject(virtualObject, state, Collections.emptyList(), getNodeSourcePosition(), false, oopOrHub);
            tool.replaceWithVirtual(virtualObject);
        }
    }

}
