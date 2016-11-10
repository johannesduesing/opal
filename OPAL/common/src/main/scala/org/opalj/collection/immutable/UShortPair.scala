/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2016
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package collection
package immutable

/**
 * A memory-efficient representation of a pair of UShortValues which
 * uses one Integer value.
 *
 * @example
 * {{{
 * scala> val p = org.opalj.collection.immutable.UShortPair(2323,332)
 * p: org.opalj.collection.immutable.UShortPair = UShortPair(2323,332)
 * }}}
 *
 * @author Michael Eichberg
 */
final class UShortPair private (val pair: Int) extends AnyVal {
    def _1: UShort = pair & UShort.MaxValue
    def key: UShort = _1
    def minor: UShort = _1
    def _2: UShort = pair >>> 16
    def value: UShort = _2
    def major: UShort = _2
    override def toString: String = s"UShortPair($key,$value)"
}
/**
 * Factory to create `UShortPair` objects.
 */
object UShortPair {

    def apply(a: UShort, b: UShort): UShortPair = {
        assert(a >= UShort.MinValue && a <= UShort.MaxValue)
        assert(b >= UShort.MinValue && b <= UShort.MaxValue)

        new UShortPair(a | b << 16)
    }
}
