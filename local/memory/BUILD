java_library(
  name='enums',
  sources=['LibMemoryEnums.java'],
)

scala_library(
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
    ':enums',
    'local',
  ],
)

junit_tests(
  name='tests',
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
    '3rdparty/jvm:scalatest',
    ':memory',
  ],
)
