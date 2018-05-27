/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
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
package support
package info

import java.net.URL

import org.opalj.br.Method
import org.opalj.br.analyses.BasicReport
import org.opalj.br.analyses.DefaultOneStepAnalysis
import org.opalj.br.analyses.Project
import org.opalj.br.collection.TypesSet
import org.opalj.fpcf.PropertyStoreKey
import org.opalj.fpcf.FPCFAnalysesManagerKey
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.analyses.LazyVirtualMethodThrownExceptionsAnalysis
import org.opalj.fpcf.analyses.EagerL1ThrownExceptionsAnalysis
import org.opalj.fpcf.properties.{ThrownExceptions ⇒ ThrownExceptionsProperty}
import org.opalj.log.OPALLogger.info
import org.opalj.util.PerformanceEvaluation.time

/**
 * Prints out the information about the exceptions thrown by methods.
 *
 * @author Michael Eichberg
 * @author Andreas Muttschelller
 */
object ThrownExceptions extends DefaultOneStepAnalysis {

    override def title: String = "Thrown Exceptions"

    override def description: String = {
        "Computes the set of the exceptions (in)directly thrown by methods"
    }

    final val AnalysisLevelL0 = "-analysisLevel=L0"
    final val AnalysisLevelL1 = "-analysisLevel=L1"
    final val SuppressPerMethodReports = "-suppressPerMethodReports"

    override def analysisSpecificParametersDescription: String = {
        "[-analysisLevel=<L0|L1>  (Default: L1)]\n"+
            "[-suppressPerMethodReports]"
    }

    override def checkAnalysisSpecificParameters(parameters: Seq[String]): Traversable[String] = {
        val remainingParameters =
            parameters.filter { p ⇒
                p != AnalysisLevelL0 && p != AnalysisLevelL1 && p != SuppressPerMethodReports
            }
        super.checkAnalysisSpecificParameters(remainingParameters)
    }

    def doAnalyze(
        project:       Project[URL],
        parameters:    Seq[String],
        isInterrupted: () ⇒ Boolean
    ): BasicReport = {
        val ps: PropertyStore = time {

            if (parameters.contains(AnalysisLevelL0)) {
                // We are relying on/using the "FallbackAnalysis":
                val ps = project.get(PropertyStoreKey)
                ps.setupPhase(Set.empty) // <= ALWAYS REQUIRED.
                // We have to query the properties...
                project.allMethods.foreach { m ⇒
                    ps.force(m, ThrownExceptionsProperty.key)
                }
                ps.waitOnPhaseCompletion()
                ps
            } else /* if no analysis level is specified or L1 */ {
                project.get(FPCFAnalysesManagerKey).runAll(
                    LazyVirtualMethodThrownExceptionsAnalysis,
                    EagerL1ThrownExceptionsAnalysis
                )
            }
        } { t ⇒
            info("analysis progress", "execution time: "+t.toSeconds)(project.logContext)
        }

        val allMethods = ps.entities(ThrownExceptionsProperty.key).toIterable
        val (epsNotThrowingExceptions, otherEPS) =
            allMethods.partition(_.ub.throwsNoExceptions)
        val epsThrowingExceptions = otherEPS.filter(eps ⇒ eps.lb.types != TypesSet.SomeException)

        val methodsThrowingExceptions = epsThrowingExceptions.map(_.e.asInstanceOf[Method])
        val privateMethodsThrowingExceptionsCount = methodsThrowingExceptions.count(_.isPrivate)

        val privateMethodsNotThrowingExceptions =
            epsNotThrowingExceptions.map(_.e.asInstanceOf[Method]).filter(_.isPrivate)

        val perMethodsReport =
            if (parameters.contains(SuppressPerMethodReports))
                ""
            else
                epsThrowingExceptions.map { p ⇒
                    p.e.asInstanceOf[Method].toJava(p.ub.toString)
                }.toList.sorted.mkString("\n")

        BasicReport(
            "\nThrown Exceptions Information:\n"+
                perMethodsReport+"\n"+
                ps.toString(printProperties = false)+
                "\nStatistics:\n"+
                "#methods with a thrown exceptions property: "+
                s"${allMethods.size} (${project.methodsCount})\n"+
                "#methods with exceptions information more precise than _ <: Throwable: "+
                s"${methodsThrowingExceptions.size + epsNotThrowingExceptions.size}\n"+
                s" ... #exceptions == 0: ${epsNotThrowingExceptions.size}\n"+
                s" ... #exceptions == 0 and private: ${privateMethodsNotThrowingExceptions.size}\n"+
                s" ... #exceptions >  0 and private: $privateMethodsThrowingExceptionsCount\n"
        )
    }
}
