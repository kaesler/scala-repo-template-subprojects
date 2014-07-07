// Adds the task "eclipse".  Used to generate Eclipse project and attach source for dependent JARs (if applicable).
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.4.0")

// Adds the task "assembly".  Used to construct executable JAR files.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.11.2")

// Code coverage.
libraryDependencies ++= Seq(
    "org.jacoco" % "org.jacoco.core" % "0.5.7.201204190339" artifacts(Artifact("org.jacoco.core", "jar", "jar")),
    "org.jacoco" % "org.jacoco.report" % "0.5.7.201204190339" artifacts(Artifact("org.jacoco.report", "jar", "jar")))

addSbtPlugin("de.johoop" % "jacoco4sbt" % "2.1.2")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")

// Unify scaladoc/javadoc across multiple projects.
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.0")
