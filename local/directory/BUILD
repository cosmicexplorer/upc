java_library(
  name='enums',
  sources=['LibDirectoryEnums.java'],
)

scala_library(
  dependencies=[
    '3rdparty/jvm:ammonite-ops',
    '3rdparty/jvm:jnr-ffi',
    ':enums',
    'local/memory',
  ],
)

junit_tests(
  name='tests',
  dependencies=[
    '3rdparty/jvm:ammonite-ops',
    '3rdparty/jvm:jnr-ffi',
    '3rdparty/jvm:scalatest',
    ':directory',
    'local/memory',
  ],
)
