addSbtPlugin("org.scalameta"      % "sbt-mdoc"      % "2.5.4")
addSbtPlugin("org.scoverage"      % "sbt-scoverage" % "2.1.0")
addSbtPlugin("org.typelevel"      % "sbt-typelevel" % "0.7.2-15-e1d4e6d-SNAPSHOT")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"       % "0.4.7")

resolvers ++= Resolver.sonatypeOssRepos("snapshots")
