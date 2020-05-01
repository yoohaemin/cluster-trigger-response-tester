import Dependencies._

ThisBuild / scalaVersion     := "2.13.2"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "cluster-killer-test",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "2.1.3",
      "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" %"2.0.9",
      "com.softwaremill.sttp" %% "circe" %"1.7.2",
      "dev.zio" %% "zio-streams" %"1.0.0-RC18-2",
      "dev.zio" %% "zio-interop-cats" %"2.0.0.0-RC13",
    ),
    graalVMNativeImageOptions ++= Seq("--static", "--enable-https", "--verbose")
  )
  .enablePlugins(GraalVMNativeImagePlugin)
