java_library(
  name='libmemory',
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
  ],
)

scala_library(
  sources=['*.scala'],
  dependencies=[
    '3rdparty/jvm:jnr-ffi',
    ':libmemory',
  ],
)
