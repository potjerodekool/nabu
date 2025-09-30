# Dependency injection

A simple dependency framework
that is used to support plugins for the Nabu compiler.

Create a transformer:
    
    package foo.bar;

    public class MyTransformer implements CodeTransformer {
    
        public void transform(CompilationUnit unit) {
            //Tranform the compilation unit.
        }
    }

Configure the transformer

    package foo.bar;

    @AutoConfiguration
    public class MyPluginConfiguration {

        @Bean(scope = StandardScopes.PROTOTYPE)
        public MyTransformer transformer(final CompilerContext context) {
            return new MyTransformer(context);
        }
    }

Create a config file which contains the configuration class name. 

META-INF/io.github.potjerodekool.autoconfig.AutoConfiguration

    foo.bar.MyPluginConfiguration