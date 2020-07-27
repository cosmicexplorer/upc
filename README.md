upc
===

Ultra-high-performance local IPC framework with Zipkin tracing to conduct a beautiful symphony of build tooling.

1. **[local/](local/):** Cross-language shared-memory IPC!
  - Using Thrift for language support and ease of dropping into a project!
  - Intra- *(for FFIs)* **or** Inter-Process Communication!
2. High-resolution local observability!
  - Zipkin spans automatically created tracking every message/response to every thrift service!
    - Allow tracking every bit of memory (file contents, etc) sent between `upc` clients!
3. Transparent distributed memory and process execution!
  - *via [the bazel remexec API](https://github.com/bazelbuild/remote-apis)!*

# TODO
- [use a prototype for virtualized i/o in zinc from the sbt maintainer to demonstrate SUPER FAST COMPILES!!!!](https://twitter.com/hipsterelectron/status/1258499282589503488?s=21)
- ["would be interesting to see how far we can get with openmpi vs openmp on a single machine." @pyalamanchili](https://twitter.com/pavan_ky/status/1260846963458625536?s=20)

# Notes
[Charles Nutter on Twitter](https://twitter.com/headius/status/1287884015735721984?s=20):
> Every new IPC system starts out by pretending we can abstract away the plumbing of making a
> function call, and somehow it will all work out. Every successful IPC system eventually recognizes
> that the important atom is the unit-of-work, and function calls are quarks.

# License
[Apache v2](./LICENSE)
