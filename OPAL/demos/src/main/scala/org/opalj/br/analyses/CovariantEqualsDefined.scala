/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2016
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package br
package analyses

import java.net.URL
import org.opalj.issues.Relevance
import org.opalj.issues.Issue
import org.opalj.issues.IssueCategory
import org.opalj.issues.IssueKind
import org.opalj.issues.ClassLocation

/**
 * Finds classes that define only a co-variant `equals` method (an equals method
 * where the parameter type is a subtype of `java.lang.Object`), but which do not
 * also define a "standard" `equals` method.
 *
 * ==Implementation Note==
 * This analysis is implemented using a traditional approach where each analysis
 * analyzes the project's resources on its own and fully controls the process.
 *
 * @author Michael Eichberg
 */
class CovariantEqualsMethodDefined[Source] extends OneStepAnalysis[Source, Iterable[Issue]] {

    //
    // Meta-data
    //

    override def description: String = "Finds classes that just define a co-variant equals method."

    //
    // Implementation
    //

    def doAnalyze(
        project:       Project[Source],
        parameters:    Seq[String],
        isInterrupted: () ⇒ Boolean
    ): Iterable[Issue] = {

        val mutex = new Object
        var reports = List[Issue]()

        project.parForeachClassFile(isInterrupted) { classFile ⇒
            var definesEqualsMethod = false
            var definesCovariantEqualsMethod = false
            for (Method(_, "equals", MethodDescriptor(Seq(ot), BooleanType)) ← classFile.methods)
                if (ot == ObjectType.Object)
                    definesEqualsMethod = true
                else
                    definesCovariantEqualsMethod = true

            if (definesCovariantEqualsMethod && !definesEqualsMethod) {
                mutex.synchronized {
                    reports = Issue(
                        "CovariantEqualsMethodDefined",
                        Relevance.Moderate,
                        summary = "defines a covariant equals method, but does not also define the standard equals method",
                        categories = Set(IssueCategory.Correctness),
                        kinds = Set(IssueKind.MethodMissing),
                        locations = List(new ClassLocation(None, project, classFile))
                    ) :: reports
                }
            }
        }
        reports
    }
}

/**
 * Enables the stand alone execution of this analysis.
 */
object CovariantEqualsMethodDefinedAnalysis extends AnalysisExecutor {

    val analysis = ReportableAnalysisAdapter[URL](new CovariantEqualsMethodDefined)
}
