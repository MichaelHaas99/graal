# Project Valhalla

## Building the Valhalla JDK

- clone https://github.com/MichaelHaas99/valhalla
- switch to the branch `mh/GR-57655`
- build the JDK with the command `sh configure --with-jtreg=/path/to/jtreg; make jdk-image`
    - jtreg with version 7.3.1+1 as well as a boot JDK with version not lower than 23 is needed.

## Building a Valhalla JDK with the graal compiler included

- switch to the valhalla repo
- set `JAVA_HOME=build/*/images/jdk`
- clone https://github.com/MichaelHaas99/graal
- switch to the branch `valhalla`
- build the JDK with the command `cd compiler; mx build;`

## Execute JTREG test cases

- run the valhalla tests with the command `make TEST="compiler/valhalla/inlinetypes runtime/valhalla/inlinetypes
  valhalla/valuetypes" test
- in order to run the tests with the graal compiler
    - switch to the graal repo
    - execute `cd compiler; mx build; export GRAALJDK=$(mx graaljdk-home)`
    - switch back to the valhalla repo
    - execute the tests with
      `make TEST="compiler/valhalla/inlinetypes runtime/valhalla/inlinetypes valhalla/valuetypes" JDK_UNDER_TEST=$GRAALJDK test`
    - to print error messages use the option `JTREG="OPTIONS=-Djdk.graal.CompilationFailureAction=Print"`

## Execute test cases from the graal repo

- `mx unittest jdk.graal.compiler.jtt.bytecode.BC_ifacmpeq`
- `mx unittest jdk.graal.compiler.jtt.bytecode.BC_monitorenter03`
- `mx unittest jdk.graal.compiler.core.test.CheckObjectEqualsTest`
- `mx unittest jdk.graal.compiler.core.test.ea.PartialEscapeAnalysisTest`

## Current status

### Work completed

Graal support for the new semantics introduced in JEP 401

- if_acmp (substitutability check for inline types)
- monitorenter (not allowed for inline types)
- adaption of hashCode plugin in Graal
- PEA supports substitutability checks and avoids materialization of operands if possible
- access to new acmp profiling data over JVMCI (profiling data not yet optimal)
- usage of acmp profiling data
- inlining of equality comparison instead of slow call to Java library

optimizations for JEP 401

- access and store operations on null-restricted flat fields
- access and store operations on null-restricted flat arrays

### Working on

- refactoring of bytecode parser changes into a node plugin
- flattened inline type fields
  - empty inline types can be optimized, not yet considered. maybe cause problem if flat?
  - getfields on flattened fields can be delayed to avoid creation of unused nodes
- flat arrays
  - use profiling data for flat arrays
  - if inline type is not known at compile time do a runtime call (deoptimization at the moment)
  - API for flat arrays in JVMCI
  - `System.arraycopy` snippets works incorrectly, throws ClassCastException because doesn't expect a fat array

- Nullable flattened inline types cause problems in the JVM
  - can't test my implementation (also not yet implemented in C1 and C2)
  - wait for changes on the valhalla repo

### Future work/ideas:

- Scalarization of inline types which are function arguments or return values
