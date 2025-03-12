# Project Valhalla

## Building the Valhalla JDK

- clone https://github.com/MichaelHaas99/valhalla
- switch to the branch `mh/GR-57655`
- build the JDK with the command `sh configure --with-jtreg=/path/to/jtreg; make jdk-image`
  - jtreg with version 7.5.1+1 as well as a boot JDK with version not lower than 23 is needed.

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

## Frequently used Hotspot JVM options

- `EnableValhalla` enable valhalla
- `UseFieldFlattening` enable flattening of value class fields
- `UseArrayFlattening` enable flattening of value class array elements
- `InlineTypePassFieldsAsArgs` enable calling convention
- `InlineTypeReturnedAsFields` enable return convention
- `UseACmpProfile` use profiling information for the acmp bytecode

## How to execute some valhalla specific test cases from the graal repo

- `mx unittest jdk.graal.compiler.jtt.bytecode.BC_ifacmpeq`
- `mx unittest jdk.graal.compiler.jtt.bytecode.BC_monitorenter03`
- `mx unittest jdk.graal.compiler.core.test.CheckObjectEqualsTest`
- `mx unittest jdk.graal.compiler.core.test.ea.PartialEscapeAnalysisTest`

## Current status

### Work completed

Graal support for the new semantics introduced in JEP 401

- if_acmp (substitutability check for inline types)
- monitorenter (not allowed for value objects)
- adaption of hashCode plugin in Graal (hash code of value objects is produced by the hash code of their fields)
- PEA supports substitutability checks and avoids materialization of operands if possible

Optimizations for JEP 401

- access to new acmp profiling data over JVMCI (profiling data not yet optimal)
- usage of acmp profiling data
- inlining of substitutability check instead of slow call to Java library, avoided for recursive checks
- access and store operations on null-restricted flat fields
- access and store operations on null-restricted flat arrays
- Valhalla calling convention
  - pass the field values of a value object instead of a reference
- Valhalla return convention
  - return the field values of a value object instead of a reference if enough registers are available

### Future work/ideas:

- support for the new intrinsics introduced with Valhalla
- produce code for an allocation for a non-inlined method handle with a known scalarized return instead of a foreign
  call
- use profiling data for flat arrays
- support for nullable (atomic) flat fields and arrays
