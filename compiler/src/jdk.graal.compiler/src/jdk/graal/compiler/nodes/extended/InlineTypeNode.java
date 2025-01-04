package jdk.graal.compiler.nodes.extended;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
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
 * The {@link InlineTypeNode} represents a nullable scalarized inline object. It takes an Object
 * {@link #oopOrHub} and the scalarized field values {@link #scalarizedInlineObject} as input. If
 * the object represents a null value then the input {@link #oopOrHub} will be null at runtime. If
 * the bit 0 of {@link #oopOrHub} is set at runtime, no oop exists and the object needs to be
 * reconstructed by the scalarized field values, if needed. If an oop exists it is up to the
 * compiler to either use the oop or the scalarized field values.
 */
@NodeInfo(nameTemplate = "InlineTypeNode")
public class InlineTypeNode extends FixedWithNextNode implements Lowerable, SingleMemoryKill, VirtualizableAllocation, Canonicalizable, Node.IndirectInputChangedCanonicalization {

    public static final NodeClass<InlineTypeNode> TYPE = NodeClass.create(InlineTypeNode.class);

    @Input ValueNode oopOrHub;
    @Input NodeInputList<ValueNode> scalarizedInlineObject;

    private final ResolvedJavaType type;

    public InlineTypeNode(ResolvedJavaType type, ValueNode oopOrHub, ValueNode[] scalarizedInlineObject) {
        super(TYPE, StampFactory.object(TypeReference.createExactTrusted(type)));
        this.oopOrHub = oopOrHub;
        this.scalarizedInlineObject = new NodeInputList<>(this, scalarizedInlineObject);
        this.type = type;
    }

    public ValueNode getOopOrHub() {
        return oopOrHub;
    }

    public ValueNode getIsInit() {
        return scalarizedInlineObject.last();
    }

    public List<ValueNode> getScalarizedInlineObject() {
        return scalarizedInlineObject.subList(0, scalarizedInlineObject.size() - 1);
    }

    public ResolvedJavaType getType() {
        return type;
    }


    public ValueNode getField(int index) {
        return scalarizedInlineObject.get(index);
    }

    public static InlineTypeNode createFromInvoke(GraphBuilderContext b, InvokeNode invoke) {
        ResolvedJavaType returnType = invoke.callTarget().returnStamp().getTrustedStamp().javaType(b.getMetaAccess());
        // ProjNode oopOrHub = b.add(new ProjNode(StampFactory.object(), invoke, 0));
        ValueNode oopOrHub = invoke;

        ResolvedJavaField[] fields = returnType.getInstanceFields(true);
        ProjNode[] projs = new ProjNode[fields.length + 1];

        for (int i = 0; i < fields.length; i++) {
            projs[i] = b.add(new ProjNode(fields[i].getType(), b.getAssumptions(), invoke, i + 1));

        }

        projs[fields.length] = b.add(new ProjNode(StampFactory.forKind(JavaKind.Long), invoke, fields.length + 1));

        FixedProjAnchorNode anchor = b.add(new FixedProjAnchorNode());
        List<ProjNode> list = new ArrayList<>();
        // list.addFirst(oopOrHub);
        list.addAll(Arrays.asList(projs));
        anchor.objects().addAll(list);
        InlineTypeNode newInstance = b.append(new InlineTypeNode(returnType, oopOrHub, projs));
        // b.append(new ForeignCallNode(LOG_OBJECT, oop, ConstantNode.forBoolean(true,
        // b.getGraph()), ConstantNode.forBoolean(true, b.getGraph())));

        return newInstance;
    }

    /**
     * Needed for replacement with a {@link CommitAllocationNode}
     */
    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.init();
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (scalarizedInlineObject.filter(n -> n.getNodeClass().equals(ProjNode.TYPE)).isEmpty()) {
            return oopOrHub;
        }
        return this;
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
