package jdk.graal.compiler.nodes.virtual;

import static jdk.graal.compiler.nodeinfo.InputType.Extension;
import static jdk.graal.compiler.nodeinfo.InputType.Memory;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import java.util.Collections;
import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNegationNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.AndNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.word.WordCastNode;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;

@NodeInfo(nameTemplate = "AllocOrReuse {i#virtualObjects}", allowedUsageTypes = {Extension,
                Memory}, cycles = CYCLES_UNKNOWN, cyclesRationale = "We don't know statically how many, and which, allocations are done.", size = SIZE_UNKNOWN, sizeRationale = "We don't know statically how much code for which allocations has to be generated.")
// @formatter:on
public class CommitAllocationOrReuseOopNode extends CommitAllocationNode {

    public static final NodeClass<CommitAllocationOrReuseOopNode> TYPE = NodeClass.create(CommitAllocationOrReuseOopNode.class);

    @Input NodeInputList<ValueNode> oopsOrHubs = new NodeInputList<>(this);

    public CommitAllocationOrReuseOopNode() {
        super(TYPE);
    }

    public List<ValueNode> getOopsOrHubs() {
        return oopsOrHubs;
    }

    @Override
    public boolean verifyNode() {
        assertTrue(virtualObjects.size() == oopsOrHubs.size(), "values size doesn't match oopsOrHubs size");
        return super.verifyNode();
    }

    @Override
    public void lower(LoweringTool tool) {
        StructuredGraph graph = graph();
        FixedNode next = next();
        FixedWithNextNode previous = (FixedWithNextNode) this.predecessor();
        setNext(null);
        int valuePos = 0;
        FrameState framestate = null;

        FixedNode frameStatePrevious = this;
        while (framestate == null) {
            if (frameStatePrevious instanceof StateSplit stateSplit) {
                if (stateSplit.stateAfter() != null) {
                    framestate = stateSplit.stateAfter();
                } else {
                    frameStatePrevious = (FixedNode) frameStatePrevious.predecessor();
                }
            } else {
                frameStatePrevious = (FixedNode) frameStatePrevious.predecessor();
            }
        }

        List<AllocatedObjectNode> commitUsages = this.usages().filter(AllocatedObjectNode.class).stream().toList();

        for (int i = 0; i < oopsOrHubs.size(); i++) {

            ValueNode oopOrHub = oopsOrHubs.get(i);
            WordCastNode oopOrHubWord = graph.addOrUnique(WordCastNode.addressToWord(oopOrHub, tool.getWordTypes().getWordKind()));
            // graph.addAfterFixed(previous, oopOrHubWord);
            previous.setNext(oopOrHubWord);
            // set bit 0 to 1, to indicate a scalarized return value
            ValueNode result = graph.addOrUnique(new AndNode(oopOrHubWord, graph.addOrUnique(ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1))));
            LogicNode isNotAlreadyBuffered = graph.addOrUnique(new IntegerEqualsNode(result, ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1, graph)));
            LogicNode isAlreadyBuffered = graph.addOrUnique(LogicNegationNode.create(isNotAlreadyBuffered));
            // graph.replaceFixed(this, taggedHub);

            BeginNode trueBegin = graph.add(new BeginNode());
            BeginNode falseBegin = graph.add(new BeginNode());

            IfNode ifNode = graph.add(new IfNode(isAlreadyBuffered, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
            oopOrHubWord.setNext(ifNode);

            // true branch - inline object is already buffered

            EndNode trueEnd = graph.add(new EndNode());
            trueBegin.setNext(trueEnd);

            // false branch - inline object is not buffered

            EndNode falseEnd = graph.add(new EndNode());

            // Use a CommitAllocation node so that no FrameState is required when creating
            // the new instance.
            CommitAllocationNode commit = graph.add(new CommitAllocationNode());
            falseBegin.setNext(commit);
            // VirtualObjectNode virtualObj = graph.add(new
            // VirtualInstanceNode(virtualObjects.get(i).type(), false));
            // virtualObj.setObjectId(0);

            VirtualObjectNode virtualObj = this.virtualObjects.get(i);

            // commit.usages().filter(AllocatedObjectNode.class).count() == commit.getUsageCount()

            AllocatedObjectNode newObj = graph.addWithoutUnique(new AllocatedObjectNode(virtualObj));
            AllocatedObjectNode oldObj = commitUsages.get(i);
            oldObj.setCommit(null);

            commit.getVirtualObjects().add(virtualObj);
            newObj.setCommit(commit);

            /*
             * The commit values follow the same ordering as the declared fields returned by JVMCI.
             * Since the new object's fields are copies of the old one's, the values are given by a
             * load of the corresponding field in the old object.
             */
            List<ValueNode> commitValues = commit.getValues();
            commitValues.addAll(values.subList(valuePos, valuePos + virtualObj.entryCount()));

            commit.addLocks(Collections.emptyList());
            commit.getEnsureVirtual().add(false);
            assert commit.verify();
            commit.setNext(falseEnd);

            // merge
            MergeNode merge = graph.add(new MergeNode());

            merge.setStateAfter(framestate);

            ValuePhiNode phi = graph.addOrUnique(new ValuePhiNode(StampFactory.objectNonNull(), merge, oopOrHub, newObj));
            // this.usages().filter(AllocatedObjectNode.class).stream().toList().get(i).replaceAtUsages(phi);
            oldObj.replaceAtUsages(phi);
            // oldObj.safeDelete();

            // graph.replaceFixedWithFloating(inlineTypeNode, phi);

            merge.addForwardEnd(trueEnd);
            merge.addForwardEnd(falseEnd);
            merge.setNext(next);

            commit.lower(tool);
            valuePos += virtualObj.entryCount();
            previous = merge;
            commit.lower(tool);
        }
        this.safeDelete();

/*
 * ValueNode oopOrHub = oopsOrHubs.get(0);
 * 
 * WordCastNode oopOrHubWord = graph.addOrUnique(WordCastNode.addressToWord(oopOrHub,
 * tool.getWordTypes().getWordKind())); graph.addBeforeFixed(this, oopOrHubWord); // set bit 0 to 1,
 * to indicate a scalarized return value ValueNode result = graph.addOrUnique(new AndNode(oopOrHub,
 * graph.addOrUnique(ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1)))); LogicNode
 * isAlreadyBuffered = graph.addOrUnique(new IntegerEqualsNode(result,
 * ConstantNode.forIntegerKind(tool.getWordTypes().getWordKind(), 1, graph))); //
 * graph.replaceFixed(this, taggedHub);
 * 
 * BeginNode trueBegin = graph.add(new BeginNode()); BeginNode falseBegin = graph.add(new
 * BeginNode());
 * 
 * IfNode ifNode = graph.add(new IfNode(isAlreadyBuffered, trueBegin, falseBegin,
 * ProfileData.BranchProbabilityData.unknown())); ((FixedWithNextNode)
 * this.predecessor()).setNext(ifNode);
 * 
 * // true branch - inline object is already buffered
 * 
 * EndNode trueEnd = graph.add(new EndNode()); trueBegin.setNext(trueEnd);
 * 
 * // false branch - inline object is not buffered
 * 
 * EndNode falseEnd = graph.add(new EndNode());
 * 
 * // Use a CommitAllocation node so that no FrameState is required when creating // the new
 * instance. CommitAllocationNode commit = graph.add(new CommitAllocationNode());
 * falseBegin.setNext(commit); VirtualObjectNode virtualObj = graph.add(new
 * VirtualInstanceNode(virtualObjects.get(0).type(), false)); virtualObj.setObjectId(0);
 * 
 * AllocatedObjectNode newObj = graph.addWithoutUnique(new AllocatedObjectNode(virtualObj));
 * commit.getVirtualObjects().add(virtualObj); newObj.setCommit(commit);
 * 
 *//*
    * The commit values follow the same ordering as the declared fields returned by JVMCI. Since the
    * new object's fields are copies of the old one's, the values are given by a load of the
    * corresponding field in the old object.
    *//*
       * List<ValueNode> commitValues = commit.getValues(); commitValues.addAll(values);
       * 
       * commit.addLocks(Collections.emptyList()); commit.getEnsureVirtual().add(false); assert
       * commit.verify(); commit.setNext(falseEnd);
       * 
       * if (falseBegin.next() == null) falseBegin.setNext(falseEnd);
       * 
       * // merge MergeNode merge = graph.add(new MergeNode()); //
       * merge.setStateAfter(inlineTypeNode.stateAfter());
       * 
       * ValuePhiNode phi = graph.addOrUnique(new ValuePhiNode(StampFactory.objectNonNull(), merge,
       * oopOrHub, newObj));
       * this.usages().filter(AllocatedObjectNode.class).first().replaceAtUsages(phi);
       * this.safeDelete(); // graph.replaceFixedWithFloating(inlineTypeNode, phi);
       * 
       * merge.addForwardEnd(trueEnd); merge.addForwardEnd(falseEnd); merge.setNext(next);
       * 
       * commit.lower(tool);
       */
        // super.lower(tool);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        int pos = 0;
        for (int i = 0; i < virtualObjects.size(); i++) {
            VirtualObjectNode virtualObject = virtualObjects.get(i);
            int entryCount = virtualObject.entryCount();
            /*
             * n.b. the node source position of virtualObject will have been set when it was
             * created.
             */
            LogicNode isInit = LogicNegationNode.create(new IsNullNode(oopsOrHubs.get(i)));
            tool.addNode(isInit);
            tool.addNode(new FixedGuardNode(isInit, DeoptimizationReason.TransferToInterpreter, DeoptimizationAction.None));
            tool.createVirtualObject(virtualObject, values.subList(pos, pos + entryCount).toArray(new ValueNode[entryCount]), getLocks(i), virtualObject.getNodeSourcePosition(), ensureVirtual.get(i),
                            oopsOrHubs.get(i));
            pos += entryCount;
        }
        tool.delete();
    }
}
