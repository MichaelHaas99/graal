package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.calc.CanonicalCondition;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.calc.PointerEqualsNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.memory.MemoryAccess;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.vm.ci.hotspot.ACmpDataAccessor;
import jdk.vm.ci.meta.JavaKind;

/**
 * Determines if two objects are equal with Valhalla semantics. The node needs to be fixed, because
 * we may perform a substitutability check. The substitutability check compares the field values of
 * two inline objects recursively, which is therefore a memory access.
 */
@NodeInfo(cycles = CYCLES_UNKNOWN, cyclesRationale = "We don't know statically the size of the inlined comparison.", size = SIZE_UNKNOWN, sizeRationale = "We don't know statically how much code for the inlined comparison will be generated")
public class ValhallaObjectEqualsNode extends FixedWithNextNode implements Lowerable, Canonicalizable, MemoryAccess, Virtualizable {
    public static final NodeClass<ValhallaObjectEqualsNode> TYPE = NodeClass.create(ValhallaObjectEqualsNode.class);
    @Input protected ValueNode x;
    @Input protected ValueNode y;

    private static final ObjectEqualsNode.ObjectEqualsOp OP = new ObjectEqualsNode.ObjectEqualsOp();

    public ValueNode getX() {
        return x;
    }

    public ValueNode getY() {
        return y;
    }

    public void setX(ValueNode newX) {
        assert newX != null;
        updateUsages(x, newX);
        this.x = newX;
    }

    public void setY(ValueNode newY) {
        assert newY != null;
        updateUsages(y, newY);
        this.y = newY;
    }

    private ACmpDataAccessor profile;

    public ACmpDataAccessor getProfile() {
        return profile;
    }

    public ValhallaObjectEqualsNode(ValueNode x, ValueNode y, ACmpDataAccessor profile) {
        super(TYPE, StampFactory.forInteger(JavaKind.Int, 0, 1));
        assert x != null;
        assert y != null;
        this.x = x;
        this.y = y;
        this.profile = profile;
    }

    public static LogicNode create(GraphBuilderContext b, ValueNode x, ValueNode y, NodeView view, ACmpDataAccessor profile) {
        LogicNode result = OP.canonical(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, CanonicalCondition.EQ, false, x, y, view, b.getValhallaOptionsProvider());
        if (result != null) {
            return result;
        }
        result = CompareNode.tryConstantFold(CanonicalCondition.EQ, x, y, b.getConstantReflection(), false);
        if (result != null) {
            return result;
        } else {

            result = PointerEqualsNode.findSynonym(x, y, view);
            if (result != null) {
                return result;
            }

            if (!InlineTypeUtil.mayNeedSubstitutabilityCheck(x, y, b.getValhallaOptionsProvider())) {
                return new ObjectEqualsNode(x, y);
            }

            FixedWithNextNode fixedEqualityCheck = b.add(new ValhallaObjectEqualsNode(x, y, profile));
            return b.add(IntegerEqualsNode.create(fixedEqualityCheck, ConstantNode.forInt(1, b.getGraph()), view));
        }
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        NodeView view = NodeView.from(tool);

        LogicNode value = OP.canonical(tool.getConstantReflection(), tool.getMetaAccess(), tool.getOptions(), tool.smallestCompareWidth(), CanonicalCondition.EQ, false, getX(), getY(), view,
                        tool.getValhallaOptionsProvider());
        if (value != null) {
            return new ConditionalNode(value, ConstantNode.forInt(1), ConstantNode.forInt(0));
        }
        if (!InlineTypeUtil.mayNeedSubstitutabilityCheck(x, y, tool.getValhallaOptionsProvider())) {
            return new ConditionalNode(new ObjectEqualsNode(x, y), ConstantNode.forInt(1), ConstantNode.forInt(0));
        }
        return this;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return LocationIdentity.any();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode x = getX();
        ValueNode y = getY();

        LogicNode node = ObjectEqualsNode.virtualizeComparison(x, y, graph(), tool);
        if (node == null) {
            return;
        }

        ValueNode result = new ConditionalNode(node, ConstantNode.forInt(1), ConstantNode.forInt(0));
        tool.ensureAdded(result);
        tool.replaceWithValue(result);
    }
}
