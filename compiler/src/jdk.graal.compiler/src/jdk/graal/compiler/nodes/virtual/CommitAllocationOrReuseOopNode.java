package jdk.graal.compiler.nodes.virtual;

import static jdk.graal.compiler.nodeinfo.InputType.Extension;
import static jdk.graal.compiler.nodeinfo.InputType.Memory;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import java.util.List;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;

/**
 * Similar to {@link CommitAllocationNode} but also stores oop inputs to avoid unnecessary
 * allocation and includes non-null information for nullable scalarized inline objects.
 */
@NodeInfo(nameTemplate = "AllocOrReuse {i#virtualObjects}", allowedUsageTypes = {Extension,
                Memory}, cycles = CYCLES_UNKNOWN, cyclesRationale = "We don't know statically how many, and which, allocations are done.", size = SIZE_UNKNOWN, sizeRationale = "We don't know statically how much code for which allocations has to be generated.")
// @formatter:on
public class CommitAllocationOrReuseOopNode extends CommitAllocationNode {

    public static final NodeClass<CommitAllocationOrReuseOopNode> TYPE = NodeClass.create(CommitAllocationOrReuseOopNode.class);

    @OptionalInput NodeInputList<ValueNode> oops = new NodeInputList<>(this);
    @OptionalInput NodeInputList<ValueNode> nonNulls = new NodeInputList<>(this);

    public CommitAllocationOrReuseOopNode() {
        super(TYPE);
    }

    public List<ValueNode> getOops() {
        return oops;
    }

    public List<ValueNode> getNonNulls() {
        return nonNulls;
    }

    @Override
    public boolean verifyNode() {
        assertTrue(virtualObjects.size() == oops.size(), "values size doesn't match oops size");
        assertTrue(virtualObjects.size() == nonNulls.size(), "values size doesn't match nonNulls size");
        return super.verifyNode();
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
            tool.createVirtualObject(virtualObject, values.subList(pos, pos + entryCount).toArray(new ValueNode[entryCount]), getLocks(i), virtualObject.getNodeSourcePosition(), ensureVirtual.get(i),
                            oops.get(i), nonNulls.get(i));
            pos += entryCount;
        }
        tool.delete();
    }
}
