package com.ibm.icu.util;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.util.Locale;

@TargetClass(className = "com.ibm.icu.util.ULocale$JDKLocaleHelper")
final class Target_JDKLocaleHelper {

    @Substitute
    public static boolean hasLocaleCategories() {
        return true;
    }

    @Substitute
    public static Locale getDefault(ULocale.Category category) {
        if (category == null) {
            return Locale.getDefault();
        }
        Locale.Category cat = (category == ULocale.Category.DISPLAY)
            ? Locale.Category.DISPLAY
            : Locale.Category.FORMAT;
        return Locale.getDefault(cat);
    }

    @Substitute
    public static void setDefault(ULocale.Category category, Locale newLocale) {
        if (category == null) {
            Locale.setDefault(newLocale);
            return;
        }
        Locale.Category cat = (category == ULocale.Category.DISPLAY)
            ? Locale.Category.DISPLAY
            : Locale.Category.FORMAT;
        Locale.setDefault(cat, newLocale);
    }
}
