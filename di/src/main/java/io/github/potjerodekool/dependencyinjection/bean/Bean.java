package io.github.potjerodekool.dependencyinjection.bean;

import io.github.potjerodekool.dependencyinjection.scope.StandardScopes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(
        {
                ElementType.TYPE,
                ElementType.METHOD,
        }
)
public @interface Bean {

        String scope() default StandardScopes.APPLICATION;
}
