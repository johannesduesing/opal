/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package fpcf
package properties
package immutability
package field_assignability

import org.opalj.br.AnnotationLike
import org.opalj.br.ObjectType
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.properties.immutability.Assignable

/**
 * Matches mutable field references
 * @author Tobias Peter Roth
 */
class AssignableFieldReferenceMatcher extends AbstractPropertyMatcher {

    val property = Assignable

    final private val PropertyReasonID = 0

    override def isRelevant(
        p:      SomeProject,
        as:     Set[ObjectType],
        entity: Object,
        a:      AnnotationLike
    ): Boolean = {

        val annotationType = a.annotationType.asObjectType

        val analysesElementValues =
            getValue(p, annotationType, a.elementValuePairs, "analyses").asArrayValue.values

        val analyses = analysesElementValues.map(ev ⇒ ev.asClassValue.value.asObjectType)

        if (!analyses.exists(as.contains)) return false;
        true

        // val prematurelyRead = getValue(p, annotationType, a.elementValuePairs, "prematurelyRead")
        //     .asInstanceOf[BooleanValue]
        //     .value

        /*   if (prematurelyRead) {
            val propertyStore = p.get(PropertyStoreKey)
            propertyStore(entity, FieldPrematurelyRead.key) match {
                case FinalP(PrematurelyReadField) ⇒ true
                case _                            ⇒ false
            }
        } else {
            true
        } */
    }

    def validateProperty(
        p:          SomeProject,
        as:         Set[ObjectType],
        entity:     Entity,
        a:          AnnotationLike,
        properties: Traversable[Property]
    ): Option[String] = {
        if (!properties.exists(p ⇒ p == property)) {
            // ... when we reach this point the expected property was not found.
            Some(a.elementValuePairs(PropertyReasonID).value.asStringValue.value)
        } else {
            None
        }
    }
}
