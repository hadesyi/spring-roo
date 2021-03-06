package org.springframework.roo.addon.plural.addon;

import org.springframework.roo.addon.plural.addon.PluralAnnotationValues;
import org.springframework.roo.addon.plural.annotations.RooPlural;
import org.springframework.roo.classpath.details.annotations.populator.AnnotationValuesTestCase;

/**
 * Unit test of {@link PluralAnnotationValues}
 * 
 * @author Andrew Swan
 * @since 1.2.0
 */
public class PluralAnnotationValuesTest extends
        AnnotationValuesTestCase<RooPlural, PluralAnnotationValues> {

    @Override
    protected Class<RooPlural> getAnnotationClass() {
        return RooPlural.class;
    }

    @Override
    protected Class<PluralAnnotationValues> getValuesClass() {
        return PluralAnnotationValues.class;
    }
}
