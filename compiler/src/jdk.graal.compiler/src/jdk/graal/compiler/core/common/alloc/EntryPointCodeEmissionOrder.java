package jdk.graal.compiler.core.common.alloc;

import java.util.BitSet;
import java.util.PriorityQueue;

import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.core.common.cfg.CodeEmissionOrder;
import jdk.graal.compiler.options.OptionValues;

/**
 * Computes the block order for an entry point. For entry points, execution should not terminate in
 * the middle of the generated machine code. Instead, the final block should emit the final line of
 * machine code, allowing the entry point to seamlessly continue with subsequent code.
 */
public class EntryPointCodeEmissionOrder<T extends BasicBlock<T>> implements CodeEmissionOrder<T> {

    protected int originalBlockCount;
    protected T startBlock;
    protected Object lastBlock;

    public EntryPointCodeEmissionOrder(int originalBlockCount, T startBlock) {
        this.originalBlockCount = originalBlockCount;
        this.startBlock = startBlock;
    }

    /**
     * Computes the block order used for an entry point code emission.
     *
     * @return sorted list of ids of basic blocks, see {@link AbstractControlFlowGraph} for details
     *         about the data structures
     */
    @Override
    public int[] computeCodeEmittingOrder(OptionValues options, ComputationTime computationTime) {
        BasicBlockOrderUtils.BlockList<T> order = new BasicBlockOrderUtils.BlockList<>(originalBlockCount);
        BitSet visitedBlocks = new BitSet(originalBlockCount);
        PriorityQueue<T> worklist = BasicBlockOrderUtils.initializeWorklist(startBlock, visitedBlocks);
        computeCodeEmittingOrder(order, worklist, visitedBlocks, computationTime);
        BasicBlockOrderUtils.checkStartBlock(order, startBlock);
        return order.toIdArray();
    }

    /**
     * Iteratively adds paths to the code emission block order.
     */
    private <T extends BasicBlock<T>> void computeCodeEmittingOrder(BasicBlockOrderUtils.BlockList<T> order, PriorityQueue<T> worklist, BitSet visitedBlocks, ComputationTime computationTime) {
        while (!worklist.isEmpty()) {
            T nextImportantPath = worklist.poll();
            addPathToCodeEmittingOrder(nextImportantPath, order, worklist, visitedBlocks, computationTime);
        }
        order.add((T) lastBlock);
    }

    /**
     * Add a linear path to the code emission order greedily following the most likely successor.
     */
    private <T extends BasicBlock<T>> void addPathToCodeEmittingOrder(T initialBlock, BasicBlockOrderUtils.BlockList<T> order, PriorityQueue<T> worklist, BitSet visitedBlocks,
                    ComputationTime computationTime) {
        T block = initialBlock;
        while (block != null) {
            if (order.isScheduled(block)) {
                /**
                 * We may be revisiting a block that has already been scheduled. This can happen for
                 * triangles:
                 *
                 * <pre>
                 *     A
                 *     |\
                 *     | B
                 *     |/
                 *     C
                 * </pre>
                 *
                 * C will be added to the worklist twice: once when A is scheduled, and once when B
                 * is scheduled.
                 */
                break;
            }
            // we want the merge block to be the last block
            if (block.getId() == originalBlockCount - 1) {
                lastBlock = block;
            } else {
                order.add(block);
            }

            T mostLikelySuccessor = BasicBlockOrderUtils.findAndMarkMostLikelySuccessor(block, order, visitedBlocks, computationTime, worklist);
            BasicBlockOrderUtils.enqueueSuccessors(block, worklist, visitedBlocks);
            block = mostLikelySuccessor;
        }
    }

}
