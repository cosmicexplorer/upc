scala_library(
  dependencies=[
    '3rdparty/jvm:finagle-thrift',
    '3rdparty/jvm:finatra-thrift',
    'local/src/thrift',
    'local/directory',
    'local/memory',
  ],
)

jvm_binary(
  name='bin',
  main='upc.local.virtual_cli.server.ProcessExecutionServer',
  dependencies=[
    '3rdparty/jvm:logback',
    ':server',
  ],
)

junit_tests(
  name='tests',
  dependencies=[
    '3rdparty/jvm:finatra-thrift-tests',
    '3rdparty/jvm:scalatest',
    ':server',
  ],
)
