/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package fpcf

import org.opalj.si.Project

/**
 * Factory for FPCF analyses which should be directly started/scheduled.
 *
 * @author Michael Eichberg
 */
trait FPCFEagerAnalysisScheduler[P <: Project] extends FPCFAnalysisScheduler[P] {

    override final def computationType: ComputationType = EagerComputation

    override final def derivesLazily: Option[PropertyBounds] = None

    override final def schedule(ps: PropertyStore, i: InitializationData): FPCFAnalysis = {
        start(ps.context(classOf[Project]).asInstanceOf[P], ps, i)
    }

    /**
     * Starts the analysis for the given `project`. This method is typically implicitly
     * called by the [[FPCFAnalysesManager]].
     */
    def start(p: P, i: InitializationData): FPCFAnalysis = {
        start(p, p.get(PropertyStoreKey), i)
    }

    /**
     * Called when a schedule is executed and when this analysis shall register itself
     * with the property store using
     * [[org.opalj.fpcf.PropertyStore.scheduleEagerComputationForEntity]] or a variant thereof.
     * This method is typically implicitly called by the [[org.opalj.fpcf.FPCFAnalysesManager]].
     *
     * @note This analysis must not call `registerTriggeredComputation` or
     *       `registerLazyPropertyComputation`.
     */
    def start(p: P, ps: PropertyStore, i: InitializationData): FPCFAnalysis
}

/**
 * A simple eager analysis scheduler for those analyses that do not perform special initialization
 * steps.
 */
// TODO Rename => Simple...
trait BasicFPCFEagerAnalysisScheduler[P <: Project] extends FPCFEagerAnalysisScheduler[P] {
    override type InitializationData = Null
    override def init(p:           P, ps: PropertyStore): Null = null
    override def beforeSchedule(p: P, ps: PropertyStore): Unit = {}

    override def afterPhaseScheduling(ps: PropertyStore, analysis: FPCFAnalysis): Unit = {}

    override def afterPhaseCompletion(p: P, ps: PropertyStore, analysis: FPCFAnalysis): Unit = {}
}
