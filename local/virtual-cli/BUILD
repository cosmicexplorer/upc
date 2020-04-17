scala_library(
  dependencies=[
    '3rdparty/jvm:ammonite-ops',
    '3rdparty/jvm:junixsocket',
    '3rdparty/jvm:nailgun-server',
    'local',
    'local/src/thrift',
    'local/memory',
  ],
)

jvm_binary(
  name='bin',
  main='upc.local.virtual_cli.ExampleMain',
  dependencies=[
    ':virtual-cli',
  ],
)
