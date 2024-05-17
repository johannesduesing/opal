/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package string
package l0
package interpretation

import org.opalj.br.ObjectType
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.properties.string.StringTreeConst
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.PropertyStore
import org.opalj.tac.fpcf.properties.TACAI
import org.opalj.tac.fpcf.properties.string.StringFlowFunction
import org.opalj.tac.fpcf.properties.string.StringTreeEnvironment

/**
 * @author Maximilian Rüsch
 */
case class L0StaticFunctionCallInterpreter()(
    implicit
    override val p:  SomeProject,
    override val ps: PropertyStore
) extends AssignmentBasedStringInterpreter
    with L0ArbitraryStaticFunctionCallInterpreter
    with L0StringValueOfFunctionCallInterpreter {

    override type E = StaticFunctionCall[V]

    override def interpretExpr(target: V, call: E)(implicit
        state: InterpretationState
    ): ProperPropertyComputationResult = {
        call.name match {
            case "valueOf" if call.declaringClass == ObjectType.String => processStringValueOf(target, call)
            case _                                                     => interpretArbitraryCall(target, call)
        }
    }
}

private[string] trait L0ArbitraryStaticFunctionCallInterpreter
    extends AssignmentBasedStringInterpreter
    with L0FunctionCallInterpreter {

    implicit val p: SomeProject

    override type E <: StaticFunctionCall[V]

    def interpretArbitraryCall(target: V, call: E)(implicit
        state: InterpretationState
    ): ProperPropertyComputationResult = {
        val calleeMethod = call.resolveCallTarget(state.dm.definedMethod.classFile.thisType)
        if (calleeMethod.isEmpty) {
            return computeFinalLBFor(target)
        }

        val m = calleeMethod.value
        val pt = target.toPersistentForm(state.tac.stmts)
        val params = getParametersForPC(state.pc).map(_.asVar.toPersistentForm(state.tac.stmts))
        val callState = FunctionCallState(state, pt, Seq(m), params, Map((m, ps(m, TACAI.key))))

        interpretArbitraryCallToFunctions(callState)
    }
}

private[string] trait L0StringValueOfFunctionCallInterpreter extends AssignmentBasedStringInterpreter {

    override type E <: StaticFunctionCall[V]

    def processStringValueOf(target: V, call: E)(implicit state: InterpretationState): ProperPropertyComputationResult = {
        val pt = target.toPersistentForm(state.tac.stmts)
        val pp = call.params.head.asVar.toPersistentForm(state.tac.stmts)

        val flowFunction: StringFlowFunction = if (call.descriptor.parameterType(0).toJava == "char") {
            (env: StringTreeEnvironment) =>
                {
                    env(pp) match {
                        case const: StringTreeConst if const.isIntConst =>
                            env.update(pt, StringTreeConst(const.string.toInt.toChar.toString))
                        case tree =>
                            env.update(pt, tree)
                    }
                }
        } else {
            (env: StringTreeEnvironment) => env.update(pt, env(pp))
        }

        computeFinalResult(flowFunction)
    }
}
