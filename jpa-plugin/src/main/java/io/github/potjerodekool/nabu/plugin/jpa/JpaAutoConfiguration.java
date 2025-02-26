package io.github.potjerodekool.nabu.plugin.jpa;

import io.github.potjerodekool.autoconfig.AutoConfiguration;
import io.github.potjerodekool.dependencyinjection.bean.Bean;
import io.github.potjerodekool.dependencyinjection.scope.StandardScopes;
import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolResolver;
import io.github.potjerodekool.nabu.plugin.jpa.transform.JpaSymbolResolver;
import io.github.potjerodekool.nabu.plugin.jpa.transform.JpaTransformer;

@AutoConfiguration
public class JpaAutoConfiguration {

    @Bean
    public SymbolResolver jpaSymbolResolver(final ClassElementLoader loader) {
        return new JpaSymbolResolver(loader);
    }

    @Bean(scope = StandardScopes.PROTOTYPE)
    public JpaTransformer jpaTransformer(final CompilerContext compilerContext) {
        return new JpaTransformer(compilerContext);
    }
}
