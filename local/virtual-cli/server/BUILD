scala_library(
  dependencies=[
    'local',
    'local/src/thrift',
    'local/src/thrift:via-thrift',
    'local/virtual-cli/client',
    'local/directory',
    'local/memory',
    'local/thrift-socket',
  ],
)

jvm_binary(
  name='process-execution-server',
  main='upc.local.virtual_cli.server.ProcessExecutionServerMain',
  dependencies=[
    ':server',
  ],
)

junit_tests(
  name='tests',
  dependencies=[
    '3rdparty/jvm:ammonite-ops',
    '3rdparty/jvm:scalatest',
    'local',
    'local/src/thrift',
    'local/src/thrift:via-thrift',
    'local/directory',
    'local/directory/testutil',
    'local/memory',
    ':server',
  ],
)
