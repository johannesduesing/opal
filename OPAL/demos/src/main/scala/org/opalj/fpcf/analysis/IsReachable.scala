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
//package org.opalj
//package fpcf
//package analysis
//package demo
//
//import java.net.URL
//import org.opalj.br.analyses.Project
//import org.opalj.br.Method
//import org.opalj.br.analyses.DefaultOneStepAnalysis
//import org.opalj.br.analyses.BasicReport
//import org.opalj.br.analyses.SourceElementsPropertyStoreKey
//
//case object IsReachable extends SetProperty[Method]
//
//object IsReachableDemo extends DefaultOneStepAnalysis {
//
//    override def title: String = "all reachable methods"
//
//    override def description: String = "determines if a method is reachable by computing the call graph"
//
//    override def doAnalyze(
//        project:       Project[URL],
//        parameters:    Seq[String],
//        isInterrupted: () ⇒ Boolean
//    ): BasicReport = {
//        implicit val theProject = project
//        implicit val theProjectStore = theProject.get(SourceElementsPropertyStoreKey)
//
//        val isReachableCount = new java.util.concurrent.atomic.AtomicInteger(0)
//        theProjectStore.onPropertyDerivation(IsReachable) { e ⇒
//            // LET'S do something meaningful:    println("is reachable: "+e)
//            isReachableCount.incrementAndGet()
//        }
//
//        theProjectStore.onPropertyChange(EntryPoint.key) { (e: Entity, p: Property) ⇒
//            p match {
//                case IsEntryPoint ⇒ theProjectStore.add(IsReachable)(e.asInstanceOf[Method])
//                case _            ⇒ // don't care
//            }
//        }
//
//        val executer = project.get(FPCFAnalysesManagerKey)
//
//        executer.runAll(
//            CallableFromClassesInOtherPackagesAnalysis,
//            FactoryMethodAnalysis,
//            InstantiabilityAnalysis,
//            MethodAccessibilityAnalysis,
//            LibraryEntryPointsAnalysis
//        )
//
//        theProjectStore.waitOnPropertyComputationCompletion(true)
//
//        //
//        //        theProjectStore.onPropertyDerivation(IsReachable) { e ⇒
//        //            // LET'S do something meaningful:
//        //            //      println("is reachable: "+e)
//        //            isReachableCount.incrementAndGet()
//        //        }
//        //        theProjectStore.onPropertyChange(EntryPoint.Key) { (e: Entity, p: Property) ⇒
//        //            p match {
//        //                case IsEntryPoint ⇒ theProjectStore.add(IsReachable)(e.asInstanceOf[Method])
//        //                case _            ⇒ // don't care
//        //            }
//        //        }
//        //        theProjectStore.waitOnPropertyComputationCompletion(true)
//
//        //import scala.collection.JavaConversions._
//        //println(theProjectStore.entities(IsReachable).mkString("\n"))
//        val entitiesThatAreEntryPoints = theProjectStore.entities { (p: Property) ⇒
//            p == IsEntryPoint
//        }
//
//        println(theProjectStore.toString)
//
//        BasicReport("IsReachable: "+isReachableCount.get+" <=> IsEntryPoint: "+entitiesThatAreEntryPoints.size)
//    }
//}
