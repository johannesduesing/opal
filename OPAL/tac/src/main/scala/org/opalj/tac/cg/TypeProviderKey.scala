/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package cg

import org.opalj.log.LogContext
import org.opalj.log.OPALLogger
import org.opalj.br.analyses.ProjectInformationKey
import org.opalj.br.analyses.ProjectInformationKeys
import org.opalj.br.analyses.SomeProject
import org.opalj.tac.fpcf.analyses.cg.TypeProvider

object TypeProviderKey
    extends ProjectInformationKey[TypeProvider, () ⇒ TypeProvider] {

    override def requirements(project: SomeProject): ProjectInformationKeys = Nil

    override def compute(project: SomeProject): TypeProvider = {
        project.getProjectInformationKeyInitializationData(this) match {
            case Some(init) ⇒
                init()
            case None ⇒
                implicit val logContext: LogContext = project.logContext
                OPALLogger.error(
                    "analysis configuration",
                    s"must configure type provider first"
                )
                throw new IllegalArgumentException()
        }
    }
}