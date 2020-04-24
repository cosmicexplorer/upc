java_thrift_library(
  sources=['*.thrift'],
  language='scala',
  compiler='scrooge',
)

scala_library(
  name='via-thrift',
  dependencies=[
    'local',
    ':thrift',
  ],
)
