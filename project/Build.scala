import sbt._
import Keys._

import sbtassembly.Plugin.AssemblyKeys._

object OPALBuild extends Build {
	 
	lazy val buildSettings =
		Defaults.defaultSettings ++
		sbtassembly.Plugin.assemblySettings ++
		Seq(
			// Enable this to avoid including the Scala runtime into fat .jars,
			// which would reduce the .jar's file size greatly. However, then the
			// user will need the Scala runtime installed in order to run the .jar.
			//assemblyOption in assembly ~= { _.copy(includeScala = false) },

			// Don't run tests as part of the "assembly" command, it's too inconvenient
			test in assembly := {}
		)

	lazy val opal = Project(
		id = "OPAL",
		base = file(".")
	).
  settings(sbtunidoc.Plugin.unidocSettings: _*).
	aggregate(
		util, 
		bt, 
		ai,
		dependenciesExtraction, 		 
		opalDeveloperTools, 
		demos,		
		findRealBugsAnalyses,
		incubation,
		VALIDATE)

	/*****************************************************************************
	 *
	 * THE CORE PROJECTS WHICH CONSTITUTE OPAL
	 *
 	 */
	
	lazy val util = Project(
		id = "Util",
		base = file("util")
	)	
	
	lazy val bt = Project(
		id = "BytecodeToolkit",
		base = file("core")
	) dependsOn(util)

	lazy val ai = Project(
		id = "AbstractInterpretationFramework",
		base = file("ext/ai")
	) dependsOn(bt % "test->test;compile->compile")

	// The project "DependenciesExtractionLibrary" depends on
	// AI to be able to resolve calls using 
	// MethodHandle/MethodType/"invokedynamic"/...
	lazy val dependenciesExtraction = Project(
		id = "DependenciesExtractionLibrary",
		base = file("ext/dependencies")
	) dependsOn(ai % "test->test;compile->compile")

	lazy val architectureValidation = Project(
		id = "ArchitectureValidation",
		base = file("av")
	) dependsOn(dependenciesExtraction % "test->test;compile->compile")

	lazy val opalDeveloperTools = Project(
		id = "OpalDeveloperTools",
		base = file("ext/tools")
	) dependsOn(dependenciesExtraction % "test->test;compile->compile")

	// This project validates OPAL's implemented architecture; hence
	// it is not a "project" in the classical sense!
	lazy val VALIDATE = Project(
		id = "VALIDATE_OPAL",
		base = file("VALIDATE")
	) dependsOn(
		opalDeveloperTools % "test->test;compile->compile",
		architectureValidation % "test->test;compile->compile")

	/*****************************************************************************
	 *
	 * PROJECTS BELONGING TO THE OPAL ECOSYSTEM 
	 *
 	 */

	lazy val findRealBugsAnalyses = Project(
		id = "FindRealBugsAnalyses",
		base = file("frb/analyses"),
		settings = buildSettings ++ Seq(
			mainClass in assembly := Some("de.tud.cs.st.bat.findrealbugs.FindRealBugsCLI")
		)
	) dependsOn(ai % "test->test;compile->compile")

	lazy val demos = Project(
		id = "Demos",
		base = file("demo")
	) dependsOn(dependenciesExtraction, architectureValidation)

	lazy val incubation = Project(
		id = "Incubation",
		base = file("incubation")
	) dependsOn(dependenciesExtraction, architectureValidation)

}
