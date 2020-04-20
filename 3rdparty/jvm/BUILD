jar_library(
  name='ammonite-ops',
  jars=[
    scala_jar(org='com.lihaoyi', name='ammonite-ops', rev='2.0.4'),
  ],
)

jar_library(
  name='jnr-ffi',
  jars=[
    jar(org='com.github.jnr', name='jnr-ffi', rev='2.1.13-SNAPSHOT',
        mutable=True),
  ],
)

jar_library(
  name='junixsocket',
  jars=[
    jar(org='com.kohlschutter.junixsocket', name='junixsocket-core', rev='2.3.2'),
  ],
)

jar_library(
  name='monix',
  jars=[
    scala_jar(org='io.monix', name='monix', rev='3.1.0'),
  ],
)

jar_library(
  name='nailgun-server',
  jars=[
    jar(org='com.martiansoftware', name='nailgun-server', rev='0.9.1'),
  ],
)

jar_library(
  name='scalatest',
  jars=[
    scala_jar(org='org.scalatest', name='scalatest', rev='3.0.0'),
  ],
)

jar_library(
  name='thrift',
  jars=[
    jar(org='org.apache.thrift', name='libthrift', rev='0.10.0'),
  ],
  # javac uses undeclared types to build the MessageBuilder API.
  scope='forced',
)
