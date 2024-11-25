package jdk.graal.compiler.replacements;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

public class InlineTypePlugin implements NodePlugin {

    @Override
    public boolean handleLoadField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {
        ValueNode fieldRead;
        if (field.isFlat()) {
            if (!field.isNullFreeInlineType()) {
                // field is flat and nullable

                LoadFieldNode y = b.add(LoadFieldNode.create(b.getAssumptions(), object, field.getNullMarkerField()));
                ConstantNode x = b.add(ConstantNode.forConstant(JavaConstant.INT_0, b.getMetaAccess()));

                LogicNode condition = IntegerEqualsNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, x, y, NodeView.DEFAULT);
                b.add(condition);

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());
                IfNode ifNode = b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));

                // true branch
                EndNode trueEnd = b.add(new EndNode());

                // false branch
                ValueNode instance = genGetLoadFlatField(b, object, (HotSpotResolvedObjectType) field.getType(), field.getOffset());
                EndNode falseEnd = b.add(new EndNode());
                falseBegin.setNext((FixedWithNextNode) instance);

                // merge
                MergeNode merge = b.add(new MergeNode());
                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);
                b.setStateAfter(merge);

                ConstantNode nullPointer = b.add(ConstantNode.forConstant(JavaConstant.NULL_POINTER, b.getMetaAccess()));

                ValuePhiNode phiNode = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), field.getType(), false).getTrustedStamp(), merge,
                                nullPointer, instance));

                fieldRead = phiNode;

            } else {
                // field is flat and null restricted
                fieldRead = genGetLoadFlatField(b, object, (HotSpotResolvedObjectType) field.getType(), field.getOffset());
            }
            b.push(JavaKind.Object, fieldRead);
            return true;
        }
        return false;
    }

    private ValueNode genGetLoadFlatField(GraphBuilderContext b, ValueNode object, HotSpotResolvedObjectType fieldType, int srcOff) {

        NewInstanceNode newInstance = b.add(new NewInstanceNode(fieldType, false));

        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has no header so remove the header offset
            LoadFieldNode load = b.add(LoadFieldNode.create(b.getAssumptions(), object, innerField.changeOffset(srcOff + off)));

            // new holder has a header
            StoreFieldNode storeFieldNode = b.add(new StoreFieldNode(newInstance, innerField, b.maskSubWordValue(load, innerField.getJavaKind())));
            if (i != innerFields.length - 1) {
                storeFieldNode.setStateAfter(b.add(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));
            } else {
                // last store field should have a valid frame state
                b.setStateAfter(storeFieldNode);
            }

        }
        return newInstance;
    }
}
