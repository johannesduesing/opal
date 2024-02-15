/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package string_analysis
package interpretation

import org.opalj.br.ComputationalTypeFloat
import org.opalj.br.ComputationalTypeInt

/**
 * @author Maximilian Rüsch
 */
case class BinaryExprInterpreter[State <: ComputationState[State]]() extends SingleStepStringInterpreter[State] {

    override type T = BinaryExpr[V]

    /**
     * Currently, this implementation supports the interpretation of the following binary expressions:
     * <ul>
     * <li>[[ComputationalTypeInt]]
     * <li>[[ComputationalTypeFloat]]</li>
     * </li>
     * For all other expressions, a [[NoIPResult]] will be returned.
     */
    def interpret(instr: T, defSite: Int)(implicit state: State): NonRefinableIPResult = {
        val defSitePC = pcOfDefSite(defSite)(state.tac.stmts)
        instr.cTpe match {
            case ComputationalTypeInt =>
                FinalIPResult(InterpretationHandler.getConstancyInfoForDynamicInt, state.dm, defSitePC)
            case ComputationalTypeFloat =>
                FinalIPResult(InterpretationHandler.getConstancyInfoForDynamicFloat, state.dm, defSitePC)
            case _ =>
                NoIPResult(state.dm, defSitePC)
        }
    }
}

object BinaryExprInterpreter {

    def interpret[State <: ComputationState[State]](instr: BinaryExpr[V], defSite: Int)(implicit state: State): IPResult =
        BinaryExprInterpreter[State]().interpret(instr, defSite)
}
