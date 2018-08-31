/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package fpcf
package analyses
package cg

import org.opalj.br.DeclaredMethod
import org.opalj.br.DefinedMethod
import org.opalj.br.ObjectType
import org.opalj.br.analyses.DeclaredMethods
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.br.analyses.SomeProject
import org.opalj.collection.immutable.UIDSet
import org.opalj.fpcf.cg.properties.CallersProperty
import org.opalj.fpcf.cg.properties.LoadedClassesLowerBound
import org.opalj.fpcf.cg.properties.LoadedClasses
import org.opalj.fpcf.cg.properties.NoCallers
import org.opalj.fpcf.cg.properties.LowerBoundCallers
import org.opalj.fpcf.cg.properties.OnlyVMLevelCallers
import org.opalj.tac.Assignment
import org.opalj.tac.ExprStmt
import org.opalj.tac.GetStatic
import org.opalj.tac.PutStatic
import org.opalj.tac.SimpleTACAIKey

/**
 * Computes the set of classes that are being loaded by the VM during the execution of the
 * `project`.
 * Extends the call graph analysis (e.g. [[RTACallGraphAnalysis]]) to include calls to static
 * initializers from within the JVM.
 *
 * @author Florian Kuebler
 */
class LoadedClassesAnalysis(
        val project: SomeProject
) extends FPCFAnalysis {
    private val tacaiProvider = project.get(SimpleTACAIKey)
    private val declaredMethods: DeclaredMethods = project.get(DeclaredMethodsKey)

    /**
     * Each time a method gets reachable in the computation of the call graph
     * (callers are added/it is an entry point [[CallersProperty]]) the declaring class gets loaded
     * (if not already done) by the VM. Furthermore, access to static fields yields the VM to load
     * a class. So for a new reachable method, we further check for such operations.
     * For newly loaded classes, the analysis triggers the computation of the call graph properties
     * ([[org.opalj.fpcf.cg.properties.StandardInvokeCallees]], [[CallersProperty]]) for the static
     * initializer.
     *
     */
    def doAnalyze(project: SomeProject): PropertyComputationResult = {

        PartialResult[SomeProject, LoadedClasses](project, LoadedClasses.key, {
            case EPK(p, _) ⇒ Some(EPS(p, LoadedClassesLowerBound, new LoadedClasses(UIDSet.empty)))
            case _         ⇒ None
        })
    }

    /**
     * If the method in `callersOfMethod` has no callers ([[NoCallers]]), it is not reachable, and
     * its declaring class will not be loaded (at least not via this call).
     *
     * If it is not yet known, we register a dependency to it.
     *
     * In case there are definitively some callers, we remove the potential existing dependency
     * and handle the method being newly reachable (i.e. analyse the field accesses of the method
     * and update its declaring class type as reachable)
     *
     * @return the static initializers, that are definitively not yet processed by the call graph
     *         analysis and became reachable here.
     */
    def handleCaller(
        declaredMethod: DeclaredMethod
    ): PropertyComputationResult = {
        val callersOfMethod = propertyStore(declaredMethod, CallersProperty.key)
        callersOfMethod match {
            case FinalEP(_, NoCallers) ⇒
                // nothing to do, since there is no caller
                NoResult
            case _: EPK[_, _] ⇒
                throw new IllegalStateException("unexpected state")
            case EPS(_, _, NoCallers) ⇒
                // we can not create a dependency here, so the analysis is not allowed to create
                // such a result
                throw new IllegalStateException("illegal immediate result for callers")
            case _: EPS[_, _] ⇒

                // the method has callers. we have to analyze it
                val (newCLInits, newLoadedClasses) = handleNewReachableMethod(callersOfMethod.e)

                if (newLoadedClasses.nonEmpty) {
                    val lcResult = PartialResult[SomeProject, LoadedClasses](project, LoadedClasses.key, {
                        case EPK(p, _) ⇒
                            Some(
                                EPS(p, LoadedClassesLowerBound, new LoadedClasses(newLoadedClasses))
                            )
                        case EPS(p, lb, ub) ⇒
                            val newUb = ub.classes ++ newLoadedClasses
                            // due to monotonicity:
                            // the size check sufficiently replaces the subset check
                            if (newUb.size > ub.classes.size)
                                Some(EPS(p, lb, new LoadedClasses(newUb)))
                            else
                                None

                    })

                    val callersResult = newCLInits map { clInit ⇒
                        PartialResult[DeclaredMethod, CallersProperty](clInit, CallersProperty.key, {
                            case EPK(_, _) ⇒
                                Some(EPS(
                                    clInit,
                                    LowerBoundCallers,
                                    OnlyVMLevelCallers
                                ))
                            case EPS(_, lb, ub) if !ub.hasCallersWithUnknownContext ⇒
                                Some(EPS(clInit, lb, ub.updatedWithVMLevelCall()))
                            case _ ⇒ None
                        })
                    }
                    Results(Seq(lcResult) ++ callersResult)
                } else {
                    NoResult
                }
        }
    }

    /**
     * For a reachable method, its declaring class will be loaded by the VM (if not done already).
     * In order to ensure this, the `state` will be updated.
     *
     * Furthermore, the method may access static fields, which again may lead to class loading.
     *
     * @return the static initializers, that became reachable and were not yet processed by the
     *         call graph analysis.
     *
     */
    def handleNewReachableMethod(
        dm: DeclaredMethod
    ): (Set[DeclaredMethod], UIDSet[ObjectType]) = {
        if (!dm.hasSingleDefinedMethod)
            return (Set.empty, UIDSet.empty)

        val method = dm.definedMethod
        val methodDCT = method.classFile.thisType
        assert(dm.declaringClassType eq methodDCT)

        var newCLInits = Set.empty[DeclaredMethod]
        var newLoadedClasses = UIDSet.empty[ObjectType]

        val currentLoadedClassesEPS: EOptionP[SomeProject, LoadedClasses] =
            propertyStore(project, LoadedClasses.key)

        val currentLoadedClasses = currentLoadedClassesEPS match {
            case _: EPK[_, _]  ⇒ UIDSet.empty[ObjectType]
            case EPS(_, _, ub) ⇒ ub.classes
        }

        @inline def isNewLoadedClass(dc: ObjectType): Boolean = {
            !currentLoadedClasses.contains(dc) && !newLoadedClasses.contains(dc)
        }

        // whenever a method is called the first time, its declaring class gets loaded
        if (isNewLoadedClass(methodDCT)) {
            // todo only for interfaces with default methods
            newLoadedClasses ++= ch.allSupertypes(methodDCT)
            LoadedClassesAnalysis.retrieveStaticInitializers(
                methodDCT, declaredMethods, project
            ).foreach(newCLInits += _)
        }

        @inline def loadClass(objectType: ObjectType): Unit = {
            LoadedClassesAnalysis.retrieveStaticInitializers(
                objectType, declaredMethods, project
            ).foreach(newCLInits += _)
            newLoadedClasses += objectType
        }

        if (method.body.isDefined) {
            for (stmt ← tacaiProvider(method).stmts) {
                stmt match {
                    case PutStatic(_, dc, _, _, _) if isNewLoadedClass(dc) ⇒
                        loadClass(dc)
                    case Assignment(_, _, GetStatic(_, dc, _, _)) if isNewLoadedClass(dc) ⇒
                        loadClass(dc)
                    case ExprStmt(_, GetStatic(_, dc, _, _)) if isNewLoadedClass(dc) ⇒
                        loadClass(dc)
                    case _ ⇒
                }
            }
        }

        (newCLInits, newLoadedClasses)
    }
}

object LoadedClassesAnalysis {
    /**
     * Retrieves the static initializer of the given type if present.
     */
    def retrieveStaticInitializers(
        declaringClassType: ObjectType, declaredMethods: DeclaredMethods, project: SomeProject
    ): Set[DefinedMethod] = {
        // todo only for interfaces with default methods
        project.classHierarchy.allSupertypes(declaringClassType, reflexive = true) flatMap { t ⇒
            project.classFile(t) flatMap { cf ⇒
                cf.staticInitializer map { clInit ⇒
                    // IMPROVE: Only return the static initializer if it is not already present
                    declaredMethods(clInit)
                }
            }
        }
    }
}

object EagerLoadedClassesAnalysis extends FPCFEagerAnalysisScheduler {

    override type InitializationData = LoadedClassesAnalysis

    override def start(
        project:               SomeProject,
        propertyStore:         PropertyStore,
        loadedClassesAnalysis: LoadedClassesAnalysis
    ): FPCFAnalysis = {
        propertyStore.scheduleEagerComputationsForEntities(List(project))(
            loadedClassesAnalysis.doAnalyze
        )
        loadedClassesAnalysis
    }

    override def uses: Set[PropertyKind] = Set(CallersProperty)

    override def derives: Set[PropertyKind] = Set(LoadedClasses, CallersProperty)

    override def init(p: SomeProject, ps: PropertyStore): LoadedClassesAnalysis = {
        val analysis = new LoadedClassesAnalysis(p)
        ps.registerTriggeredComputation(CallersProperty.key, analysis.handleCaller)
        analysis
    }

    override def beforeSchedule(p: SomeProject, ps: PropertyStore): Unit = {}

    override def afterPhaseCompletion(p: SomeProject, ps: PropertyStore): Unit = {}
}
