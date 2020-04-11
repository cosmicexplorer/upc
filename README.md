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

# License
[Apache v2](./LICENSE)
