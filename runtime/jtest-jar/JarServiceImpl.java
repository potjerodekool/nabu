/** The provider of JarService, named in META-INF/services of the jar. */
public class JarServiceImpl implements JarService {
    public String name() {
        return "from jar";
    }
}
