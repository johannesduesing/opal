/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.fixtures.linear_constant_propagation.lcp_on_fields;

import org.opalj.fpcf.properties.linear_constant_propagation.lcp_on_fields.StaticValues;
import org.opalj.fpcf.properties.linear_constant_propagation.lcp.ConstantValue;
import org.opalj.fpcf.properties.linear_constant_propagation.lcp.ConstantValues;
import org.opalj.fpcf.properties.linear_constant_propagation.lcp.VariableValue;

/**
 * An example to test reading and writing private static fields in one method.
 *
 * @author Robin Körkemeier
 */
public class StaticFieldReadWriteExample {
    private static int a;

    @StaticValues(constantValues = {
            @ConstantValue(variable = "a", value = 11)
    })
    @ConstantValues({
            @ConstantValue(variable = "lv5", value = 23),
            @ConstantValue(variable = "lva", value = 11)
    })
    @VariableValue(variable = "lv2")
    public static void main(String[] args) {
        StaticFieldReadWriteExample example1 = new StaticFieldReadWriteExample();

        int a1 = a;

        a = 23;

        int a2 = example1.a;

        example1.a = 11;

        StaticFieldReadWriteExample example2 = new StaticFieldReadWriteExample();

        int a3 = example2.a;

        System.out.println("a1: " + a1 + ", a2: " + a2 + ", a3: " + a3);
    }
}
