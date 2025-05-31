/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package ide
package instances
package lcp_on_fields

import scala.collection.immutable

import org.opalj.br.analyses.DeclaredFieldsKey
import org.opalj.br.analyses.ProjectInformationKeys
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.properties.immutability.FieldAssignability
import org.opalj.fpcf.PropertyBounds
import org.opalj.tac.fpcf.analyses.ide.instances.lcp_on_fields.problem.LCPOnFieldsFact
import org.opalj.tac.fpcf.analyses.ide.instances.lcp_on_fields.problem.LCPOnFieldsProblem
import org.opalj.tac.fpcf.analyses.ide.instances.lcp_on_fields.problem.LCPOnFieldsValue
import org.opalj.tac.fpcf.analyses.ide.instances.linear_constant_propagation.LinearConstantPropagationPropertyMetaInformation
import org.opalj.tac.fpcf.analyses.ide.integration.JavaIDEAnalysisScheduler
import org.opalj.tac.fpcf.analyses.ide.integration.JavaIDEAnalysisSchedulerBase
import org.opalj.tac.fpcf.analyses.ide.integration.JavaIDEPropertyMetaInformation
import org.opalj.tac.fpcf.analyses.ide.problem.JavaIDEProblem
import org.opalj.tac.fpcf.analyses.ide.solver.JavaICFG

/**
 * Linear constant propagation on fields as IDE analysis. This implementation is mainly intended to be an example of a
 * cyclic IDE analysis (see [[org.opalj.tac.fpcf.analyses.ide.instances.lcp_on_fields.problem.LCPOnFieldsProblem]]).
 *
 * @author Robin Körkemeier
 */
class LCPOnFieldsAnalysisScheduler extends JavaIDEAnalysisScheduler[LCPOnFieldsFact, LCPOnFieldsValue]
    with JavaIDEAnalysisSchedulerBase.ForwardICFG {
    override def propertyMetaInformation: JavaIDEPropertyMetaInformation[LCPOnFieldsFact, LCPOnFieldsValue] =
        LCPOnFieldsPropertyMetaInformation

    override def createProblem(project: SomeProject, icfg: JavaICFG): JavaIDEProblem[
        LCPOnFieldsFact,
        LCPOnFieldsValue
    ] = {
        new LCPOnFieldsProblem(project, icfg)
    }

    override def requiredProjectInformation: ProjectInformationKeys =
        super.requiredProjectInformation :+ DeclaredFieldsKey

    override def uses: Set[PropertyBounds] =
        super.uses ++ immutable.Set(
            PropertyBounds.ub(FieldAssignability),
            PropertyBounds.ub(LinearConstantPropagationPropertyMetaInformation)
        )
}
