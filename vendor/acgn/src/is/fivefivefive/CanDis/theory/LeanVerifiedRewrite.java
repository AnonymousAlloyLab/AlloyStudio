package is.fivefivefive.CanDis.theory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a Java rewrite entry point to immutable rule identifiers in the
 * independently checked Lean/Java rewrite catalog.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LeanVerifiedRewrite {
    String[] value();
}
