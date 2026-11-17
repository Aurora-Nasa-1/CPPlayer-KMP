package com.ibm.icu.text;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "com.ibm.icu.text.BreakIterator")
final class Target_BreakIterator {

    @Substitute
    private static BreakIterator.BreakIteratorServiceShim getShim() {
        return new BreakIteratorFactory();
    }
}
