java_library(
  name='libmemory',
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
  ],
)

scala_library(
  sources=['*.scala', '!*Spec.scala'],
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
    ':libmemory',
  ],
)

junit_tests(
  name='tests',
  dependencies=[
    '3rdparty/jvm:scalatest',
    ':libmemory',
    ':memory',
  ],
  extra_jvm_options=['-Dld.library.path=/Users/dmcclanahan/projects/active/upc/local/target/debug'],
)
