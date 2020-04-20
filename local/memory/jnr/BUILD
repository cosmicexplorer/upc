scala_library(
  sources=['FFI.scala', 'Idk.java'],
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
  ],
)

junit_tests(
  name='tests',
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
    '3rdparty/jvm:scalatest',
    ':jnr',
  ],
)
