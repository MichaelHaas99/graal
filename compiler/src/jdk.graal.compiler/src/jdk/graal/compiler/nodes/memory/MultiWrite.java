package jdk.graal.compiler.nodes.memory;

/**
 * This interface marks nodes that update a flat inline object. An inline object can consists of
 * multiple instance fields, so multiple write operations may be necessary.
 */
public interface MultiWrite extends MultiMemoryKill, MemoryAccess {
}
