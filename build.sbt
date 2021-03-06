name := "jobcoin"

version := "0.1"

scalaVersion := "2.12.4"

scalacOptions += "-Ypartial-unification"

addCompilerPlugin(
  "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
)



lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .settings(
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "io.monix" %% "monix" % "2.3.0",
      "io.monix" %% "monix-cats" % "2.3.0",
      "com.softwaremill.sttp" %% "core" % "1.0.2",
      "com.softwaremill.sttp" %% "okhttp-backend-monix" % "1.0.2",
      "com.softwaremill.sttp" %% "circe" % "1.0.2",
      "org.scalatest" %% "scalatest" % "3.0.4",
      "com.lihaoyi" %% "pprint" % "0.5.3"
    ) ++ Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-generic-extras",
      "io.circe" %% "circe-parser"
    ).map(_ % "0.8.0")
    // other settings here
  )
