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

import static jdk.graal.compiler.asm.Assembler.guaranteeDifferentRegisters;
import static jdk.graal.compiler.core.common.GraalOptions.AssemblyGCBarriersSlowPathOnly;
import static jdk.graal.compiler.core.common.GraalOptions.VerifyAssemblyGCBarriers;
import static jdk.graal.compiler.core.common.GraalOptions.ZapStackOnMethodEntry;
import static jdk.vm.ci.amd64.AMD64.r10;
import static jdk.vm.ci.amd64.AMD64.r11;
import static jdk.vm.ci.amd64.AMD64.r13;
import static jdk.vm.ci.amd64.AMD64.r14;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rbp;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.xmm8;
import static jdk.vm.ci.code.CodeUtil.getCallingConvention;
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
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.gen.LIRGenerationProvider;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.HotSpotDataBuilder;
import jdk.graal.compiler.hotspot.HotSpotGraalRuntime;
import jdk.graal.compiler.hotspot.HotSpotGraalRuntimeProvider;
import jdk.graal.compiler.hotspot.HotSpotHostBackend;
import jdk.graal.compiler.hotspot.HotSpotLIRGenerationResult;
import jdk.graal.compiler.hotspot.HotSpotMarkId;
import jdk.graal.compiler.hotspot.amd64.g1.AMD64HotSpotG1BarrierSetLIRTool;
import jdk.graal.compiler.hotspot.amd64.z.AMD64HotSpotZBarrierSetLIRGenerator;
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
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeUtil;
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
import jdk.vm.ci.meta.Value;

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
    public LIRGenerationResult newLIRGenerationResult(CompilationIdentifier compilationId, LIR lir, RegisterAllocationConfig registerAllocationConfig, StructuredGraph graph, Object stub) {
        return new HotSpotLIRGenerationResult(compilationId, lir, newFrameMapBuilderWithStackRepair(registerAllocationConfig.getRegisterConfig(), (Stub) stub, graph.method()),
                        registerAllocationConfig,
                        makeCallingConvention(graph, (Stub) stub), (Stub) stub, config.requiresReservedStackCheck(graph.getMethods()));
    }

    protected FrameMapBuilder newFrameMapBuilderWithStackRepair(RegisterConfig registerConfig, Stub stub, ResolvedJavaMethod rootMethod) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        AMD64FrameMap frameMap = new AMD64HotSpotFrameMap(getCodeCache(), registerConfigNonNull, this, config.preserveFramePointer(stub != null), needStackRepair(rootMethod));
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

        @Override
        public void enter(CompilationResultBuilder crb, int stackIncrement, boolean emitEntryBarrier) {
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

            // TODO: avoid stack increment for dummy frames, do it after nmethod barrier
            if (crb.compilationResult.getMethods() != null && needStackRepair(crb.compilationResult.getMethods()[0])) {
                // method needs stack repair
                // stack increment doesn't include RBP so add it, RA and padding already included
                AMD64HotSpotFrameMap hotSpotFrameMap = (AMD64HotSpotFrameMap) crb.frameMap;
                asm.movptr(new AMD64Address(rsp, frameMap.offsetForStackSlot(hotSpotFrameMap.getStackIncrement())),
                                frameSize + stackIncrement + (!frameMap.preserveFramePointer() ? 0 : getTarget().wordSize));
            }

            if (emitEntryBarrier) {
                // verified entry frame, other entry points have a dummy frame at the beginning
                if (!isStub && config.nmethodEntryBarrier != 0) {
                    emitNmethodEntryBarrier(crb, asm);
                } else {
                    crb.recordMark(HotSpotMarkId.FRAME_COMPLETE);
                }
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
            leave(crb, true);

        }

        @Override
        public void leave(CompilationResultBuilder crb, boolean allowStackRepair) {
            AMD64HotSpotFrameMap frameMap = (AMD64HotSpotFrameMap) crb.frameMap;
            AMD64MacroAssembler asm = (AMD64MacroAssembler) crb.asm;
            assert frameMap.getRegisterConfig().getCalleeSaveRegisters() == null;

            if (crb.compilationResult.getMethods() != null && needStackRepair(crb.compilationResult.getMethods()[0]) && allowStackRepair && crb.compilationResult.getEntryBCI() == -1) {
                // needs stack repair

                if (frameMap.preserveFramePointer()) {
                    // restore the rbp
                    asm.movq(rbp, new AMD64Address(rsp, frameMap.frameSize()));
                }
                // add the stack increment to the rsp, located directly under the rbp
                asm.addq(rsp, new AMD64Address(rsp, frameMap.offsetForStackSlot(frameMap.getStackIncrement())));
            } else {
                if (frameMap.preserveFramePointer()) {
                    asm.movq(rsp, rbp);
                    asm.pop(rbp);
                } else {
                    asm.incrementq(rsp, frameMap.frameSize());
                }
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
    public int unpackInlineArgs(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, boolean receiverOnly) {


        // VIEP: nothing scalarized yet
        // VIEP_RO: everything except receiver already scalarized
        JavaType[] currentParameterTypes = receiverOnly ? rootMethod.getScalarizedParameters(false)
                        : rootMethod.getSignature().toParameterTypes(rootMethod.isStatic() ? null : rootMethod.getDeclaringClass());
        CallingConvention currentCC = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, currentParameterTypes, this);

        // VEP: the parameters that are expected
        JavaType[] expectedParameterTypes = rootMethod.getScalarizedParameters(true);
        CallingConvention expectedCC = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, expectedParameterTypes, this);

        int currentStackSizeArguments = currentCC.getStackSize(); /* sig args on stack */
        AllocatableValue[] currentArguments = currentCC.getArguments();
        int expectedStackSizeArguments = expectedCC.getStackSize(); /* sig_cc args on stack */
        AllocatableValue[] expectedArguments = expectedCC.getArguments();

        int spInc = 0;
        if (expectedStackSizeArguments > currentStackSizeArguments) {
            spInc = extendStackForInlineArgs(rootMethod, crb, asm, regConfig);
        }

        shuffleInlineArgs(rootMethod, crb, asm, receiverOnly, currentParameterTypes, currentArguments, currentStackSizeArguments, expectedArguments,
                        expectedStackSizeArguments,
                        spInc);
        return spInc;
    }


    public void shuffleInlineArgs(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, boolean receiverOnly, JavaType[] currentParameterTypes,
                    AllocatableValue[] currentArguments, int currentStackSizeArguments, AllocatableValue[] expectedArguments, int expectedStackSizeArguments, int spInc) {

        State[] state = initRegState(currentArguments, currentStackSizeArguments, expectedStackSizeArguments, spInc);

        // Emit code for unpacking inline type arguments
        // We try multiple times and eventually start spilling to resolve (circular) dependencies
        int currentArgsPassedLength = currentArguments.length;
        int expectedArgsPassedLength = expectedArguments.length;
        boolean done = (expectedArgsPassedLength == 0);
        for (int i = 0; i < 2 * expectedArgsPassedLength && !done; i++) {
            done = true;
            boolean spill = (i > expectedArgsPassedLength);
            // iterate over arguments in reverse
            int step = -1;
            int fromIndex = currentArgsPassedLength - 1;
            int toIndex = expectedArgsPassedLength - 1;
            int signatureLength = rootMethod.getSignature().getParameterCount(!rootMethod.isStatic());
            int signatureIndex = signatureLength - 1;
            int signatureIndexEnd = -1;

            for (; signatureIndex != signatureIndexEnd; signatureIndex += step) {
                assert 0 <= signatureIndex && signatureIndex < signatureLength : "index out of bounds";
                if (spill) {
                    spill = shuffleInlineArgsSpill(rootMethod, crb, asm, state, currentArguments, currentParameterTypes, fromIndex);
                }
                JavaKind kind;
                if (!rootMethod.isStatic()) {
                    if (signatureIndex == 0) {
                        kind = JavaKind.Object;
                    } else {
                        kind = rootMethod.getSignature().getParameterKind(signatureIndex - 1);
                    }
                } else {
                    kind = rootMethod.getSignature().getParameterKind(signatureIndex);
                }
                boolean isScalarized = rootMethod.isScalarizedParameter(signatureIndex, true);

                // for receiver only all parameters except the receiver are already scalarized so
                // just
                // move them
                if (isScalarized && (!receiverOnly || signatureIndex == 0)) {
                    // parameter which is not scalarized yet
                    AllocatableValue fromArgument = currentArguments[fromIndex];

                    done &= unpackInlineHelper(rootMethod, crb, asm, signatureIndex, fromArgument, expectedArguments, toIndex, state);

                    toIndex -= rootMethod.getScalarizedParameter(signatureIndex, true).length;
                    fromIndex--;
                    if (fromIndex == -1 && signatureIndex != 0) {
                        assert receiverOnly : "sanity";
                        fromIndex = 0;
                    }
                } else if (isScalarized && receiverOnly) {
                    // parameters without receiver already scalarized iterate over them and move
                    JavaType[] types = rootMethod.getScalarizedParameter(signatureIndex, true);
                    for (int j = 0; j < types.length; j++) {
                        AllocatableValue fromArgument = currentArguments[fromIndex];
                        AllocatableValue toArgument = expectedArguments[toIndex];
                        done &= moveHelper(asm, fromArgument, toArgument, types[types.length - j - 1].getJavaKind(), state);

                        toIndex += step;
                        fromIndex += step;
                    }
                } else {
                    // parameter which is not sclarized
                    AllocatableValue fromArgument = currentArguments[fromIndex];
                    AllocatableValue toArgument = expectedArguments[toIndex];
                    done &= moveHelper(asm, fromArgument, toArgument, kind, state);

                    toIndex += step;
                    fromIndex += step;
                }

            }
        }

    }

    public boolean unpackInlineHelper(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, int signatureIndex, AllocatableValue fromValue,
                    AllocatableValue[] toValues,
                    int toIndex,
                    State[] state) {

        assert rootMethod.isScalarizedParameter(signatureIndex, true);
        assert !ValueUtil.isIllegal(fromValue) : "source must be valid";
        boolean progress = false;
        final Label labelIsNull = new Label();
        final Label labelIsNotNull = new Label();
        // Don't use r14 as tmp because it's used for spilling spillRegFor

        Register tmp1 = r10;
        Register tmp2 = r13;
        Register fromReg = Register.None;

        int fromValueIndex = argumentToStateIndex(fromValue);

        JavaType[] types = rootMethod.getScalarizedParameter(signatureIndex, true);

        ResolvedJavaField[] fields = rootMethod.getScalarizedParameterFields(signatureIndex, true);
        boolean done = true;
        boolean markDone = true;
        AllocatableValue toValue = null;

        // receiver is null-free
        boolean nullCheck = !rootMethod.isParameterNullFree(signatureIndex, true);

        // we traverse from back therefore null check field subtract to get nullcheck field
        AllocatableValue nullCheckValue = nullCheck ? toValues[toIndex - (types.length - 1)] : null;
        for (int i = 0; i < types.length; i++) {
            JavaKind kind = types[types.length - i - 1].getJavaKind();
            toValue = toValues[toIndex - i];
            assert !ValueUtil.isIllegal(toValue) : "destination must be valid";
            int toValueIndex = argumentToStateIndex(toValue);
            if (state[toValueIndex] == State.READ_ONLY) {
                if (toValueIndex != argumentToStateIndex(fromValue)) {
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
                    AMD64Address fromAddress = new AMD64Address(rsp, stackSlotToOffset((StackSlot) fromValue));
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
                    AMD64Address address = new AMD64Address(rsp, stackSlotToOffset((StackSlot) toValue));
                    asm.movq(address, 1);
                } else {
                    asm.movq(ValueUtil.asRegister(toValue), 1);
                }
                continue;
            }

            AMD64Address fromAddress = new AMD64Address(fromReg, fields[fields.length - i - 1].getOffset());
            if (!isXMMRegister(toValue)) {
                Register dst = ValueUtil.isStackSlot(toValue) ? tmp2 : ValueUtil.asRegister(toValue);
                if (kind == JavaKind.Object) {
                    loadHeapOop(crb, asm, dst, fromAddress, fields[fields.length - i - 1]);
                } else {
                    boolean isSigned = kind != JavaKind.Char && kind != JavaKind.Boolean;
                    loadSizedValue(asm, dst, fromAddress, kind.getByteCount(), isSigned);
                }
                if (ValueUtil.isStackSlot(toValue)) {
                    AMD64Address address = new AMD64Address(rsp, stackSlotToOffset((StackSlot) toValue));
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
                        toValue = toValues[toIndex - i];
                        if (ValueUtil.isStackSlot(toValue)) {
                            AMD64Address address = new AMD64Address(rsp, stackSlotToOffset((StackSlot) toValue));
                            asm.movq(address, 0);
                        } else {
                            asm.xorq(ValueUtil.asRegister(toValue), ValueUtil.asRegister(toValue));
                        }
                    }
                }
                asm.bind(labelIsNotNull);
            } else {
                asm.bind(labelIsNull);
            }
        }

        if (markDone && state[fromValueIndex] != State.WRITTEN) {
            // This is okay because no one else will write to that slot
            state[fromValueIndex] = State.WRITEABLE;
        }
        return done;

    }

    public int stackSlotToOffset(StackSlot stackSlot) {
        // add RA to raw offset
        return stackSlot.getRawOffset() + getTarget().wordSize;
    }

    private void loadHeapOop(CompilationResultBuilder crb, AMD64MacroAssembler asm, Register dst, AMD64Address fromAddress, ResolvedJavaField field) {
        // asm.movptr(dst, fromAddress);
        AMD64HotSpotMacroAssembler hasm = (AMD64HotSpotMacroAssembler) asm;
        hasm.loadObject(dst, fromAddress);
// if (config.useCompressedOops) {
// AMD64Move.move((AMD64Kind) new
// AMD64HotSpotLIRKindTool().getNarrowOopKind().getPlatformKind(), crb, asm,
// dst.asValue(), dst.asValue());
// new AMD64Move.UncompressPointerOp(dst.asValue(), dst.asValue(),
// getProviders().getRegisters().getHeapBaseRegister().asValue(),
// config.getOopEncoding(), false,
// new AMD64HotSpotLIRKindTool()).emitCode(crb, asm);
            // loaded compressed oop, uncompress it, zero out upper 32 bits

        // hasm.
// asm.movl(dst, dst);
// AMD64Move.UncompressPointerOp.emitUncompressCode(asm, dst,
// config.getOopEncoding().getShift(),
// getProviders().getRegisters().getHeapBaseRegister(), false);
// }
        if (config.gc == HotSpotGraalRuntime.HotSpotGC.Z) {
            ForeignCallLinkage zCallTarget = this.getForeignCalls().lookupForeignCall(HotSpotHostForeignCallsProvider.Z_LOAD_BARRIER);
            emitZBarrier(crb, asm, dst, zCallTarget, fromAddress, false);
        } else if (config.gc == HotSpotGraalRuntime.HotSpotGC.G1) {
            BarrierType barrierType = this.getProviders().getPlatformConfigurationProvider().getBarrierSet().fieldReadBarrierType(field,
                            this.getProviders().getMetaAccessExtensionProvider().getStorageKind(field.getType()));
            if (barrierType == BarrierType.REFERENCE_GET) {
                emitG1Barrier(crb, asm, dst, fromAddress, false);
            }
        }
        // TODO: others don't need a read barrier?
        // config.gc == HotSpotGraalRuntime.HotSpotGC.Epsilon || config.useG1GC()
    }

    public void emitG1Barrier(CompilationResultBuilder crb, AMD64MacroAssembler masm, Register expectedObject, AMD64Address address, boolean nonNull) {
        AMD64HotSpotG1BarrierSetLIRTool tool = new AMD64HotSpotG1BarrierSetLIRTool(config, getProviders());


        // TODO: emit barrier after we stored the value on stack, no need to push
        Register temp = r10;
        Register temp2 = r11;
        Register temp3 = r13;
        masm.push(temp);
        masm.push(temp2);
        masm.push(temp3);


        ForeignCallLinkage callTarget = getForeignCalls().lookupForeignCall(tool.preWriteBarrierDescriptor());

        AMD64Address storeAddress = address;

        Register thread = getProviders().getRegisters().getThreadRegister();
        Register tmp = temp;
        Register previousValue = expectedObject.equals(Value.ILLEGAL) ? temp2 : expectedObject;

        guaranteeDifferentRegisters(thread, tmp, previousValue);

        Label done = new Label();
        Label runtime = new Label();

        AMD64Address markingActive = new AMD64Address(thread, tool.satbQueueMarkingActiveOffset());

        // Is marking active?
        masm.cmpb(markingActive, 0);
        masm.jcc(AMD64Assembler.ConditionFlag.Equal, done);

        // Do we need to load the previous value?
        if (expectedObject.equals(Value.ILLEGAL)) {
            tool.loadObject(masm, previousValue, storeAddress);
        } else {
            // previousValue contains the value
        }

        if (!nonNull) {
            // Is the previous value null?
            masm.testq(previousValue, previousValue);
            masm.jcc(AMD64Assembler.ConditionFlag.Equal, done);
        }

        if (VerifyAssemblyGCBarriers.getValue(crb.getOptions())) {
            tool.verifyOop(masm, previousValue, tmp, temp3, false, true);
        }

        if (AssemblyGCBarriersSlowPathOnly.getValue(crb.getOptions())) {
            masm.jmp(runtime);
        } else {
            AMD64Address satbQueueIndex = new AMD64Address(thread, tool.satbQueueIndexOffset());
            // tmp := *index_adr
            // tmp == 0?
            // If yes, goto runtime
            masm.movq(tmp, satbQueueIndex);
            masm.cmpq(tmp, 0);
            masm.jcc(AMD64Assembler.ConditionFlag.Equal, runtime);

            // tmp := tmp - wordSize
            // *index_adr := tmp
            // tmp := tmp + *buffer_adr
            masm.subq(tmp, 8);
            masm.movptr(satbQueueIndex, tmp);
            AMD64Address satbQueueBuffer = new AMD64Address(thread, tool.satbQueueBufferOffset());
            masm.addq(tmp, satbQueueBuffer);

            // Record the previous value
            masm.movptr(new AMD64Address(tmp, 0), previousValue);
        }
        masm.bind(done);

        masm.pop(temp3);
        masm.pop(temp2);
        masm.pop(temp);

        // Out of line slow path
        crb.getLIR().addSlowPath(null, () -> {
            masm.bind(runtime);
            CallingConvention cc = callTarget.getOutgoingCallingConvention();
            AllocatableValue arg0 = cc.getArgument(0);
            if (arg0 instanceof StackSlot) {
                // AMD64Address slot0 = (AMD64Address) crb.asAddress(arg0);

                AMD64Address slot0 = new AMD64Address(rsp, -getTarget().wordSize * 2);
                masm.movq(slot0, previousValue);
            } else {
                GraalError.shouldNotReachHere("must be StackSlot: " + arg0);
            }
            AMD64Call.directCall(crb, masm, tool.getCallTarget(callTarget), null, false, null);
            masm.jmp(done);
        });
    }


    public static void emitZBarrier(CompilationResultBuilder crb,
                    AMD64MacroAssembler masm,
                    Register resultReg,
                    ForeignCallLinkage callTarget,
                    AMD64Address address,
                    boolean isNotStrong) {
        assert !resultReg.equals(address.getBase()) && !resultReg.equals(address.getIndex()) : Assertions.errorMessage(resultReg, address);

        final Label entryPoint = new Label();
        final Label continuation = new Label();

        if (isNotStrong) {
            masm.testl(resultReg, AMD64HotSpotZBarrierSetLIRGenerator.UNPATCHED);
            crb.recordMark(HotSpotMarkId.Z_BARRIER_RELOCATION_FORMAT_MARK_BAD_AFTER_TEST);
            masm.jcc(AMD64Assembler.ConditionFlag.NotZero, entryPoint);
            AMD64HotSpotZBarrierSetLIRGenerator.zUncolor(crb, masm, resultReg);
        } else {
            AMD64HotSpotZBarrierSetLIRGenerator.zUncolor(crb, masm, resultReg);
            masm.jcc(AMD64Assembler.ConditionFlag.Above, entryPoint);
        }
        masm.bind(entryPoint);

        CallingConvention cc = callTarget.getOutgoingCallingConvention();
        AMD64Address cArg0 = (AMD64Address) crb.asAddress(cc.getArgument(0));
        AMD64Address cArg1 = (AMD64Address) crb.asAddress(cc.getArgument(1));

        // The fast-path shift destroyed the oop - need to re-read it
        masm.movq(resultReg, address);

        masm.movq(cArg0, resultReg);
        masm.leaq(resultReg, address);
        masm.movq(cArg1, resultReg);
        AMD64Call.directCall(crb, masm, callTarget, null, false, null);
        masm.movq(resultReg, cArg0);

        // Return to inline code
        masm.jmp(continuation);
        masm.bind(continuation);
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
            boolean result = moveHelper(asm, fromArgument, spilled, fromKind, state);
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

    private boolean moveHelper(AMD64MacroAssembler asm, AllocatableValue fromValue, AllocatableValue toValue, JavaKind kind, State[] state) {
        assert !ValueUtil.isIllegal(fromValue) && !ValueUtil.isIllegal(toValue) : "source and destination must be valid";
        int toValueIndex = argumentToStateIndex(toValue);
        int fromValueIndex = argumentToStateIndex(fromValue);
        if (state[toValueIndex] == State.WRITTEN) {
            // already written
            return true;
        }
        if (fromValueIndex != toValueIndex) {
            if (state[toValueIndex] == State.READ_ONLY) {
                return false;
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
                    AMD64Address toAddress = new AMD64Address(rsp, stackSlotToOffset((StackSlot) toValue));
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
                AMD64Address fromAddress = new AMD64Address(rsp, stackSlotToOffset((StackSlot) fromValue));
                if (ValueUtil.isRegister(toValue)) {
                    if (isXMMRegister(ValueUtil.asRegister(toValue))) {
                        if (kind == JavaKind.Double) {
                            asm.movdbl(ValueUtil.asRegister(toValue), fromAddress);
                        } else {
                            assert kind == JavaKind.Float : "kind must be float";
                            asm.movflt(ValueUtil.asRegister(toValue), fromAddress);
                        }
                    } else {
                        asm.movq(ValueUtil.asRegister(toValue), fromAddress);
                    }
                } else {
                    AMD64Address toAddress = new AMD64Address(rsp, stackSlotToOffset((StackSlot) toValue));
                    asm.movq(r13, fromAddress);
                    asm.movq(toAddress, r13);
                }

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

    private boolean isXMMRegister(AllocatableValue value) {
        if (!ValueUtil.isRegister(value))
            return false;
        return ValueUtil.asRegister(value).getRegisterCategory().equals(AMD64.XMM);
    }

    public State[] initRegState(AllocatableValue[] currentArguments, int currentStackSizeArguments, int expectedStackSizeArguments, int spInc) {
        RegisterArray registers = getTarget().arch.getAvailableValueRegisters();
        int currentArgsOnStack = currentStackSizeArguments / getTarget().wordSize;
        int expectedArgsOnStack = expectedStackSizeArguments / getTarget().wordSize;
        State[] state;
        if (spInc == 0) {
            // no additional stack slots
            // include RA and padding 16 byte
            state = new State[registers.size() + currentArgsOnStack + 2];
        } else {
            // include all registers, the current args and the increased stack space and RA and
            // padding 16 byte
            state = new State[registers.size() + currentArgsOnStack + expectedArgsOnStack + 3];
        }

        // initialize the state, set all locations to writeable
        Arrays.fill(state, State.WRITEABLE);

        // correct the stack offset of the current args by the sp inc
        for (int i = 0; i < currentArguments.length; i++) {
            if (currentArguments[i] instanceof StackSlot stackSlot) {
                StackSlot newStackSlot = StackSlot.get(stackSlot.getValueKind(), stackSlot.getRawOffset() + spInc, stackSlot.getRawAddFrameSize());
                currentArguments[i] = newStackSlot;

            }
            // make the slot read only to prevent accidental writing
            state[argumentToStateIndex(currentArguments[i])] = State.READ_ONLY;
        }

        return state;
    }

    public enum State {
        READ_ONLY,
        WRITEABLE,
        WRITTEN
    }

    public boolean needStackRepair(ResolvedJavaMethod rootMethod) {
        if (rootMethod == null)
            return false;
        CallingConvention cc = getCallingConvention(getCodeCache(), HotSpotCallingConventionType.JavaCallee, rootMethod, this);
        CallingConvention ccScalarized = CodeUtil.getValhallaCallingConvention(getCodeCache(), HotSpotCallingConventionType.JavaCallee, rootMethod, this, true);
        CallingConvention ccScalarizedWithoutReceiver = CodeUtil.getValhallaCallingConvention(getCodeCache(), HotSpotCallingConventionType.JavaCallee, rootMethod, this, false);

        return ccScalarized.getStackSize() > cc.getStackSize() || ccScalarized.getStackSize() > ccScalarizedWithoutReceiver.getStackSize();
    }

    /**
     * calculates the index for the state used during unpacking of inline type arguments
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
        return regNumber + stackSlotToOffset((StackSlot) argument) / getTarget().wordSize;
    }

    public int extendStackForInlineArgs(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig) {
        JavaType[] parameterTypes = rootMethod.getScalarizedParameters(true);
        CallingConvention cc = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes, this);

        int RAsize = crb.target.arch.getReturnAddressSize();
        int spInc = (cc.getStackSize() + RAsize);
        int stackAlignment = crb.target.stackAlignment;
        spInc = spInc % stackAlignment == 0 ? spInc : ((spInc / stackAlignment) + 1) * stackAlignment;
        asm.pop(r13);
        asm.decrementq(rsp, spInc);
        asm.push(r13);
        return spInc;
    }

    private void icCheck(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, HotSpotMarkId markId, HotSpotMarkId additionalMarkId) {
        HotSpotProviders providers = getProviders();
        if (rootMethod != null && !rootMethod.isStatic()) {
            JavaType[] parameterTypes = {providers.getMetaAccess().lookupJavaType(Object.class)};
            CallingConvention cc = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes, this);
            Register receiver = asRegister(cc.getArgument(0));
            int before;
            if (config.icSpeculatedKlassOffset == Integer.MAX_VALUE) {
                crb.recordMark(markId);
                if (additionalMarkId != null) {
                    crb.recordMark(additionalMarkId);
                }

                // c1_LIRAssembler_x86.cpp: const Register IC_Klass = rax;
                Register inlineCacheKlass = rax;

                if (config.useCompressedClassPointers) {
                    Register register = r10;
                    Register heapBase = providers.getRegisters().getHeapBaseRegister();
                    if (config.useCompactObjectHeaders) {
                        ((AMD64HotSpotMacroAssembler) asm).loadCompactClassPointer(register, receiver);
                    } else {
                        asm.movl(register, new AMD64Address(receiver, config.hubOffset));
                    }
                    AMD64HotSpotMove.decodeKlassPointer(asm, register, heapBase, config);
                    if (config.narrowKlassBase != 0) {
                        // The heap base register was destroyed above, so restore it
                        if (config.narrowOopBase == 0L) {
                            asm.xorl(heapBase, heapBase);
                        } else {
                            asm.movq(heapBase, config.narrowOopBase);
                        }
                    }
                    before = asm.cmpqAndJcc(inlineCacheKlass, register, ConditionFlag.NotEqual, null, false);
                } else {
                    before = asm.cmpqAndJcc(inlineCacheKlass, new AMD64Address(receiver, config.hubOffset), ConditionFlag.NotEqual, null, false);
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
                if (config.useCompactObjectHeaders) {
                    // 4 bytes for extra shift instruction, 1 byte less for 0-displacement address
                    inlineCacheCheckSize += 3;
                }
                asm.align(config.codeEntryAlignment, asm.position() + inlineCacheCheckSize);

                int startICCheck = asm.position();
                crb.recordMark(markId);
                if (additionalMarkId != null) {
                    crb.recordMark(additionalMarkId);
                }

                AMD64Address icSpeculatedKlass = new AMD64Address(data, config.icSpeculatedKlassOffset);

                AMD64BaseAssembler.OperandSize size;
                if (config.useCompressedClassPointers) {
                    if (config.useCompactObjectHeaders) {
                        ((AMD64HotSpotMacroAssembler) asm).loadCompactClassPointer(temp, receiver);
                    } else {
                        asm.movl(temp, new AMD64Address(receiver, config.hubOffset));
                    }
                    size = AMD64BaseAssembler.OperandSize.DWORD;
                } else {
                    asm.movptr(temp, new AMD64Address(receiver, config.hubOffset));
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
    }

    private void emitEntry(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, HotSpotMarkId markId, boolean receiverOnly,
                    boolean verified, Label verifiedEntry, HotSpotMarkId additionalMarkId) {
        // asm.align(config.codeEntryAlignment);
        crb.recordMark(markId);
        if (additionalMarkId != null) {
            crb.recordMark(additionalMarkId);
        }
        if (!verified) {
            icCheck(rootMethod, crb, asm, regConfig, markId, additionalMarkId);
        } else {
            crb.frameContext.enter(crb, 0, true);
            crb.frameContext.leave(crb, false);
            int stackIncrement = unpackInlineArgs(rootMethod, crb, asm, regConfig, receiverOnly);

            crb.frameContext.enter(crb, stackIncrement, false);
            asm.jmp(verifiedEntry);
        }

        asm.align(config.codeEntryAlignment);
    }

    private void emitEntry(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig, HotSpotMarkId markId, boolean receiverOnly,
                    boolean verified, Label verifiedEntry) {
        emitEntry(rootMethod, crb, asm, regConfig, markId, receiverOnly, verified, verifiedEntry, null);
    }

    /**
     * Emits the code prior to the verified entry point.
     *
     * @param installedCodeOwner see {@link LIRGenerationProvider#emitCode}
     */

    // @formatter:off
    // The entry points of JVMCI-compiled methods can have the following types:
    //
    // (1) Methods with no inline type arguments
    // (2) Methods with an inline type receiver but no inline type arguments
    //     VIEP_RO is the same as VIEP
    // (3) Methods with a non-inline type receiver and some inline type arguments
    //     VIEP_RO is the same as VEP
    // (4) Methods with an inline type receiver and other inline type arguments
    //     Separate VEP, VIEP, and VIEP_RO

    //
    // (1)               (2)                 (3)                    (4)
    // UEP/UIEP:         VIEP:               UIEP:                  UEP:
    //   check_icache      unpack receiver     check_icache           check_icache
    // VEP/VIEP/VIEP_RO    jump to VEP       VIEP/VIEP_RO:          VIEP_RO:
    //   body            UEP/UIEP:             unpack inline args     unpack receiver
    //                     check_icache        jump to VEP            jump to VEP
    //                   VEP/VIEP_RO         UEP:                   UIEP:
    //                     body                check_icache           check_icache
    //                                       VEP:                   VIEP:
    //                                         body                   unpack all inline args
    //                                                                jump to VEP
    //                                                              VEP:
    //                                                                body
    // @formatter:on
    public void emitCodePrefix(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, AMD64MacroAssembler asm, RegisterConfig regConfig) {

        boolean verifiedInlineSet = false;
        boolean verifiedInlineROSet = false;
        boolean unverifiedSet = false;
        boolean unverifiedInlineSet = false;

        Label verifiedEntry = new Label();
        if (installedCodeOwner != null) {
            if (crb.compilationResult.getEntryBCI() == -1 && installedCodeOwner.hasScalarizedParameters()) {
                // we have parameters that need to be scalarized
                if (!installedCodeOwner.isStatic()) {

                    if (installedCodeOwner.hasScalarizedReceiver()) {
                        // additional unverified entry points for receiver

                        if (installedCodeOwner.getScalarizedParametersCount() == 1) {
                            // only receiver needs to be scalarized share entries

                            /*
                             * unverified and no parameter scalarized, falls through to
                             * VERIFIED_INLINE_ENTRY
                             */
                            emitEntry(installedCodeOwner, crb, asm, regConfig,
                                            HotSpotMarkId.INLINE_ENTRY, false, false, null, HotSpotMarkId.UNVERIFIED_ENTRY);

                            /*
                             * verified but no parameter scalarized yet, produce code that
                             * scalarizes all of them and jump to verified entry
                             */
                            emitEntry(installedCodeOwner, crb, asm, regConfig,
                                            HotSpotMarkId.VERIFIED_INLINE_ENTRY, false, true, verifiedEntry, HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO);
                        } else {
                            /*
                             * unverified and no parameter scalarized, falls through to
                             * VERIFIED_INLINE_ENTRY
                             */
                            emitEntry(installedCodeOwner, crb, asm, regConfig,
                                            HotSpotMarkId.INLINE_ENTRY, false, false, null);

                            /*
                             * verified but no parameter scalarized yet, produce code that verified
                             * but no parameter scalarized yet, produce code that all of them and
                             * jump to verified entry
                             */
                            emitEntry(installedCodeOwner, crb, asm, regConfig,
                                            HotSpotMarkId.VERIFIED_INLINE_ENTRY, false, true, verifiedEntry);

                            /*
                             * unverified and all parameters except receiver scalarized, falls
                             * through to VERIFIED_INLINE_ENTRY_RO
                             */
                            emitEntry(installedCodeOwner, crb, asm, regConfig,
                                            HotSpotMarkId.UNVERIFIED_ENTRY, true, false, null);

                            /*
                             * verified and all parameters except receiver scalarized, falls through
                             * to VERIFIED_INLINE_ENTRY_RO
                             */
                            emitEntry(installedCodeOwner, crb, asm, regConfig,
                                            HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO, true, true, verifiedEntry);
                        }

                        verifiedInlineSet = true;
                        verifiedInlineROSet = true;
                        unverifiedSet = true;
                        unverifiedInlineSet = true;
                    } else {
                        // no entry points specific for receiver needed

                        /*
                         * unverified and no parameter scalarized, falls through to
                         * VERIFIED_INLINE_ENTRY
                         */
                        emitEntry(installedCodeOwner, crb, asm, regConfig,
                                        HotSpotMarkId.INLINE_ENTRY, false, false, null);

                        /*
                         * verified but no parameter scalarized yet, produce code that scalarizes
                         * all of them and jump to verified entry
                         */
                        emitEntry(installedCodeOwner, crb, asm, regConfig,
                                        HotSpotMarkId.VERIFIED_INLINE_ENTRY, false, true, verifiedEntry);

                        /*
                         * unverified and all parameters scalarized, falls through to the verified
                         * entry
                         */
                        emitEntry(installedCodeOwner, crb, asm, regConfig,
                                        HotSpotMarkId.UNVERIFIED_ENTRY, true, false, null);
                        verifiedInlineSet = true;
                        verifiedInlineROSet = false;
                        unverifiedSet = true;
                        unverifiedInlineSet = true;
                    }

                } else {
                    // static method no receiver specific entry points needed

                    // verified but no parameter scalarized yet, produce code that scalarizes
                    // all of them and jump to verified entry
                    emitEntry(installedCodeOwner, crb, asm, regConfig,
                                    HotSpotMarkId.VERIFIED_INLINE_ENTRY, false, true, verifiedEntry);
                    verifiedInlineSet = true;
                }
            } else if (!installedCodeOwner.isStatic()) {
                // no additional entry points needed
                emitEntry(installedCodeOwner, crb, asm, regConfig,
                                HotSpotMarkId.UNVERIFIED_ENTRY, false, false, null, HotSpotMarkId.INLINE_ENTRY);
                unverifiedSet = true;
                unverifiedInlineSet = true;
            }
        }

        if (crb.compilationResult.getEntryBCI() != -1) {
            crb.recordMark(HotSpotMarkId.OSR_ENTRY);
            crb.frameContext.enter(crb);

        } else {
            // set entry points (if not set yet) to verified entry point
            if (!unverifiedSet) {
                crb.recordMark(HotSpotMarkId.UNVERIFIED_ENTRY);
            }
            if (!unverifiedInlineSet) {
                crb.recordMark(HotSpotMarkId.INLINE_ENTRY);
            }
            if (!verifiedInlineSet) {
                crb.recordMark(HotSpotMarkId.VERIFIED_INLINE_ENTRY);
            }
            if (!verifiedInlineROSet) {
                crb.recordMark(HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO);
            }
            // record the normal entry point
            crb.recordMark(HotSpotMarkId.VERIFIED_ENTRY);
            crb.frameContext.enter(crb, 0, true);
            asm.bind(verifiedEntry);
        }


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
