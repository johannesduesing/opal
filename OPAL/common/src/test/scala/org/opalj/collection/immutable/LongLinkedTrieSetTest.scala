/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package collection
package immutable

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class LongLinkedTrieSetTest extends LongSetTest {

    def empty(): LongSet = LongLinkedTrieSet.empty

    // The following methods should be implemented using the most fitting
    // factory methods.
    def create(v1: Long): LongSet = LongLinkedTrieSet(v1)
    def create(v1: Long, v2: Long): LongSet = LongLinkedTrieSet(v1, v2)
    def create(v1: Long, v2: Long, v3: Long): LongSet = LongLinkedTrieSet(v1, v2) + v3
    def create(v1: Long, v2: Long, v3: Long, v4: Long): LongSet = LongLinkedTrieSet(v1, v2) + v3 + v4

}
