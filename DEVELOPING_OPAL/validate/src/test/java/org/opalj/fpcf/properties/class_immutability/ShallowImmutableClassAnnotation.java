/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.properties.class_immutability;

import org.opalj.br.fpcf.FPCFAnalysis;
import org.opalj.fpcf.properties.PropertyValidator;
import org.opalj.fpcf.properties.class_mutability.MutableObjectMatcher;
import org.opalj.tac.fpcf.analyses.LxClassImmutabilityAnalysis_new;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation to state that the annotated class is shallow immutable
 *
 * @author Tobias Peter Roth
 */
@PropertyValidator(key = "ClassImmutability_new",validator = ShallowImmutableClassMatcher.class)
@Documented
@Retention(RetentionPolicy.CLASS)
public @interface ShallowImmutableClassAnnotation {

    /**
     * A short reasoning of this property.
     */
    String value();// default = "N/A";

    Class<? extends FPCFAnalysis>[] analyses() default {LxClassImmutabilityAnalysis_new.class};
}