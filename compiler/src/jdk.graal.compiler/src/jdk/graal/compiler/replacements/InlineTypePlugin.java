package jdk.graal.compiler.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.SAFEPOINT;
import static jdk.graal.compiler.nodes.NamedLocationIdentity.OBJECT_ARRAY_LOCATION;
import static jdk.graal.compiler.replacements.DefaultJavaLoweringProvider.POSITIVE_ARRAY_INDEX_STAMP;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.extended.LoadArrayComponentHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.java.StoreFlatFieldNode;
import jdk.graal.compiler.nodes.java.StoreFlatIndexedNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

public class InlineTypePlugin implements NodePlugin {


    @Override
    public boolean handleLoadField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {

        if (field.isFlat()) {
            if (!field.isNullFreeInlineType()) {
                // field is flat and nullable

                // TODO: functionality not correctly implemented yet, wait until it's fully
                // implemented in the JVM

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());

                genFlatFieldNullCheck(b, object, field, trueBegin, falseBegin);

                // true branch - flat field is null
                EndNode trueEnd = b.add(new EndNode());
                trueBegin.setNext(trueEnd);

                // false branch - flat field is non-null
                InlineTypeNode instance = genLoadFlatField(b, object, field);
                EndNode falseEnd = b.add(new EndNode());
                falseBegin.setNext(instance);

                ConstantNode nullPointer = ConstantNode.forConstant(JavaConstant.NULL_POINTER, b.getMetaAccess(), b.getGraph());

                // return a null pointer if the flat field was null or the read instance otherwise
                ValuePhiNode phiNode = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), field.getType(), false).getTrustedStamp(), null,
                                nullPointer, instance));
                b.push(JavaKind.Object, phiNode);

                // merge
                MergeNode merge = b.add(new MergeNode());
                phiNode.setMerge(merge);

                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);



            } else {
                // field is flat and null-restricted
                b.push(JavaKind.Object, genLoadFlatField(b, object, field));
            }
            return true;

        } else if (field.isNullFreeInlineType()) {
            // field is null-free but not flat

            // for null free inline type fields it is the responsibility of the reader to return the
            // default instance if the field is null
            object = genNullCheck(b, object);
            LoadFieldNode fieldValue = b.add(LoadFieldNode.create(b.getAssumptions(), object, field));
            genHandleNullFreeInlineTypeField(b, fieldValue, field);
            return true;

        }
        return false;
    }

    @Override
    public boolean handleLoadStaticField(GraphBuilderContext b, ResolvedJavaField field) {
        if (field.isNullFreeInlineType() && !field.isInitialized()) {
            // field is a static null-free inline type and not already initialized, do a null-check
            // at runtime
            LoadFieldNode fieldValue = b.add(LoadFieldNode.create(b.getAssumptions(), null, field));
            genHandleNullFreeInlineTypeField(b, fieldValue, field);
            return true;
        }
        return false;
    }

    /**
     * Responsible for loading the inline object stored in a flat representation as a field value in
     * another object.
     * 
     * @param b the context
     * @param object the receiver object for the field access
     * @param field the accessed field
     * @return an {@link InlineTypeNode} representing the loaded flat field
     */
    private InlineTypeNode genLoadFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {

        // make a null check for all load operations
        object = genNullCheck(b, object);

        // only support null-restricted flat fields for now
        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();
        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);
        LoadFieldNode[] loads = new LoadFieldNode[innerFields.length];

        int srcOff = field.getOffset();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has no header so remove the header offset
            loads[i] = b.add(
                            LoadFieldNode.create(b.getAssumptions(), object, innerField.changeOffset(srcOff + off).setOuterDeclaringClass((HotSpotResolvedObjectType) field.getDeclaringClass())));
        }

        // create InlineTypeNode
        return b.append(InlineTypeNode.createNonNullWithoutOop(fieldType, loads));
    }

    /**
     * Responsible for checking an already loaded value from a null-restricted field at runtime. If
     * the value is null, it has to be replaced by the default instance.
     *
     * @param b the context
     * @param fieldValue the already loaded field value
     * @param field the accessed field
     */
    private void genHandleNullFreeInlineTypeField(GraphBuilderContext b, ValueNode fieldValue, ResolvedJavaField field) {
        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();
        BeginNode trueBegin = b.getGraph().add(new BeginNode());
        BeginNode falseBegin = b.getGraph().add(new BeginNode());

        genFieldNullCheck(b, fieldValue, trueBegin, falseBegin);

        // true branch - field is null use the default instance
        EndNode trueEnd = b.add(new EndNode());
        ConstantNode defaultValue = b.add(ConstantNode.forConstant(fieldType.getDefaultInlineTypeInstance(), b.getMetaAccess(), b.getGraph()));
        trueBegin.setNext(trueEnd);

        // false branch - field is non-null
        EndNode falseEnd = b.add(new EndNode());
        falseBegin.setNext(falseEnd);

        // return the default instance if the field was null otherwise the value
        ValuePhiNode phiNode = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), field.getType(), true).getTrustedStamp(), null,
                        defaultValue, fieldValue));
        b.push(JavaKind.Object, phiNode);

        // merge
        MergeNode merge = b.add(new MergeNode());
        phiNode.setMerge(merge);

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
    }

    @Override
    public boolean handleStoreField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, ValueNode value) {
        if (field.isFlat()) {
            // field is flat
            if (!field.isNullFreeInlineType()) {
                // field is flat and nullable

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());

                // generate if node with condition
                genFlatFieldNullCheck(b, object, field, trueBegin, falseBegin);

                // true branch - flat field is null
                StoreFieldNode storeField = b.add(new StoreFieldNode(object, field.getNullMarkerField(),
                                b.maskSubWordValue(ConstantNode.forInt(0, b.getGraph()), field.getNullMarkerField().getJavaKind())));
                trueBegin.setNext(storeField);
                EndNode trueEnd = b.add(new EndNode());

                // false branch - flat field is non-null
                b.add(falseBegin);
                storeField = b.add(new StoreFieldNode(object, field.getNullMarkerField(),
                                b.maskSubWordValue(ConstantNode.forInt(1, b.getGraph()), field.getNullMarkerField().getJavaKind())));
                falseBegin.setNext(storeField);
                genStoreFlatField(b, object, field, value);
                EndNode falseEnd = b.add(new EndNode());

                // merge
                MergeNode merge = b.add(new MergeNode());
                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);

            } else {
                // field is null restricted
                genStoreFlatField(b, object, field, value);
            }
            return true;
        }
        return false;
    }

    /**
     * Responsible for storing the {@code value} in a flat representation as a field value of
     * another {@code object}. It therefore loads each field value and stores it at a specific
     * offset into the {@code object}.
     *
     * @param b the context
     * @param object the receiver object for the field access
     * @param field the accessed field
     * @param value the value to be stored into the field
     */
    private void genStoreFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, ValueNode value) {

        // make a null check for all load operations
        object = genNullCheck(b, object);

        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();

        int destOff = field.getOffset();

        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);

        List<ValueNode> readOperations = new ArrayList<>();
        List<StoreFlatFieldNode.StoreFieldInfo> writeOperations = new ArrayList<>();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has a header
            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), value, innerField));
            readOperations.add(b.maskSubWordValue(load, innerField.getJavaKind()));

            // holder has no header so remove the header offset
            writeOperations.add(new StoreFlatFieldNode.StoreFieldInfo(i, innerField.changeOffset(destOff + off).setOuterDeclaringClass((HotSpotResolvedObjectType) field.getDeclaringClass())));
        }
        StoreFlatFieldNode storeFlatFieldNode = b.add(new StoreFlatFieldNode(object, field, writeOperations));
        storeFlatFieldNode.addValues(readOperations);
    }

    /**
     *
     * @deprecated
     */
    private void genFlatFieldNullCheck(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, BeginNode trueBegin, BeginNode falseBegin) {
        LoadFieldNode y = b.add(LoadFieldNode.create(b.getAssumptions(), object, field.getNullMarkerField()));
        ConstantNode x = ConstantNode.forInt(0, b.getGraph());

        LogicNode condition = IntegerEqualsNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, x, y, NodeView.DEFAULT);
        b.add(condition);

        b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }


    private void genFieldNullCheck(GraphBuilderContext b, ValueNode fieldValue, BeginNode trueBegin, BeginNode falseBegin) {
        LogicNode condition = b.add(IsNullNode.create(fieldValue));
        b.add(condition);

        b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }

    @Override
    public boolean handleLoadIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, JavaKind elementKind) {
        if (!elementKind.isObject() || !b.getValhallaOptionsProvider().useArrayFlattening())
            return false;

        boolean isInlineTypeArray = StampTool.isInlineTypeArray(array, b.getValhallaOptionsProvider());
        boolean canBeInlineTypeArray = StampTool.canBeInlineTypeArray(array, b.getValhallaOptionsProvider());

        if (canBeInlineTypeArray) {
            // array can consist of inline objects
            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());

            array = genNullCheck(b, array);
            boundsCheck = genBoundsCheck(b, boundsCheck, array, index);
            index = createPositiveIndex(b.getGraph(), index, boundsCheck);

            if (isInlineTypeArray && resolvedType.isFlatArray()) {
                // array is known to consist of flat inline objects
                int shift = resolvedType.getLog2ComponentSize();
                b.push(elementKind,
                                genLoadFlatFieldFromArray(b, array, index, boundsCheck, resolvedType, shift, null));
                return true;
            }

            // check at runtime if we have a flat array

            BeginNode trueBegin = b.getGraph().add(new BeginNode());
            BeginNode falseBegin = b.getGraph().add(new BeginNode());
            genFlatArrayCheck(b, array, trueBegin, falseBegin);

            Stamp resultStamp = null;

            // true branch - flat array
            FixedWithNextNode instanceFlatArray;
            if (isInlineTypeArray) {
                // produce code that loads the flat inline type
                int shift = resolvedType.convertToFlatArray().getLog2ComponentSize();

                instanceFlatArray = genLoadFlatFieldFromArray(b, array, index, boundsCheck, resolvedType,
                                shift, trueBegin);
                resultStamp = instanceFlatArray.stamp(NodeView.DEFAULT);
                if (hasNoNext(trueBegin)) {
                    trueBegin.setNext(instanceFlatArray);
                }
            } else {
                // we don't know the type at compile time, produce a runtime call
                ForeignCallNode load = b.add(new ForeignCallNode(LOAD_UNKNOWN_INLINE, array, index));
                resultStamp = load.stamp(NodeView.DEFAULT);
                instanceFlatArray = load;
                trueBegin.setNext(load);
            }

            EndNode trueEnd = b.add(new EndNode());

            // false branch - no flat array
            ValueNode instanceNonFlatArray = b.add(LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, elementKind, b.getMetaAccess(), b.getConstantReflection()));
            resultStamp = resultStamp.meet(instanceNonFlatArray.stamp(NodeView.DEFAULT));
            EndNode falseEnd = b.add(new EndNode());
            if (instanceNonFlatArray instanceof FixedNode fixedNode) {
                falseBegin.setNext(fixedNode);
            } else {
                falseBegin.setNext(falseEnd);
            }

            ValuePhiNode phiNode = b.add(new ValuePhiNode(resultStamp, null,
                            instanceFlatArray, instanceNonFlatArray));
            b.push(elementKind, phiNode);
            // merge, wait for frame state creation until phi is on stack
            MergeNode merge = b.add(new MergeNode());
            phiNode.setMerge(merge);
            merge.addForwardEnd(trueEnd);
            merge.addForwardEnd(falseEnd);
            return true;
        }
        return false;
    }

    /**
     * Similar to {@link #genLoadFlatField(GraphBuilderContext, ValueNode, ResolvedJavaField)}, but
     * loads the flat field from a flat array.
     */
    private FixedWithNextNode genLoadFlatFieldFromArray(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, HotSpotResolvedObjectType resolvedType, int shift,
                    BeginNode begin) {
        HotSpotResolvedObjectType componentType = (HotSpotResolvedObjectType) resolvedType.getComponentType();

        ResolvedJavaField[] innerFields = componentType.getInstanceFields(true);
        LoadIndexedNode[] loads = new LoadIndexedNode[innerFields.length];

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - componentType.firstFieldOffset();

            LoadIndexedNode load;
            if (innerField.getJavaKind() == JavaKind.Object) {
                load = new LoadIndexedNode(LoadIndexedNode.TYPE, StampFactory.forDeclaredType(b.getAssumptions(), innerField.getType(), false).getTrustedStamp(), array, index, boundsCheck,
                                innerField.getJavaKind());
            } else {
                load = new LoadIndexedNode(LoadIndexedNode.TYPE, StampFactory.forKind(innerField.getJavaKind()), array, index, boundsCheck, innerField.getJavaKind());
            }

            // holder has no header so remove the header offset
            load.setAdditionalOffset(off);
            load.setShift(shift);
            load.setLocation(innerField.changeOffset(off).setOuterDeclaringClass(componentType));
            loads[i] = b.add(load);

        }
        InlineTypeNode inlineTypeNode = b.append(InlineTypeNode.createNonNullWithoutOop(componentType, loads));

        // return first node in control flow
        if (hasNoNext(begin)) {
            begin.setNext(loads.length > 0 ? loads[0] : inlineTypeNode);
        }
        return inlineTypeNode;
    }

    @Override
    public boolean handleStoreIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, JavaKind elementKind, ValueNode value) {
        if (!elementKind.isObject() || !b.getValhallaOptionsProvider().useArrayFlattening())
            return false;
        boolean isInlineTypeArray = StampTool.isInlineTypeArray(array, b.getValhallaOptionsProvider());
        boolean canBeInlineTypeArray = StampTool.canBeInlineTypeArray(array, b.getValhallaOptionsProvider());

        if (canBeInlineTypeArray) {
            // array can consist of inline objects
            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());

            // produce checks for all store indexed nodes
            array = genNullCheck(b, array);
            boundsCheck = genBoundsCheck(b, boundsCheck, array, index);
            index = createPositiveIndex(b.getGraph(), index, boundsCheck);
            storeCheck = genStoreCheck(b, storeCheck, array, value);

            if (isInlineTypeArray && resolvedType.isFlatArray()) {
                // array is known to consist of flat inline objects
                int shift = resolvedType.getLog2ComponentSize();
                // we store the value in a flat array we need to do a null check before loading the
                // fields
                ValueNode nullCheckedValue = genNullCheck(b, value);
                genStoreFlatFieldToArray(b, array, index, boundsCheck, storeCheck, resolvedType,
                                nullCheckedValue, shift);
                return true;
            }

            // runtime check necessary

            BeginNode trueBegin = null;
            BeginNode falseBegin = b.getGraph().add(new BeginNode());
            IfNode ifNode = genFlatArrayCheck(b, array, trueBegin, falseBegin);



            // true branch - flat array
            EndNode trueEnd;
            // we store the value in a flat array we need to do a null check before loading the
            // fields
            trueBegin = b.append(new BeginNode());
            ifNode.setTrueSuccessor(trueBegin);

            ValueNode nullCheckedValue = genNullCheck(b, value, trueBegin);
            if (isInlineTypeArray) {

                // produce code that stores the flat inline type
                int shift = resolvedType.convertToFlatArray().getLog2ComponentSize();
                ValueNode firstFixedNode = genStoreFlatFieldToArray(b, array, index, boundsCheck, storeCheck, resolvedType,
                                nullCheckedValue, shift);
                trueEnd = b.add(new EndNode());
                if (hasNoNext(trueBegin)) {
                    if (firstFixedNode instanceof FixedNode fixedNode) {
                        trueBegin.setNext(fixedNode);
                    } else {
                        trueBegin.setNext(trueEnd);
                    }
                }

            } else {
                // we don't know the type at compile time, produce a runtime call
                ForeignCallNode store = b.add(new ForeignCallNode(STORE_UNKNOWN_INLINE, array, index, nullCheckedValue));
                if (hasNoNext(trueBegin))
                    trueBegin.setNext(store);
                trueEnd = b.add(new EndNode());
            }

            // false branch - no flat array
            StoreIndexedNode storeIndexed = b.add(new StoreIndexedNode(array, index, boundsCheck, storeCheck, elementKind, b.maskSubWordValue(value, elementKind)));
            falseBegin.setNext(storeIndexed);
            EndNode falseEnd = b.add(new EndNode());

            // merge
            MergeNode merge = b.add(new MergeNode());
            merge.addForwardEnd(trueEnd);
            merge.addForwardEnd(falseEnd);
            return true;
        }
        return false;
    }

    /**
     *
     * Similar to
     * {@link #genStoreFlatField(GraphBuilderContext, ValueNode, ResolvedJavaField, ValueNode)}, but
     * stores the object as an element into a flat array.
     */
    private ValueNode genStoreFlatFieldToArray(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, HotSpotResolvedObjectType resolvedType,
                    ValueNode value, int shift) {
        HotSpotResolvedObjectType elementType = (HotSpotResolvedObjectType) resolvedType.getComponentType();
        ResolvedJavaField[] innerFields = elementType.getInstanceFields(true);

        List<ValueNode> readOperations = new ArrayList<>();
        List<StoreFlatIndexedNode.StoreIndexedInfo> writeOperations = new ArrayList<>();

        // empty inline type will have no fields
        ValueNode returnValue = null;

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - elementType.firstFieldOffset();

            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), value, innerField));
            readOperations.add(b.maskSubWordValue(load, innerField.getJavaKind()));

            if (i == 0)
                returnValue = load;

            // new holder has a header
            writeOperations.add(new StoreFlatIndexedNode.StoreIndexedInfo(i, innerField.getJavaKind(), off, shift));

        }

        StoreFlatIndexedNode storeFlatIndexedNode = b.add(new StoreFlatIndexedNode(array, index, boundsCheck, storeCheck, elementType.getJavaKind(), writeOperations));
        storeFlatIndexedNode.addValues(readOperations);

        return returnValue;
    }

    private IfNode genFlatArrayCheck(GraphBuilderContext b, ValueNode array, BeginNode trueBegin, BeginNode falseBegin) {
        IsFlatArrayNode isFlatArrayNode = b.add(new IsFlatArrayNode(array));
        LogicNode condition = b.add(new IntegerEqualsNode(isFlatArrayNode, ConstantNode.forInt(1, b.getGraph())));

        // TODO: insert profiling data
        return b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }

    private ValueNode genNullCheck(GraphBuilderContext b, ValueNode value) {
        return genNullCheck(b, value, null);
    }

    private ValueNode genNullCheck(GraphBuilderContext b, ValueNode value, BeginNode begin) {
        return b.nullCheckedValue(value);
    }

    private GuardingNode genStoreCheck(GraphBuilderContext b, GuardingNode storeCheck, ValueNode array, ValueNode value) {
        return genStoreCheck(b, storeCheck, array, value, null);
    }

    private GuardingNode genStoreCheck(GraphBuilderContext b, GuardingNode storeCheck, ValueNode array, ValueNode value, BeginNode begin) {
        if (storeCheck != null)
            return storeCheck;

        LogicNode condition;
        TypeReference arrayType = StampTool.typeReferenceOrNull(array);
        if (arrayType != null && arrayType.isExact()) {
            ResolvedJavaType elementType = arrayType.getType().getComponentType();
            TypeReference typeReference = TypeReference.createTrusted(b.getGraph().getAssumptions(),
                            elementType);
            condition = b.getGraph().addOrUniqueWithInputs(InstanceOfNode.createAllowNull(typeReference,
                            value, null, null));
        } else {
            ValueNode arrayClass = b.add(LoadHubNode.create(array, b.getStampProvider(), b.getMetaAccess(), b.getConstantReflection()));
            ValueNode componentHub = b.add(LoadArrayComponentHubNode.create(arrayClass, b.getStampProvider(), b.getMetaAccess(), b.getConstantReflection()));
            condition = b.add(InstanceOfDynamicNode.create(b.getAssumptions(), b.getConstantReflection(), componentHub, value, true));
        }
        if (condition.isTautology()) {
            // Skip unnecessary guards
            return null;
        }
        FixedGuardNode guard = b.add(new FixedGuardNode(condition, DeoptimizationReason.ArrayStoreException, InvalidateReprofile));
        if (hasNoNext(begin)) {
            begin.setNext(guard);
        }

        return guard;
    }

    private GuardingNode genBoundsCheck(GraphBuilderContext b, GuardingNode boundsCheck, ValueNode array, ValueNode index) {
        return genBoundsCheck(b, boundsCheck, array, index, null);
    }

    private GuardingNode genBoundsCheck(GraphBuilderContext b, GuardingNode boundsCheck, ValueNode array, ValueNode index, BeginNode begin) {
        if (boundsCheck != null)
            return boundsCheck;
        ValueNode length = b.add(ArrayLengthNode.create(array, b.getConstantReflection()));
        if (length instanceof FixedNode fixed && hasNoNext(begin)) {
            begin.setNext(fixed);
        }
        LogicNode condition = b.add(IntegerBelowNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, index, length, NodeView.DEFAULT));
        if (condition.isTautology()) {
            // Skip unnecessary guards
            return null;
        }
        FixedGuardNode guard = b.add(new FixedGuardNode(condition, DeoptimizationReason.BoundsCheckException, InvalidateReprofile));
        if (hasNoNext(begin)) {
            begin.setNext(guard);
        }
        return guard;
    }

    private ValueNode createPositiveIndex(StructuredGraph graph, ValueNode index, GuardingNode boundsCheck) {
        return graph.addOrUnique(PiNode.create(index, POSITIVE_ARRAY_INDEX_STAMP, boundsCheck != null ? boundsCheck.asNode() : null));
    }

    public static boolean hasNoNext(BeginNode begin) {
        return begin != null && begin.next() == null;
    }

    public static final HotSpotForeignCallDescriptor LOAD_UNKNOWN_INLINE = new HotSpotForeignCallDescriptor(SAFEPOINT, NO_SIDE_EFFECT, OBJECT_ARRAY_LOCATION, "loadUnknownInline", Object.class,
                    Object.class,
                    int.class);

    public static final HotSpotForeignCallDescriptor STORE_UNKNOWN_INLINE = new HotSpotForeignCallDescriptor(LEAF, HAS_SIDE_EFFECT, OBJECT_ARRAY_LOCATION, "storeUnknownInline", void.class,
                    Object.class,
                    int.class, Object.class);
}
