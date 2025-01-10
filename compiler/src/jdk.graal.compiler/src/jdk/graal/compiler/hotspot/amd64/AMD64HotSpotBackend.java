/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.hotspot.amd64;

import static jdk.graal.compiler.core.common.GraalOptions.ZapStackOnMethodEntry;
import static jdk.vm.ci.amd64.AMD64.r10;
import static jdk.vm.ci.amd64.AMD64.r13;
import static jdk.vm.ci.amd64.AMD64.r14;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rbp;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmm8;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import java.util.Arrays;

import jdk.graal.compiler.asm.BranchTargetOutOfBoundsException;
import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.amd64.AMD64Address;
import jdk.graal.compiler.asm.amd64.AMD64Assembler;
import jdk.graal.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import jdk.graal.compiler.asm.amd64.AMD64BaseAssembler;
import jdk.graal.compiler.asm.amd64.AMD64MacroAssembler;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.amd64.AMD64NodeMatchRules;
import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.gen.LIRGenerationProvider;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.HotSpotDataBuilder;
import jdk.graal.compiler.hotspot.HotSpotGraalRuntimeProvider;
import jdk.graal.compiler.hotspot.HotSpotHostBackend;
import jdk.graal.compiler.hotspot.HotSpotLIRGenerationResult;
import jdk.graal.compiler.hotspot.HotSpotMarkId;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.stubs.Stub;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.amd64.AMD64Call;
import jdk.graal.compiler.lir.amd64.AMD64FrameMap;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.asm.CompilationResultBuilderFactory;
import jdk.graal.compiler.lir.asm.DataBuilder;
import jdk.graal.compiler.lir.asm.EntryPointDecorator;
import jdk.graal.compiler.lir.asm.FrameContext;
import jdk.graal.compiler.lir.framemap.FrameMap;
import jdk.graal.compiler.lir.framemap.FrameMapBuilder;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.gc.BarrierSet;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

/**
 * HotSpot AMD64 specific backend.
 */
public class AMD64HotSpotBackend extends HotSpotHostBackend implements LIRGenerationProvider {

    public AMD64HotSpotBackend(GraalHotSpotVMConfig config, HotSpotGraalRuntimeProvider runtime, HotSpotProviders providers) {
        super(config, runtime, providers);
    }

    @Override
    protected FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig, Stub stub) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        AMD64FrameMap frameMap = new AMD64HotSpotFrameMap(getCodeCache(), registerConfigNonNull, this, config.preserveFramePointer(stub != null));
        return new AMD64HotSpotFrameMapBuilder(frameMap, getCodeCache(), registerConfigNonNull);
    }

    @Override
    public LIRGeneratorTool newLIRGenerator(LIRGenerationResult lirGenRes) {
        return new AMD64HotSpotLIRGenerator(getProviders(), config, lirGenRes);
    }

    @Override
    public NodeLIRBuilderTool newNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        return new AMD64HotSpotNodeLIRBuilder(graph, lirGen, new AMD64NodeMatchRules(lirGen));
    }

    @Override
    protected void bangStackWithOffset(CompilationResultBuilder crb, int bangOffset) {
        AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
        int pos = asm.position();
        asm.movl(new AMD64Address(rsp, -bangOffset), AMD64.rax);
        assert asm.position() - pos >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE : asm.position() + "-" + pos;
    }

    /**
     * The size of the instruction used to patch the verified entry point of an nmethod when the
     * nmethod is made non-entrant or a zombie (e.g. during deopt or class unloading). The first
     * instruction emitted at an nmethod's verified entry point must be at least this length to
     * ensure mt-safe patching.
     */
    public static final int PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE = 5;

    /**
     * Emits code at the verified entry point and return point(s) of a method.
     */
    public class HotSpotFrameContext implements FrameContext {

        final boolean isStub;
        private final EntryPointDecorator entryPointDecorator;

        HotSpotFrameContext(boolean isStub, EntryPointDecorator entryPointDecorator) {
            this.isStub = isStub;
            this.entryPointDecorator = entryPointDecorator;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            AMD64FrameMap frameMap = (AMD64FrameMap) crb.frameMap;
            int frameSize = frameMap.frameSize();
            AMD64HotSpotMacroAssembler asm = (AMD64HotSpotMacroAssembler) crb.asm;

            int verifiedEntryPointOffset = asm.position();
            if (!isStub) {
                emitStackOverflowCheck(crb);
                // assert asm.position() - verifiedEntryPointOffset >=
                // PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
            }
            if (frameMap.preserveFramePointer()) {
                // Stack-walking friendly instructions
                asm.push(rbp);
                asm.movq(rbp, rsp);
            }
            if (!isStub && asm.position() == verifiedEntryPointOffset) {
                asm.subqWide(rsp, frameSize);
                assert asm.position() - verifiedEntryPointOffset >= PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE : asm.position() + "-" + verifiedEntryPointOffset;
            } else {
                asm.decrementq(rsp, frameSize);
            }

            assert frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;

            if (!isStub && config.nmethodEntryBarrier != 0) {
                emitNmethodEntryBarrier(crb, asm);
            } else {
                crb.recordMark(HotSpotMarkId.FRAME_COMPLETE);
            }

            if (entryPointDecorator != null) {
                entryPointDecorator.emitEntryPoint(crb, false);
            }

            if (ZapStackOnMethodEntry.getValue(crb.getOptions())) {
                final int intSize = 4;
                for (int i = 0; i < frameSize / intSize; ++i) {
                    asm.movl(new AMD64Address(rsp, i * intSize), 0xC1C1C1C1);
                }
            }
        }

        private void emitNmethodEntryBarrier(CompilationResultBuilder crb, AMD64HotSpotMacroAssembler asm) {
            GraalError.guarantee(HotSpotMarkId.ENTRY_BARRIER_PATCH.isAvailable(), "must be available");
            ForeignCallLinkage callTarget = getForeignCalls().lookupForeignCall(HotSpotHostForeignCallsProvider.NMETHOD_ENTRY_BARRIER);

            // The assembly sequence is from
            // src/hotspot/cpu/x86/gc/shared/barrierSetAssembler_x86.cpp. It was improved in
            // JDK 20 to be more efficient.
            final Label continuation = new Label();
            final Label entryPoint = new Label();

            /*
             * The following code sequence must be emitted in exactly this fashion as HotSpot will
             * check that the barrier is the expected code sequence.
             */
            asm.align(4);
            crb.recordMark(HotSpotMarkId.FRAME_COMPLETE);
            crb.recordMark(HotSpotMarkId.ENTRY_BARRIER_PATCH);
            asm.nmethodEntryCompare(config.threadDisarmedOffset);
            asm.jcc(ConditionFlag.NotEqual, entryPoint);
            crb.getLIR().addSlowPath(null, () -> {
                asm.bind(entryPoint);
                /*
                 * The nmethod entry barrier can deoptimize by manually removing this frame. It
                 * makes some assumptions about the frame layout that aren't always true for Graal.
                 * In particular it assumes the caller`s rbp is always saved in the standard
                 * location. With -XX:+PreserveFramePointer this has been done by the frame setup.
                 * Otherwise it is only saved lazily (i.e. if rbp is actually used by the register
                 * allocator). Since nmethod entry barriers are enabled, the space for rbp has been
                 * reserved in the frame and here we ensure it is properly saved before calling the
                 * nmethod entry barrier.
                 */
                AMD64HotSpotFrameMap frameMap = (AMD64HotSpotFrameMap) crb.frameMap;
                if (!frameMap.preserveFramePointer()) {
                    asm.movq(new AMD64Address(rsp, frameMap.offsetForStackSlot(frameMap.getRBPSpillSlot())), rbp);
                }
                // This is always a near call
                asm.call((before, after) -> {
                    crb.recordDirectCall(before, after, callTarget, null);
                }, callTarget);

                // Return to inline code
                asm.jmp(continuation);
            });
            asm.bind(continuation);
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            AMD64HotSpotFrameMap frameMap = (AMD64HotSpotFrameMap) crb.frameMap;
            AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
            assert frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;

            if (frameMap.preserveFramePointer()) {
                asm.movq(rsp, rbp);
                asm.pop(rbp);
            } else {
                asm.incrementq(rsp, frameMap.frameSize());
            }
        }

        @Override
        public void returned(CompilationResultBuilder crb) {
            // nothing to do
        }

        public void rawEnter(CompilationResultBuilder crb) {
            AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
            AMD64FrameMap frameMap = (AMD64FrameMap) crb.frameMap;

            if (frameMap.preserveFramePointer()) {
                // Stack-walking friendly instructions
                asm.push(rbp);
                asm.movq(rbp, rsp);
            }
            asm.decrementq(rsp, frameMap.frameSize());
        }

        public void rawLeave(CompilationResultBuilder crb) {
            leave(crb);
        }
    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenRen, FrameMap frameMap, CompilationResult compilationResult, CompilationResultBuilderFactory factory,
                    EntryPointDecorator entryPointDecorator) {
        // Omit the frame if the method:
        // - has no spill slots or other slots allocated during register allocation
        // - has no callee-saved registers
        // - has no incoming arguments passed on the stack
        // - has no deoptimization points
        // - makes no foreign calls (which require an aligned stack)
        HotSpotLIRGenerationResult gen = (HotSpotLIRGenerationResult) lirGenRen;
        LIR lir = gen.getLIR();
        assert gen.getDeoptimizationRescueSlot() == null || frameMap.frameNeedsAllocating() : "method that can deoptimize must have a frame";
        OptionValues options = lir.getOptions();
        DebugContext debug = lir.getDebug();

        Stub stub = gen.getStub();
        AMD64MacroAssembler masm = new AMD64HotSpotMacroAssembler(config, getTarget(), options, getProviders(), config.CPU_HAS_INTEL_JCC_ERRATUM);
        HotSpotFrameContext frameContext = new HotSpotFrameContext(stub != null, entryPointDecorator);
        DataBuilder dataBuilder = new HotSpotDataBuilder(getCodeCache().getTarget());
        CompilationResultBuilder crb = factory.createBuilder(getProviders(), frameMap, masm, dataBuilder, frameContext, options, debug, compilationResult, Register.None, lir);
        crb.setTotalFrameSize(frameMap.totalFrameSize());
        crb.setMaxInterpreterFrameSize(gen.getMaxInterpreterFrameSize());
        crb.setMinDataSectionItemAlignment(getMinDataSectionItemAlignment());
        StackSlot deoptimizationRescueSlot = gen.getDeoptimizationRescueSlot();
        if (deoptimizationRescueSlot != null && stub == null) {
            crb.compilationResult.setCustomStackAreaOffset(deoptimizationRescueSlot);
        }

        if (stub != null) {
            updateStub(stub, gen, frameMap);
        }

        return crb;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        emitCodeHelper(crb, installedCodeOwner, entryPointDecorator);
        if (GraalOptions.OptimizeLongJumps.getValue(crb.getOptions())) {
            optimizeLongJumps(crb, installedCodeOwner, entryPointDecorator);
        }
    }

    private void emitCodeHelper(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
        FrameMap frameMap = crb.frameMap;
        RegisterConfig regConfig = frameMap.getRegisterConfig();

        // Emit the prefix
        emitCodePrefix(installedCodeOwner, crb, asm, regConfig);

        if (entryPointDecorator != null) {
            entryPointDecorator.emitEntryPoint(crb, true);
        }

        // Emit code for the LIR
        crb.emitLIR();

        // Emit the suffix
        emitCodeSuffix(crb, asm);
    }

    // see MacroAssembler::unpack_inline_args
    public void unpackInlineArgs(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, boolean receiverOnly) {

        // VIEP: nothing scalarized yet
        // VIEP_RO: everything except receiver already scalarized
        JavaType[] currentParameterTypes = receiverOnly ? installedCodeOwner.getScalarizedParameters(false) : getParameters(installedCodeOwner); // TODO
        CallingConvention currentCC = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, currentParameterTypes, this);

        // VEP: the parameters that are expected
        JavaType[] expectedParameterTypes = installedCodeOwner.getScalarizedParameters(true);
        CallingConvention expectedCC = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, expectedParameterTypes, this);

        if (true /* receiver_only */) {
            CallingConvention sig;/* = sig_cc_R0 */
        } else {
            CallingConvention sig;/* = sig */
        }
        CallingConvention sig_cc; /* sig_cc */

        int currentStackSizeArguments = currentCC.getStackSize(); /* sig args on stack */
        AllocatableValue[] currentArguments = currentCC.getArguments();
        int expectedStackSizeArguments = expectedCC.getStackSize(); /* sig_cc args on stack */
        AllocatableValue[] expectedArguments = expectedCC.getArguments();

        int spInc = 0;
        if (expectedStackSizeArguments > currentStackSizeArguments) {
            spInc = extendStackForInlineArgs(installedCodeOwner, crb, asm, regConfig, expectedStackSizeArguments, receiverOnly);
        }

        shuffleInlineArgs(installedCodeOwner, crb, asm, receiverOnly, currentParameterTypes, currentArguments, currentStackSizeArguments, expectedParameterTypes, expectedArguments,
                        expectedStackSizeArguments,
                        spInc);

    }

/*
 * receiver_only, sig, args_passed, args_on_stack, regs, // from args_passed_cc, args_on_stack_cc,
 * regs_cc, // to sp_inc
 */

    public JavaType[] getParameters(ResolvedJavaMethod method) {
        Signature sig = method.getSignature();
        int sigCount = sig.getParameterCount(false);
        JavaType[] argTypes;
        int argIndex = 0;
        if (!method.isStatic()) {
            argTypes = new JavaType[sigCount + 1];
            argTypes[argIndex++] = method.getDeclaringClass();
        } else {
            argTypes = new JavaType[sigCount];
        }
        for (int i = 0; i < sigCount; i++) {
            argTypes[argIndex++] = sig.getParameterType(i, null);
        }
        return argTypes;
    }

    public void shuffleInlineArgs(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, boolean receiverOnly, JavaType[] currentParameterTypes,
                    AllocatableValue[] currentArguments, int currentStackSizeArguments,
                    JavaType[] expectedParameterTypes, AllocatableValue[] expectedArguments, int expectedStackSizeArguments, int spInc) {

        State[] state = initRegState(currentArguments, currentStackSizeArguments, expectedStackSizeArguments, spInc);

        // Emit code for unpacking inline type arguments
        // We try multiple times and eventually start spilling to resolve (circular) dependencies
        int currentArgsPassedLength = currentArguments.length;
        int expectedArgsPassedLength = expectedArguments.length;
        boolean done = (expectedArgsPassedLength == 0);
        for (int i = 0; i < 2 * expectedArgsPassedLength && !done; i++) {
            done = true;
            boolean spill = (i > expectedArgsPassedLength);
            // iterate over arguments in revers
            int step = -1;
            int fromIndex = currentArgsPassedLength - 1;
            int toIndex = expectedArgsPassedLength - 1;
            int signatureLength = installedCodeOwner.getSignature().getParameterCount(true);
            int signatureIndex = signatureLength;
            int signatureIndexEnd = -1;
            int vTargIndex = 0;

            for (; signatureIndex != signatureIndexEnd; signatureIndex += step) {
                assert 0 <= signatureIndex && signatureIndex < signatureLength : "index out of bounds";
                if (spill) {
                    spill = shuffleInlineArgsSpill(installedCodeOwner, crb, asm, state, currentArguments, currentParameterTypes, fromIndex);
                }
                JavaKind kind = currentParameterTypes[signatureIndex].getJavaKind();
                boolean isScalarized = signatureIndex == 0 ? installedCodeOwner.hasScalarizedReceiver() : installedCodeOwner.isScalarizedParameter(signatureIndex - 1);

                // for receiver only all parameters except receiver are already scalarized so just
                // move then
                boolean result = true;
                if (isScalarized && (!receiverOnly || signatureIndex == 0)) {
                    AllocatableValue fromArgument = currentArguments[fromIndex];
                    // TODO: unpackInline Helper
                    result = unpackInlineHelper();
                    if (fromIndex == -1 && signatureIndex != 0) {
                        assert receiverOnly : "sanity";
                        fromIndex = 0;
                    }
                } else {
                    AllocatableValue fromArgument = currentArguments[fromIndex];
                    AllocatableValue toArgument = expectedArguments[toIndex];
                    result = moveHelper(crb, asm, fromArgument, toArgument, kind, state);

                    toIndex += step;
                    fromIndex += step;
                }
                if (done && result)
                    done = true;
                else
                    done = false;

            }
        }

    }

    public boolean unpackInlineHelper(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, int signatureIndex, AllocatableValue fromValue,
                    AllocatableValue[] toValues,
                    int toIndex,
                    State[] state) {

        AMD64FrameMap frameMap = (AMD64FrameMap) crb.frameMap;
        boolean receiver = signatureIndex == 0;
        assert receiver && installedCodeOwner.hasScalarizedReceiver() || installedCodeOwner.isScalarizedParameter(signatureIndex - 1) : "should be scalarized type";
        assert !ValueUtil.isIllegal(fromValue) : "source must be valid";
        boolean progress = false;
        final Label labelIsNull = new Label();
        final Label labelIsNotNull = new Label();
        // Don't use r14 as tmp because it's used for spilling spillRegFor

        Register tmp1 = r10;
        Register tmp2 = r13;
        Register fromReg = Register.None;

        int fromValueIndex = argumentToStateIndex(fromValue);

        JavaType[] types = receiver ? installedCodeOwner.getScalarizedReceiver() : installedCodeOwner.getScalarizedParameter(signatureIndex - 1);
        // TODO: nicer interface
        ResolvedJavaField[] fields = installedCodeOwner.getSignature().getParameterType(signatureIndex - 1, installedCodeOwner.getDeclaringClass()).resolve(
                        installedCodeOwner.getDeclaringClass()).getInstanceFields(true);
        boolean done = true;
        boolean markDone = true;
        AllocatableValue toValue = null;

        // receiver is null-free
        boolean nullCheck = !receiver && installedCodeOwner.isParameterNullFree(signatureIndex - 1);

        // we traverse from back therefore null check field subtract to get nullcheck field
        AllocatableValue nullCheckValue = nullCheck ? toValues[toIndex - (types.length - 1)] : null;
        for (int i = 0; i < types.length; i++) {
            JavaKind kind = types[types.length - i - 1].getJavaKind();
            toValue = toValues[toIndex - i];
            assert !ValueUtil.isIllegal(toValue) : "destination must be valid";
            int toValueIndex = argumentToStateIndex(toValue);
            if (state[toValueIndex] == State.READ_ONLY) {
                if (!toValue.equals(fromValue)) {
                    markDone = false;
                }
                done = false;
                continue;
            } else if (state[toValueIndex] == State.WRITTEN) {
                continue;
            }
            assert state[toValueIndex] == State.WRITEABLE : "must be writable";
            state[toValueIndex] = State.WRITTEN;
            progress = true;

            if (fromReg == Register.None) {
                if (ValueUtil.isRegister(fromValue)) {
                    fromReg = ValueUtil.asRegister(fromValue);
                } else {
                    AMD64Address fromAddress = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(fromValue)));
                    asm.movq(tmp1, fromAddress);
                    fromReg = tmp1;
                }
                if (nullCheck) {
                    // Nullable inline type argument, emit null check
                    asm.testq(fromReg, fromReg);
                    asm.jcc(ConditionFlag.Zero, labelIsNull);
                }
            }
            if (i == types.length - 1 && nullCheck) {
                if (ValueUtil.isStackSlot(toValue)) {
                    AMD64Address address = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(toValue)));
                    // TODO: is new assembly correct?
                    asm.movq(address, 1);
                } else {
                    asm.movq(ValueUtil.asRegister(toValue), 1);
                }
                continue;
            }

            AMD64Address fromAddress = new AMD64Address(fromReg, fields[fields.length - i - 1].getOffset());
            if (ValueUtil.isRegister(toValue) && !isXMMRegister(ValueUtil.asRegister(toValue))) {
                Register dst = ValueUtil.isStackSlot(toValue) ? tmp2 : ValueUtil.asRegister(toValue);
                if (kind == JavaKind.Object) {
                    // TODO: need a barrier?
                    // asm.movptr(dst, fromAddress);
                    BarrierSet barrierSet = crb.getPlatformConfigurationProvider().getBarrierSet();
                    BarrierType barrierType = barrierSet.fieldReadBarrierType(fields[fields.length - i - 1],
                                    crb.getMetaAccessExtensionProvider().getStorageKind(fields[fields.length - i - 1].getType()));

                } else {
                    boolean isSigned = kind != JavaKind.Char && kind != JavaKind.Boolean;
                    loadSizedValue(asm, dst, fromAddress, kind.getByteCount(), isSigned);
                }
                if (ValueUtil.isStackSlot(toValue)) {
                    AMD64Address address = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(toValue)));
                    asm.movq(address, dst);
                }
            } else if (kind == JavaKind.Double) {
                asm.movdbl(ValueUtil.asRegister(toValue), fromAddress);
            } else {
                assert kind == JavaKind.Float : "must be float";
                asm.movflt(ValueUtil.asRegister(toValue), fromAddress);
            }

        }
        if (progress && nullCheck) {
            if (done) {
                asm.jmp(labelIsNotNull);
                asm.bind(labelIsNull);
                // Set IsNotNull field to zero to signal that the argument is null.
                // Also set all oop fields to zero to make the GC happy.
                for (int i = 0; i < types.length; i++) {
                    JavaKind kind = types[types.length - i - 1].getJavaKind();
                    if (i == types.length - 1 && nullCheck || kind == JavaKind.Object) {
                        if (ValueUtil.isStackSlot(toValue)) {
                            AMD64Address address = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(toValue)));
                            asm.movq(address, 0);
                        } else {
                            asm.xorq(ValueUtil.asRegister(toValue), ValueUtil.asRegister(toValue));
                        }
                    }
                }
            } else {
                asm.bind(labelIsNull);
            }
        }

        if (markDone && state[fromValueIndex] != State.WRITTEN) {
            // This is okay because no one else will write to that slot
            state[fromValueIndex] = State.WRITEABLE;
        }
        fromValueIndex--;
        signatureIndex--;
        toIndex -= types.length;
        return done;

    }

    public void loadSizedValue(AMD64MacroAssembler asm, Register dst, AMD64Address src, int sizeInBytes, boolean isSigned) {
        switch (sizeInBytes) {
            case 8:
                asm.movq(dst, src);
                break;
            case 4:
                asm.movl(dst, src);
                break;
            case 2:
                if (isSigned) {
                    loadSignedShort(asm, dst, src);
                } else {
                    loadUnsignedShort(asm, dst, src);
                }
                break;
            case 1:
                if (isSigned) {
                    loadSignedByte(asm, dst, src);
                } else {
                    loadUnsignedByte(asm, dst, src);
                }
                break;
        }
    }

    public void loadSignedShort(AMD64MacroAssembler asm, Register dst, AMD64Address src) {
        asm.movswl(dst, src);
    }

    public void loadUnsignedShort(AMD64MacroAssembler asm, Register dst, AMD64Address src) {
        asm.movzwl(dst, src);
    }

    public void loadSignedByte(AMD64MacroAssembler asm, Register dst, AMD64Address src) {
        asm.movsbl(dst, src);
    }

    public void loadUnsignedByte(AMD64MacroAssembler asm, Register dst, AMD64Address src) {
        asm.movzbl(dst, src);
    }

    public boolean shuffleInlineArgsSpill(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, State[] state, AllocatableValue[] currentArguments,
                    JavaType[] currentParameterTypes, int fromIndex) {
        AllocatableValue fromArgument = currentArguments[fromIndex];
        JavaKind fromKind = currentParameterTypes[fromIndex].getJavaKind();
        int fromArgumentStateIndex = argumentToStateIndex(fromArgument);
        State fromArgumentState = state[fromArgumentStateIndex];
        if (fromArgumentState != State.READ_ONLY) {
            // spilling this won't break any cycles
            return true;
        }

        // Spill argument to be able to write the source and resolve circular dependencies
        AllocatableValue spilled = spillRegFor(fromArgument);
        int spilledArgumentStateIndex = argumentToStateIndex(spilled);
        if (state[spilledArgumentStateIndex] == State.READ_ONLY) {
            // We have already spilled (in previous round). The spilled register should be consumed
            // by this round.
        } else {
            boolean result = moveHelper(crb, asm, fromArgument, spilled, fromKind, state);
            assert result : "spilling should not fail";
            // Set spill_reg as new source and update state
            currentArguments[fromIndex] = spilled;
            state[spilledArgumentStateIndex] = State.READ_ONLY;
        }

        // Do not spill again in this round
        return false;
    }

    private AllocatableValue spillRegFor(AllocatableValue value) {
        if (ValueUtil.isRegister(value) && ValueUtil.asRegister(value).getRegisterCategory().equals(AMD64.XMM)) {
            return xmm8.asValue(value.getValueKind());
        }
        return r14.asValue(value.getValueKind());
    }

    private boolean moveHelper(CompilationResultBuilder crb, AMD64MacroAssembler asm, AllocatableValue fromValue, AllocatableValue toValue, JavaKind kind, State[] state) {
        assert !ValueUtil.isIllegal(fromValue) && !ValueUtil.isIllegal(toValue) : "source and destination must be valid";
        AMD64FrameMap frameMap = (AMD64FrameMap) crb.frameMap;
        int toValueIndex = argumentToStateIndex(toValue);
        int fromValueIndex = argumentToStateIndex(fromValue);
        if (state[toValueIndex] == State.WRITTEN) {
            // already written
            return true;
        }
        if (!fromValue.equals(toValue)) {
            if (state[toValueIndex] == State.READ_ONLY) {
                return false;
            }
        }
        if (ValueUtil.isRegister(fromValue)) {
            if (ValueUtil.isRegister(toValue)) {
                if (isXMMRegister(ValueUtil.asRegister(fromValue))) {
                    if (kind == JavaKind.Double) {
                        asm.movdbl(ValueUtil.asRegister(toValue), ValueUtil.asRegister(fromValue));
                    } else {
                        assert kind == JavaKind.Float : "kind must be float";
                        asm.movflt(ValueUtil.asRegister(toValue), ValueUtil.asRegister(fromValue));
                    }
                } else {
                    asm.movq(ValueUtil.asRegister(toValue), ValueUtil.asRegister(fromValue));
                }
            } else {
                // TODO Address from_addr = Address(rsp, from->reg2stack() *
                // VMRegImpl::stack_slot_size + wordSize); + wordSize because RBP not included in
                // their case
                AMD64Address toAddress = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(toValue)));
                if (isXMMRegister(ValueUtil.asRegister(fromValue))) {
                    if (kind == JavaKind.Double) {
                        asm.movdbl(toAddress, ValueUtil.asRegister(fromValue));
                    } else {
                        assert kind == JavaKind.Float : "kind must be float";
                        asm.movflt(toAddress, ValueUtil.asRegister(fromValue));
                    }
                } else {
                    asm.movq(toAddress, ValueUtil.asRegister(fromValue));
                }
            }
        } else {
            AMD64Address fromAddress = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(fromValue)));
            if (ValueUtil.isRegister(toValue)) {
                if (isXMMRegister(ValueUtil.asRegister(toValue))) {
                    if (kind == JavaKind.Double) {
                        asm.movdbl(ValueUtil.asRegister(toValue), fromAddress);
                    } else {
                        assert kind == JavaKind.Float : "kind must be float";
                        asm.movflt(ValueUtil.asRegister(toValue), fromAddress);
                    }
                } else {
                    asm.movq(ValueUtil.asRegister(fromValue), fromAddress);
                }
            } else {
                AMD64Address toAddress = new AMD64Address(rsp, frameMap.offsetForStackSlot(ValueUtil.asStackSlot(toValue)));
                asm.movq(r13, fromAddress);
                asm.movq(toAddress, r13);
            }

        }

        // update states
        state[fromValueIndex] = State.WRITEABLE;
        state[toValueIndex] = State.WRITTEN;
        return true;
    }

    private boolean isXMMRegister(Register register) {
        return register.getRegisterCategory().equals(AMD64.XMM);
    }

    public State[] initRegState(AllocatableValue[] currentArguments, int currentStackSizeArguments, int expectedStackSizeArguments, int spInc) {
        RegisterArray registers = getTarget().arch.getAvailableValueRegisters();
        int currentArgsOnStack = currentStackSizeArguments / getTarget().wordSize;
        int expectedArgsOnStack = expectedStackSizeArguments / getTarget().wordSize;
        State[] state;
        // no additional stack slots
        if (spInc == 0) {
            state = new State[registers.size() + currentArgsOnStack];
        } else {
            // include all registers, the current args and the increased stack space and RA
            state = new State[registers.size() + currentArgsOnStack + expectedArgsOnStack + 1];
        }

        // initialize the state, set all locations to writeable
        Arrays.fill(state, State.WRITEABLE);

        // correct the stack offset of the current args by the sp inc
        for (int i = 0; i < currentArguments.length; i++) {
            if (currentArguments[i] instanceof StackSlot stackSlot) {
                StackSlot newStackSlot = StackSlot.get(stackSlot.getValueKind(), stackSlot.getRawOffset() + spInc, stackSlot.getRawAddFrameSize());
                currentArguments[i] = newStackSlot;

                // make the slot read only to prevent accidental writing
                state[argumentToStateIndex(newStackSlot)] = State.READ_ONLY;
            }
        }

        return state;
    }

    public static enum State {
        READ_ONLY,
        WRITEABLE,
        WRITTEN
    }

    /**
     * calculates the index for the state
     */
    public int argumentToStateIndex(AllocatableValue argument) {
        // include all registers, TODO: only the ones used for the calling convention
        RegisterArray registers = getTarget().arch.getAvailableValueRegisters();
        for (int i = 0; i < registers.size(); i++) {
            if (argument instanceof RegisterValue registerValue) {
                if (registerValue.getRegister().equals(registers.get(i))) {
                    return i;
                }
            } else {
                break;
            }
        }

        // no register calculate index from offset, every stack slot is 8 byte aligned
        int regNumber = registers.size();
        assert argument instanceof StackSlot : "expected stack slot in calling convention";
        return regNumber + ((StackSlot) argument).getRawOffset() / getTarget().wordSize;
    }

    public int extendStackForInlineArgs(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, int argsOnStack, boolean receiverOnly) {
        JavaType[] parameterTypes = installedCodeOwner.getScalarizedParameters(false);
        CallingConvention cc = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes, this);

        int RAsize = crb.target.arch.getReturnAddressSize();
        int spInc = (cc.getStackSize() + RAsize);
        asm.pop(r13);
        asm.decrementq(rsp, spInc);
        asm.push(r13);
        return spInc;
    }

    /**
     * Emits the code prior to the verified entry point.
     *
     * @param installedCodeOwner see {@link LIRGenerationProvider#emitCode}
     */
    public void emitCodePrefix(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig) {
        HotSpotProviders providers = getProviders();
        if (installedCodeOwner != null && !installedCodeOwner.isStatic()) {
            JavaType[] parameterTypes = {providers.getMetaAccess().lookupJavaType(Object.class)};
            CallingConvention cc = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes, this);
            Register receiver = asRegister(cc.getArgument(0));
            AMD64Address src = new AMD64Address(receiver, config.hubOffset);
            int before;
            if (config.icSpeculatedKlassOffset == Integer.MAX_VALUE) {
                crb.recordMark(HotSpotMarkId.UNVERIFIED_ENTRY);
                /*
                 * Just set the new entry point to the same position for the moment. TODO produce a
                 * correct code prefix for the new entry points
                 */
                // crb.recordMark(HotSpotMarkId.INLINE_ENTRY);
                // c1_LIRAssembler_x86.cpp: const Register IC_Klass = rax;
                Register inlineCacheKlass = rax;

                if (config.useCompressedClassPointers) {
                    Register register = r10;
                    Register heapBase = providers.getRegisters().getHeapBaseRegister();
                    AMD64HotSpotMove.decodeKlassPointer(asm, register, heapBase, src, config);
                    if (config.narrowKlassBase != 0) {
                        // The heap base register was destroyed above, so restore it
                        if (config.narrowOopBase == 0L) {
                            asm.xorq(heapBase, heapBase);
                        } else {
                            asm.movq(heapBase, config.narrowOopBase);
                        }
                    }
                    before = asm.cmpqAndJcc(inlineCacheKlass, register, ConditionFlag.NotEqual, null, false);
                } else {
                    before = asm.cmpqAndJcc(inlineCacheKlass, src, ConditionFlag.NotEqual, null, false);
                }
                crb.recordDirectCall(before, asm.position(), getForeignCalls().lookupForeignCall(IC_MISS_HANDLER), null);
            } else {
                // JDK-8322630 (removed ICStubs)
                Register data = rax;
                Register temp = r10;
                ForeignCallLinkage icMissHandler = getForeignCalls().lookupForeignCall(IC_MISS_HANDLER);

                // Size of IC check sequence checked with a guarantee below.
                int inlineCacheCheckSize = 14;
                if (asm.force4ByteNonZeroDisplacements()) {
                    /*
                     * The mov and cmp below each contain a 1-byte displacement that is emitted as 4
                     * bytes instead, thus we have 3 extra bytes for each of these instructions.
                     */
                    inlineCacheCheckSize += 3 + 3;
                }
                asm.align(config.codeEntryAlignment, asm.position() + inlineCacheCheckSize);

                int startICCheck = asm.position();
                crb.recordMark(HotSpotMarkId.UNVERIFIED_ENTRY);
                /*
                 * Just set the new entry point to the same position for the moment. TODO produce a
                 * correct code prefix for the new entry points
                 */
                // crb.recordMark(HotSpotMarkId.INLINE_ENTRY);
                AMD64Address icSpeculatedKlass = new AMD64Address(data, config.icSpeculatedKlassOffset);

                AMD64BaseAssembler.OperandSize size;
                if (config.useCompressedClassPointers) {
                    asm.movl(temp, src);
                    size = AMD64BaseAssembler.OperandSize.DWORD;
                } else {
                    asm.movptr(temp, src);
                    size = AMD64BaseAssembler.OperandSize.QWORD;
                }
                before = asm.cmpAndJcc(size, temp, icSpeculatedKlass, ConditionFlag.NotEqual, null, false);
                crb.recordDirectCall(before, asm.position(), icMissHandler, null);

                int actualInlineCacheCheckSize = asm.position() - startICCheck;
                if (actualInlineCacheCheckSize != inlineCacheCheckSize) {
                    // Code emission pattern has changed: adjust `inlineCacheCheckSize`
                    // initialization above accordingly.
                    throw new GraalError("%s != %s", actualInlineCacheCheckSize, inlineCacheCheckSize);
                }
            }
        }

        asm.align(config.codeEntryAlignment);
        crb.recordMark(crb.compilationResult.getEntryBCI() != -1 ? HotSpotMarkId.OSR_ENTRY : HotSpotMarkId.VERIFIED_ENTRY);
        /*
         * Just set the new entry points to the same position for the moment. TODO produce a correct
         * code prefix for the new entry points
         */
        crb.recordMark(crb.compilationResult.getEntryBCI() != -1 ? HotSpotMarkId.OSR_ENTRY : HotSpotMarkId.VERIFIED_INLINE_ENTRY);
        crb.recordMark(crb.compilationResult.getEntryBCI() != -1 ? HotSpotMarkId.OSR_ENTRY : HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO);
    }

    public void emitCodeSuffix(CompilationResultBuilder crb, AMD64MacroAssembler asm) {
        HotSpotProviders providers = getProviders();
        HotSpotFrameContext frameContext = (HotSpotFrameContext) crb.frameContext;
        if (!frameContext.isStub) {
            HotSpotForeignCallsProvider foreignCalls = providers.getForeignCalls();
            if (crb.getPendingImplicitExceptionList() != null) {
                for (CompilationResultBuilder.PendingImplicitException pendingImplicitException : crb.getPendingImplicitExceptionList()) {
                    // Insert stub code that stores the corresponding deoptimization action &
                    // reason, as well as the failed speculation, and calls into
                    // DEOPT_BLOB_UNCOMMON_TRAP. Note that we use the debugging info at the
                    // exceptional PC that triggers this implicit exception, we cannot touch
                    // any register/stack slot in this stub, so as to preserve a valid mapping for
                    // constructing the interpreter frame.
                    int pos = asm.position();
                    Register thread = getProviders().getRegisters().getThreadRegister();
                    // Store deoptimization reason and action into thread local storage.
                    asm.movl(new AMD64Address(thread, config.pendingDeoptimizationOffset), pendingImplicitException.state.deoptReasonAndAction.asInt());

                    JavaConstant deoptSpeculation = pendingImplicitException.state.deoptSpeculation;
                    if (deoptSpeculation.getJavaKind() == JavaKind.Long) {
                        // Store speculation into thread local storage. As AMD64 does not support
                        // 64-bit long integer memory store, we break it into two 32-bit integer
                        // store.
                        long speculationAsLong = pendingImplicitException.state.deoptSpeculation.asLong();
                        if (NumUtil.isInt(speculationAsLong)) {
                            AMD64Assembler.AMD64MIOp.MOV.emit(asm, AMD64BaseAssembler.OperandSize.QWORD,
                                            new AMD64Address(thread, config.pendingFailedSpeculationOffset), (int) speculationAsLong);
                        } else {
                            asm.movl(new AMD64Address(thread, config.pendingFailedSpeculationOffset), (int) speculationAsLong);
                            asm.movl(new AMD64Address(thread, config.pendingFailedSpeculationOffset + 4), (int) (speculationAsLong >> 32));
                        }
                    } else {
                        assert deoptSpeculation.getJavaKind() == JavaKind.Int : deoptSpeculation;
                        int speculationAsInt = pendingImplicitException.state.deoptSpeculation.asInt();
                        asm.movl(new AMD64Address(thread, config.pendingFailedSpeculationOffset), speculationAsInt);
                    }

                    AMD64Call.directCall(crb, asm, foreignCalls.lookupForeignCall(DEOPT_BLOB_UNCOMMON_TRAP), null, false, pendingImplicitException.state);
                    crb.recordImplicitException(pendingImplicitException.codeOffset, pos, pendingImplicitException.state);
                }
            }
            trampolineCall(crb, asm, foreignCalls.lookupForeignCall(EXCEPTION_HANDLER), HotSpotMarkId.EXCEPTION_HANDLER_ENTRY);
            trampolineCall(crb, asm, foreignCalls.lookupForeignCall(DEOPT_BLOB_UNPACK), HotSpotMarkId.DEOPT_HANDLER_ENTRY);
            if (config.supportsMethodHandleDeoptimizationEntry() && crb.needsMHDeoptHandler()) {
                trampolineCall(crb, asm, foreignCalls.lookupForeignCall(DEOPT_BLOB_UNPACK), HotSpotMarkId.DEOPT_MH_HANDLER_ENTRY);
            }
        }
    }

    private static void trampolineCall(CompilationResultBuilder crb, AMD64MacroAssembler asm, ForeignCallLinkage callTarget, HotSpotMarkId exceptionHandlerEntry) {
        crb.recordMark(AMD64Call.directCall(crb, asm, callTarget, null, false, null), exceptionHandlerEntry);
        // Ensure the return location is a unique pc and that control flow doesn't return here
        asm.halt();
    }

    @Override
    public RegisterAllocationConfig newRegisterAllocationConfig(RegisterConfig registerConfig, String[] allocationRestrictedTo, Object stub) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new AMD64HotSpotRegisterAllocationConfig(registerConfigNonNull, allocationRestrictedTo, config.preserveFramePointer(stub != null));
    }

    /**
     * Performs a code emit from LIR and replaces jumps with 4byte displacement by equivalent
     * instructions with single byte displacement, where possible. If any of these optimizations
     * unexpectedly results in a {@link BranchTargetOutOfBoundsException}, code without any
     * optimized jumps will be emitted.
     */
    private void optimizeLongJumps(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        // triggers a reset of the assembler during which replaceable jumps are identified
        crb.resetForEmittingCode();
        try {
            emitCodeHelper(crb, installedCodeOwner, entryPointDecorator);
        } catch (BranchTargetOutOfBoundsException e) {
            /*
             * Alignments have invalidated the assumptions regarding short jumps. Trigger fail-safe
             * mode and emit unoptimized code.
             */
            AMD64MacroAssembler masm = (AMD64MacroAssembler) crb.asm;
            masm.disableOptimizeLongJumpsAfterException();
            crb.resetForEmittingCode();
            emitCodeHelper(crb, installedCodeOwner, entryPointDecorator);
        }
    }
}
