jar_library(
  name='ammonite-ops',
  jars=[
    scala_jar(org='com.lihaoyi', name='ammonite-ops', rev='2.0.4'),
  ],
)

SCROOGE_REV = '20.4.0'

jar_library(name='finagle-thrift',
  jars=[
    scala_jar(org='com.twitter', name='finagle-thrift', rev=SCROOGE_REV),
  ],
)

FINATRA_REV = SCROOGE_REV

jar_library(
  name='finatra-thrift',
  jars=[
    scala_jar(org='com.twitter', name='finatra-thrift', rev=FINATRA_REV),
  ]
)

jar_library(
  name='finatra-thrift-tests',
  jars=[
    scala_jar(org='com.twitter', name='finatra-thrift', rev=FINATRA_REV,
              classifier='tests'),
    scala_jar(org='com.twitter', name='inject-server', rev=FINATRA_REV,
              classifier='tests'),
    scala_jar(org='com.twitter', name='inject-app', rev=FINATRA_REV,
              classifier='tests'),
    scala_jar(org='com.twitter', name='inject-core', rev=FINATRA_REV,
              classifier='tests'),
    scala_jar(org='com.twitter', name='inject-modules', rev=FINATRA_REV,
              classifier='tests'),
  ],
  scope='compile test',
)

jar_library(
  name='jnr-ffi',
  jars=[
    jar(org='com.github.jnr', name='jnr-ffi', rev='2.1.12'),
  ],
)

jar_library(
  name='junixsocket',
  jars=[
    jar(org='com.kohlschutter.junixsocket', name='junixsocket-core', rev='2.3.2'),
  ],
)

jar_library(
  name='logback',
  jars=[
    jar(org='ch.qos.logback', name='logback-classic', rev='1.2.3'),
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

jar_library(name='scrooge-core',
  jars=[
    scala_jar(org='com.twitter', name='scrooge-core', rev=SCROOGE_REV),
  ],
)

jar_library(name='scrooge-generator',
  jars=[
    scala_jar(org='com.twitter', name='scrooge-generator', rev=SCROOGE_REV),
  ],
)

jar_library(name='scrooge-linter',
  jars=[
    scala_jar(org='com.twitter', name='scrooge-linter', rev=SCROOGE_REV),
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
