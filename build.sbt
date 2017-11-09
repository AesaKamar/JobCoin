name := "jobcoin"

version := "0.1"

scalaVersion := "2.12.4"

scalacOptions += "-Ypartial-unification"
libraryDependencies ++= Seq("io.monix" %% "monix" % "2.3.0",
                            "io.monix" %% "monix-cats" % "2.3.0",
                            "org.scalatest" %% "scalatest" % "3.0.4" % "test",
                            "com.lihaoyi" %% "pprint" % "0.5.3")
