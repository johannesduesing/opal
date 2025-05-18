/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.fixtures.linear_constant_propagation.lcp;

import org.opalj.fpcf.properties.linear_constant_propagation.lcp.ConstantValue;
import org.opalj.fpcf.properties.linear_constant_propagation.lcp.ConstantValues;

/**
 * An example to test constants (simple and linear) in a single method.
 *
 * @author Robin Körkemeier
 */
public class ConstantsWithinMethodExample {
    @ConstantValues({
            @ConstantValue(variable = "lv0", value = 4),
            @ConstantValue(variable = "lv1", value = 3),
            @ConstantValue(variable = "lv2", value = 12),
            @ConstantValue(variable = "lv3", value = 4),
            @ConstantValue(variable = "lv4", value = 16)
    })
    public static void main(String[] args) {
        int a = 4;
        int b = a;
        int c = 3 * b + 4;

        System.out.println("a: " + a + ", b: " + b + ", c: " + c);
    }
}
