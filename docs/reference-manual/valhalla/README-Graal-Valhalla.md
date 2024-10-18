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