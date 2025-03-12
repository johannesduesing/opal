/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.properties.linear_constant_propagation;

import org.opalj.fpcf.properties.PropertyValidator;

import java.lang.annotation.*;

/**
 * Container annotation for {@link ConstantValue} annotations.
 *
 * @author Robin Körkemeier
 */
@PropertyValidator(key = LinearConstantPropagationProperty.KEY, validator = ConstantValueMatcher.class)
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ConstantValues {
    ConstantValue[] value();
}

